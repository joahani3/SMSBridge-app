plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // google-services 플러그인: google-services.json 을 앱에 적용
    id("com.google.gms.google-services")
}

android {
    namespace = "com.workez365.smsbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.workez365.smsbridge"
        minSdk = 26          // Android 8.0 이상 (NotificationChannel 필수 API)
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // -------------------------------------------------------
    // Firebase BOM: 버전을 한 곳에서 관리 (개별 라이브러리 버전 생략 가능)
    // -------------------------------------------------------
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))

    // Firestore: SMS 수신 데이터 저장 & 발송 명령 실시간 수신
    implementation("com.google.firebase:firebase-firestore-ktx")

    // FCM: Master → Slave 발송 명령 Push (앱이 꺼져도 깨울 수 있음)
    implementation("com.google.firebase:firebase-messaging-ktx")

    // Auth: 이메일/비밀번호 로그인 → 유저별 데이터 격리의 핵심
    implementation("com.google.firebase:firebase-auth-ktx")

    // Firebase Analytics (선택적, 사용 안 하면 제거 가능)
    // implementation("com.google.firebase:firebase-analytics-ktx")

    // 코루틴: 비동기 처리용
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1") // Firebase Task → 코루틴 변환
}
