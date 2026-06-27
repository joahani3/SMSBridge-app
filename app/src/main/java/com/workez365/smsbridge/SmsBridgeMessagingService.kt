package com.workez365.smsbridge

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * FCM 메시지 수신 핸들러
 *
 * 1. FCM 토큰 갱신 시 → Firestore /users/{uid}/devices/{device_id} 에 저장
 * 2. "wake_up" 메시지 수신 시 → SmsBridgeService 재시작 (auth + 서비스 활성화 모두 확인)
 */
class SmsBridgeMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCMService"
        private val fcmScope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "FCM 토큰 갱신")

        val uid      = FirebaseAuth.getInstance().currentUser?.uid
        val deviceId = AppSettings.getDeviceId(this)

        if (uid == null || deviceId.isBlank()) {
            Log.w(TAG, "로그인 미완료 또는 기기 ID 미설정 → 토큰 저장 생략")
            return
        }

        fcmScope.launch { saveFcmToken(token, deviceId) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.i(TAG, "FCM 수신: ${message.data}")

        when (message.data["type"]) {
            "wake_up" -> handleWakeUp()
        }
    }

    /**
     * [fix-2] wake_up 처리 전 두 가지 조건 모두 확인:
     *   1. 사용자가 서비스를 켜둔 상태인지 (isServiceEnabled)
     *   2. 현재 로그인 상태인지 (currentUser != null)
     *
     * 로그인 없이 startForegroundService를 호출하면 SmsBridgeService.onStartCommand에서
     * startForeground 없이 stopSelf가 먼저 호출되어 ANR급 크래시 발생.
     * (이 수정으로 Service 내부의 startForeground→stopSelf 방어코드와 이중 보호)
     */
    private fun handleWakeUp() {
        if (!AppSettings.isServiceEnabled(this)) return
        if (FirebaseAuth.getInstance().currentUser == null) {
            Log.w(TAG, "wake_up 수신 but 로그아웃 상태 → 서비스 재시작 생략")
            return
        }
        // [fix-6] SmsBridgeService.start() 헬퍼로 버전 분기 중복 제거
        SmsBridgeService.start(this)
    }

    private suspend fun saveFcmToken(token: String, deviceId: String) {
        try {
            FirestorePaths.devicesCollection()
                .document(deviceId)
                .set(
                    mapOf(
                        "device_id"  to deviceId,
                        "role"       to AppSettings.getRole(this),
                        "fcm_token"  to token,
                        "updated_at" to com.google.firebase.Timestamp.now()
                    ),
                    com.google.firebase.firestore.SetOptions.merge()
                )
                .await()
            Log.i(TAG, "FCM 토큰 저장 완료: $deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "FCM 토큰 저장 실패: ${e.message}", e)
        }
    }
}
