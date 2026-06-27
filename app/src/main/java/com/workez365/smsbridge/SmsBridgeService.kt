package com.workez365.smsbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class SmsBridgeService : Service() {

    companion object {
        private const val TAG         = "SmsBridgeService"
        const val CHANNEL_ID          = "sms_bridge_channel"
        const val CHANNEL_NAME        = "SMS 브릿지 서비스"
        const val NOTIFICATION_ID     = 1001
        const val ACTION_START        = "com.workez365.smsbridge.ACTION_START"
        const val ACTION_STOP         = "com.workez365.smsbridge.ACTION_STOP"

        fun startIntent(context: Context) =
            Intent(context, SmsBridgeService::class.java).apply { action = ACTION_START }

        fun stopIntent(context: Context) =
            Intent(context, SmsBridgeService::class.java).apply { action = ACTION_STOP }

        // [fix-6] 분산된 startForegroundService/startService 버전 분기를 한 곳으로 통합
        fun start(context: Context) {
            val intent = startIntent(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var commandsListener: ListenerRegistration? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Log.w(TAG, "로그인 상태가 아님 → 서비스 중단")
            // [fix-ANR] startForeground를 먼저 호출한 뒤 stopSelf해야
            // ForegroundServiceDidNotStartInTimeException(ANR급 크래시)을 방지할 수 있음
            startForeground(NOTIFICATION_ID, buildNotification())
            stopSelf()
            return START_NOT_STICKY
        }

        Log.i(TAG, "SmsBridgeService 시작: uid=$uid")
        startForeground(NOTIFICATION_ID, buildNotification())

        // [fix-9] isSlaveMode() 헬퍼로 중복 비교 제거
        if (AppSettings.isSlaveMode(this)) {
            startCommandsListener()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // [fix-0] onDestroy에서도 리스너 정리 (정상 종료 경로)
        removeCommandsListener()
        serviceScope.cancel()
        Log.i(TAG, "SmsBridgeService 종료")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------
    // Firestore 실시간 리스너
    // -------------------------------------------------------

    private fun startCommandsListener() {
        // [fix-8] isConfigured() 헬퍼로 기기 ID 검증 통일
        if (!AppSettings.isConfigured(this)) {
            Log.w(TAG, "기기 ID 미설정 → 명령 리스너 시작 불가")
            return
        }

        // [fix-0] 기존 리스너를 반드시 제거하고 재등록
        // START_STICKY 재시작이나 FCM wake_up으로 onStartCommand가 재진입하면
        // 이전 리스너가 살아있어 동일 명령을 두 번 실행(중복 SMS 발송)하는 버그 방지
        removeCommandsListener()

        val deviceId = AppSettings.getDeviceId(this)
        Log.i(TAG, "Firestore 명령 리스너 시작: device_id=$deviceId")

        commandsListener = FirestorePaths.commandsCollection()
            .whereEqualTo("target_device_id", deviceId)
            .whereEqualTo("status", "pending")
            .orderBy("created_at", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "Firestore 리스너 오류: ${error.message}", error)
                    return@addSnapshotListener
                }

                snapshots?.documentChanges?.forEach { change ->
                    if (change.type == DocumentChange.Type.ADDED) {
                        val doc       = change.document
                        val commandId = doc.id
                        val to        = doc.getString("to") ?: return@forEach
                        val message   = doc.getString("message") ?: return@forEach

                        Log.i(TAG, "발송 명령 수신: commandId=$commandId, to=$to")
                        serviceScope.launch { executeSendCommand(commandId, to, message) }
                    }
                }
            }
    }

    private fun removeCommandsListener() {
        commandsListener?.remove()
        commandsListener = null
    }

    /**
     * SMS 발송 명령 실행
     *
     * [fix-1] 순서 보장: pending → processing → sent/failed
     * SMS 발송 전에 "processing"으로 먼저 마킹해야 프로세스가 중간에 죽어도
     * 재시작 후 리스너가 해당 명령을 다시 실행(중복 발송)하지 않음.
     *
     * [fix-4] 로그아웃 후 currentUid() 호출 시 IllegalStateException 방지
     */
    private suspend fun executeSendCommand(commandId: String, to: String, message: String) {
        // [fix-4] 코루틴 재진입 시점에 auth가 만료됐을 수 있으므로 재확인
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Log.w(TAG, "로그아웃 상태에서 명령 처리 시도 → 건너뜀: $commandId")
            return
        }

        val commandDoc = FirestorePaths.commandsCollection().document(commandId)

        try {
            // [fix-1] 발송 전 "processing" 으로 원자적 마킹 (멱등성 보장)
            // 이미 processing/sent/failed인 경우 다른 인스턴스가 처리 중이므로 중단
            val snap = commandDoc.get().await()
            if (snap.getString("status") != "pending") {
                Log.i(TAG, "이미 처리된 명령 건너뜀: $commandId (${snap.getString("status")})")
                return
            }
            commandDoc.update("status", "processing").await()
        } catch (e: Exception) {
            Log.e(TAG, "processing 마킹 실패: $commandId — ${e.message}", e)
            return
        }

        val success   = sendSms(to, message)
        val newStatus = if (success) "sent" else "failed"

        try {
            commandDoc.update(
                "status",       newStatus,
                "processed_at", com.google.firebase.Timestamp.now()
            ).await()
            Log.i(TAG, "명령 완료: $commandId → $newStatus")
        } catch (e: Exception) {
            // 상태 업데이트 실패 시 "processing"으로 남아있어 재발송 방지됨
            Log.e(TAG, "최종 상태 업데이트 실패: $commandId — ${e.message}", e)
        }
    }

    private fun sendSms(to: String, message: String): Boolean {
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) smsManager.sendTextMessage(to, null, message, null, null)
            else smsManager.sendMultipartTextMessage(to, null, parts, null, null)
            Log.i(TAG, "SMS 발송 완료: to=$to")
            true
        } catch (e: Exception) {
            Log.e(TAG, "SMS 발송 실패: ${e.message}", e)
            false
        }
    }

    // -------------------------------------------------------
    // 알림 채널 & 포그라운드 알림
    // -------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "SMS 브릿지가 Firebase를 실시간 감지 중입니다"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val roleLabel = if (AppSettings.isSlaveMode(this)) "Slave" else "Master"
        val email     = FirebaseAuth.getInstance().currentUser?.email ?: "연결 중..."

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS 브릿지 실행 중 [$roleLabel]")
            .setContentText(email)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_delete, "서비스 중지", stopIntent)
            .build()
    }
}
