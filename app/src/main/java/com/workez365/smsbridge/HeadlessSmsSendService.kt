package com.workez365.smsbridge

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * [fix-3] Play Store 기본 SMS 앱 후보 필수 컴포넌트 (Android 11+)
 *
 * 기본 SMS 앱이 아닌 상태에서 시스템이 SMS 발송을 요청할 때 호출됨.
 * 향후 실제 발송 로직 구현 가능.
 */
class HeadlessSmsSendService : Service() {

    companion object {
        private const val TAG = "HeadlessSms"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "HeadlessSmsSendService 호출 (현재 미구현)")
        stopSelf()
        return START_NOT_STICKY
    }
}
