package com.workez365.smsbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * SMS 수신 감지 BroadcastReceiver
 *
 * SMS 도착 → 발신번호 + 본문 추출 → Firestore /users/{uid}/sms_inbox 에 저장
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"

        // [fix-5] 매 onReceive마다 새 스코프를 만들면 참조가 남아 OOM/누수 발생.
        // companion object의 단일 스코프를 재사용: SupervisorJob으로 개별 실패가
        // 다른 코루틴을 취소하지 않도록 격리.
        private val receiverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // goAsync 타임아웃: 시스템 제한(약 10초)보다 여유 있게 설정
        private const val UPLOAD_TIMEOUT_MS = 8_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION &&
            action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        // [fix-9] isSlaveMode() 헬퍼 사용
        if (!AppSettings.isSlaveMode(context)) return

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Log.w(TAG, "로그인 상태가 아님 → SMS 업로드 생략")
            return
        }

        val messages: Array<SmsMessage> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } else {
            @Suppress("DEPRECATION")
            val pdus = intent.extras?.get("pdus") as? Array<*> ?: return
            pdus.mapNotNull { SmsMessage.createFromPdu(it as ByteArray) }.toTypedArray()
        }

        if (messages.isEmpty()) return

        val sender    = messages[0].displayOriginatingAddress ?: "Unknown"
        val body      = messages.joinToString("") { it.messageBody ?: "" }
        val timestamp = messages[0].timestampMillis

        Log.d(TAG, "SMS 수신: from=$sender")

        val pendingResult = goAsync()

        // [fix-5] 재사용 스코프로 launch — 스코프 누수 없음
        receiverScope.launch {
            try {
                // [fix-5] 타임아웃으로 goAsync 데드라인 초과 방지
                withTimeout(UPLOAD_TIMEOUT_MS) {
                    uploadToFirestore(sender, body, timestamp, context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Firestore 업로드 실패: ${e.message}", e)
            } finally {
                // 타임아웃이나 예외 후에도 반드시 finish() 호출
                pendingResult.finish()
            }
        }
    }

    private suspend fun uploadToFirestore(
        sender: String,
        body: String,
        timestamp: Long,
        context: Context
    ) {
        // [fix-8] isConfigured() 헬퍼 사용
        if (!AppSettings.isConfigured(context)) {
            Log.w(TAG, "기기 ID 미설정 → Firestore 저장 생략")
            return
        }

        val data = hashMapOf(
            "device_id"   to AppSettings.getDeviceId(context),
            "sender"      to sender,
            "body"        to body,
            "received_at" to com.google.firebase.Timestamp(
                timestamp / 1000,
                ((timestamp % 1000) * 1_000_000).toInt()
            ),
            "read" to false
        )

        val docRef = FirestorePaths.inboxCollection().add(data).await()
        Log.i(TAG, "Firestore 저장 완료: ${docRef.id}, from=$sender")
    }
}
