package com.workez365.smsbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.auth.FirebaseAuth

/**
 * 기기 부팅 완료 후 SmsBridgeService를 자동으로 재시작하는 Receiver
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        if (!AppSettings.isServiceEnabled(context)) {
            Log.i(TAG, "서비스가 비활성화 상태 → 자동 재시작 생략")
            return
        }

        // [fix-7] 부팅 직후 Firebase Auth가 아직 캐시된 사용자를 복원하지 않았을 수 있음.
        // currentUser == null이면 서비스를 시작하지 않음. 이 경우 SmsBridgeService 내부의
        // startForeground→stopSelf 방어코드가 없어 ForegroundServiceDidNotStartInTimeException
        // 크래시가 발생하므로, Receiver 단에서 먼저 차단하는 것이 안전함.
        //
        // 실제로 Firebase는 부팅 후 수 초 내에 캐시된 auth 상태를 복원하므로,
        // 앱이 포그라운드로 올 때 MainActivity의 AuthStateListener가 서비스를 시작함.
        if (FirebaseAuth.getInstance().currentUser == null) {
            Log.w(TAG, "부팅 후 아직 로그인 상태 미복원 → 서비스 재시작 생략 (앱 열면 자동 시작)")
            return
        }

        Log.i(TAG, "SmsBridgeService 자동 재시작")
        // [fix-6] SmsBridgeService.start() 헬퍼로 버전 분기 중복 제거
        SmsBridgeService.start(context)
    }
}
