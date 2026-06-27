package com.workez365.smsbridge

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 메인 화면 (로그인 후 진입)
 *
 * - Auth 상태 감지: 로그아웃 상태가 되면 즉시 LoginActivity로 이동
 * - 역할(Master/Slave), 기기 ID 설정
 * - SmsBridgeService 시작 / 중지
 * - Master 모드: Firestore에 SMS 발송 명령 추가
 */
class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var authStateListener: FirebaseAuth.AuthStateListener

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val requiredPermissions: Array<String>
        get() {
            val perms = mutableListOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_SMS,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return perms.toTypedArray()
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val denied = results.filterValues { !it }.keys
            if (denied.isNotEmpty()) showPermissionDeniedDialog(denied)
            else promptDefaultSmsAppIfNeeded()  // [fix-3] 권한 허용 후 기본 앱 설정 유도
        }

    // [fix-3] 기본 SMS 앱 지정 요청 결과 처리
    private val defaultSmsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val isDefault = isDefaultSmsApp()
            Toast.makeText(
                this,
                if (isDefault) "기본 SMS 앱으로 설정되었습니다." else "기본 SMS 앱 미설정: 일부 기기에서 SMS 수신이 제한될 수 있습니다.",
                Toast.LENGTH_LONG
            ).show()
        }

    private lateinit var rgRole: RadioGroup
    private lateinit var rbSlave: RadioButton
    private lateinit var rbMaster: RadioButton
    private lateinit var etDeviceId: EditText
    private lateinit var btnSaveSettings: Button
    private lateinit var btnToggleService: Button
    private lateinit var btnLogout: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCurrentUser: TextView

    // Master 패널
    private lateinit var layoutMasterPanel: View
    private lateinit var etTargetDevice: EditText
    private lateinit var etTo: EditText
    private lateinit var etMessage: EditText
    private lateinit var btnSendCommand: Button
    private lateinit var tvCommandResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = FirebaseAuth.getInstance()

        // 로그인 상태가 아니면 LoginActivity로 리다이렉트
        if (auth.currentUser == null) {
            navigateToLogin()
            return
        }

        setContentView(R.layout.activity_main)
        initViews()
        loadSettings()
        setupListeners()
        checkAndRequestPermissions()
        // [fix-3] Slave 모드일 때만 기본 SMS 앱 설정 유도 (권한 허용 완료 후에도 호출됨)
        promptDefaultSmsAppIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        // Auth 상태 리스너 등록: 다른 기기에서 로그아웃 or 계정 삭제 시 즉시 감지
        authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            if (firebaseAuth.currentUser == null) {
                // 서비스 중지 후 로그인 화면으로
                stopBridgeService()
                navigateToLogin()
            }
        }
        auth.addAuthStateListener(authStateListener)
    }

    override fun onStop() {
        super.onStop()
        auth.removeAuthStateListener(authStateListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    // -------------------------------------------------------
    // 뷰 초기화 & 설정 로드
    // -------------------------------------------------------

    private fun initViews() {
        rgRole           = findViewById(R.id.rg_role)
        rbSlave          = findViewById(R.id.rb_slave)
        rbMaster         = findViewById(R.id.rb_master)
        etDeviceId       = findViewById(R.id.et_device_id)
        btnSaveSettings  = findViewById(R.id.btn_save_settings)
        btnToggleService = findViewById(R.id.btn_toggle_service)
        btnLogout        = findViewById(R.id.btn_logout)
        tvStatus         = findViewById(R.id.tv_status)
        tvCurrentUser    = findViewById(R.id.tv_current_user)

        layoutMasterPanel = findViewById(R.id.layout_master_panel)
        etTargetDevice    = findViewById(R.id.et_target_device)
        etTo              = findViewById(R.id.et_to)
        etMessage         = findViewById(R.id.et_message)
        btnSendCommand    = findViewById(R.id.btn_send_command)
        tvCommandResult   = findViewById(R.id.tv_command_result)
    }

    private fun loadSettings() {
        val email = auth.currentUser?.email ?: ""
        tvCurrentUser.text = "계정: $email"

        val role = AppSettings.getRole(this)
        if (role == AppSettings.ROLE_MASTER) rbMaster.isChecked = true
        else rbSlave.isChecked = true

        etDeviceId.setText(AppSettings.getDeviceId(this))
        updateServiceButton()
        updateMasterPanelVisibility()
    }

    private fun setupListeners() {
        btnSaveSettings.setOnClickListener { saveSettings() }
        btnToggleService.setOnClickListener { toggleService() }
        btnSendCommand.setOnClickListener { sendSmsCommand() }

        rgRole.setOnCheckedChangeListener { _, _ -> updateMasterPanelVisibility() }

        btnLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("로그아웃")
                .setMessage("로그아웃하면 서비스가 중지됩니다. 계속하시겠습니까?")
                .setPositiveButton("로그아웃") { _, _ ->
                    stopBridgeService()
                    AppSettings.setServiceEnabled(this, false)
                    auth.signOut() // → authStateListener가 감지하여 LoginActivity로 이동
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    // -------------------------------------------------------
    // 설정 저장
    // -------------------------------------------------------

    private fun saveSettings() {
        val role     = if (rbMaster.isChecked) AppSettings.ROLE_MASTER else AppSettings.ROLE_SLAVE
        val deviceId = etDeviceId.text.toString().trim()

        if (deviceId.isBlank()) {
            Toast.makeText(this, "기기 ID를 입력해주세요. (예: slave-phone-01)", Toast.LENGTH_SHORT).show()
            return
        }

        AppSettings.setRole(this, role)
        AppSettings.setDeviceId(this, deviceId)

        Toast.makeText(this, "설정 저장 완료", Toast.LENGTH_SHORT).show()
    }

    // -------------------------------------------------------
    // 서비스 시작 / 중지
    // -------------------------------------------------------

    private fun toggleService() {
        if (AppSettings.isServiceEnabled(this)) {
            stopBridgeService()
        } else {
            if (!hasRequiredPermissions()) {
                Toast.makeText(this, "먼저 권한을 허용해주세요.", Toast.LENGTH_SHORT).show()
                checkAndRequestPermissions()
                return
            }
            if (AppSettings.getDeviceId(this).isBlank()) {
                Toast.makeText(this, "설정을 먼저 저장해주세요.", Toast.LENGTH_SHORT).show()
                return
            }
            startBridgeService()
        }
    }

    private fun startBridgeService() {
        AppSettings.setServiceEnabled(this, true)
        // [fix-6] SmsBridgeService.start() 헬퍼로 버전 분기 중복 제거
        SmsBridgeService.start(this)
        updateServiceButton()
        tvStatus.text = "상태: 서비스 실행 중 (Firebase 연결)"
    }

    private fun stopBridgeService() {
        if (!AppSettings.isServiceEnabled(this)) return
        AppSettings.setServiceEnabled(this, false)
        startService(SmsBridgeService.stopIntent(this))
        updateServiceButton()
        tvStatus.text = "상태: 서비스 중지됨"
    }

    private fun updateServiceButton() {
        val running = AppSettings.isServiceEnabled(this)
        btnToggleService.text = if (running) "서비스 중지" else "서비스 시작"
    }

    // -------------------------------------------------------
    // Master 패널
    // -------------------------------------------------------

    private fun updateMasterPanelVisibility() {
        layoutMasterPanel.visibility =
            if (rbMaster.isChecked) View.VISIBLE else View.GONE
    }

    private fun sendSmsCommand() {
        val targetDevice = etTargetDevice.text.toString().trim()
        val to           = etTo.text.toString().trim()
        val message      = etMessage.text.toString().trim()

        if (targetDevice.isBlank()) {
            etTargetDevice.error = "대상 기기 ID를 입력하세요"
            etTargetDevice.requestFocus()
            return
        }
        if (to.isBlank()) {
            etTo.error = "수신자 번호를 입력하세요"
            etTo.requestFocus()
            return
        }
        if (message.isBlank()) {
            etMessage.error = "메시지를 입력하세요"
            etMessage.requestFocus()
            return
        }

        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(this, "로그인이 필요합니다.", Toast.LENGTH_SHORT).show()
            return
        }

        btnSendCommand.isEnabled = false
        showCommandResult("전송 중...", "#888888")

        activityScope.launch {
            try {
                val data = hashMapOf(
                    "target_device_id" to targetDevice,
                    "to"               to to,
                    "message"          to message,
                    "status"           to "pending",
                    "created_at"       to Timestamp.now()
                )
                FirestorePaths.commandsCollection().add(data).await()

                etTo.text.clear()
                etMessage.text.clear()
                showCommandResult("발송 명령 전송 완료", "#4CAF50")
            } catch (e: Exception) {
                showCommandResult("전송 실패: ${e.message}", "#F44336")
            } finally {
                btnSendCommand.isEnabled = true
            }
        }
    }

    private fun showCommandResult(text: String, colorHex: String) {
        tvCommandResult.text      = text
        tvCommandResult.setTextColor(android.graphics.Color.parseColor(colorHex))
        tvCommandResult.visibility = View.VISIBLE
    }

    // -------------------------------------------------------
    // 권한 처리
    // -------------------------------------------------------

    private fun hasRequiredPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndRequestPermissions() {
        val notGranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isEmpty()) return

        val needRationale = notGranted.any { shouldShowRequestPermissionRationale(it) }
        if (needRationale) {
            AlertDialog.Builder(this)
                .setTitle("권한 허용 필요")
                .setMessage("SMS 수신·발신 권한과 알림 권한이 필요합니다.")
                .setPositiveButton("허용") { _, _ -> permissionLauncher.launch(notGranted.toTypedArray()) }
                .setNegativeButton("취소", null)
                .show()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun showPermissionDeniedDialog(denied: Set<String>) {
        AlertDialog.Builder(this)
            .setTitle("권한 거부됨")
            .setMessage("일부 기능이 동작하지 않을 수 있습니다.\n설정 > 앱 > 권한 에서 수동으로 허용해주세요.")
            .setPositiveButton("확인", null)
            .show()
    }

    // -------------------------------------------------------
    // [fix-3] 기본 SMS 앱 설정 유도 (Play Store 정책 준수)
    // -------------------------------------------------------

    private fun isDefaultSmsApp(): Boolean =
        Telephony.Sms.getDefaultSmsPackage(this) == packageName

    /**
     * Slave 모드이고 기본 SMS 앱이 아닐 때 사용자에게 설정 변경을 유도.
     * Android 10+: RoleManager API 사용 (시스템 다이얼로그)
     * Android 9 이하: 설정 Intent 사용
     */
    private fun promptDefaultSmsAppIfNeeded() {
        if (!AppSettings.isSlaveMode(this)) return  // Master 모드는 불필요
        if (isDefaultSmsApp()) return               // 이미 기본 앱이면 스킵

        AlertDialog.Builder(this)
            .setTitle("기본 SMS 앱 설정 필요")
            .setMessage(
                "안정적인 SMS 수신을 위해 'SMS 브릿지'를 기본 SMS 앱으로 설정해야 합니다.\n\n" +
                "기본 앱으로 설정하지 않으면 일부 기기에서 문자 수신이 누락될 수 있습니다."
            )
            .setPositiveButton("설정하기") { _, _ -> requestDefaultSmsApp() }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun requestDefaultSmsApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: RoleManager로 시스템 권한 다이얼로그 표시
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                defaultSmsLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS))
            }
        } else {
            // Android 9 이하: 구 방식
            @Suppress("DEPRECATION")
            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            defaultSmsLauncher.launch(intent)
        }
    }

    // -------------------------------------------------------
    // 네비게이션
    // -------------------------------------------------------

    private fun navigateToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
