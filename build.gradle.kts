// 프로젝트 레벨 build.gradle.kts
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    // google-services 플러그인 등록 (앱 모듈에서 apply)
    id("com.google.gms.google-services") version "4.4.2" apply false
}
