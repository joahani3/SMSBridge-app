package com.workez365.smsbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * [fix-3] Play Store 기본 SMS 앱 후보 필수 컴포넌트: WAP/MMS 수신 처리
 *
 * 이 앱은 MMS 표시 기능을 구현하지 않으므로 수신만 감지하고 로그만 남김.
 * 기본 SMS 앱 등록 요건을 충족하기 위해 컴포넌트로 선언됨.
 */
class MmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // MMS 기능 미구현: 수신 감지만 하고 무시
        // 향후 MMS 중계 기능 추가 시 여기에 처리 로직 구현
        Log.d(TAG, "MMS 수신 (처리 미구현): action=${intent.action}")
    }
}
