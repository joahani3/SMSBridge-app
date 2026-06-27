package com.workez365.smsbridge

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * [fix-3] Play Store 기본 SMS 앱 후보 필수 컴포넌트: 빠른 답장 처리
 *
 * 시스템이 "문자로 답장" 기능을 이 Activity로 라우팅함.
 * 기본 SMS 앱 등록 요건 충족을 위해 선언됨.
 * 향후 SMS 작성 UI를 구현하면 이 Activity에서 처리.
 */
class RespondViaSmsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 현재는 즉시 종료 (향후 SMS 작성 화면으로 대체)
        finish()
    }
}
