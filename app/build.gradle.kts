plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.juya.trend"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.juya.trend"
        minSdk = 26
        targetSdk = 36
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "1.0"
        // 개인용 앱: 구글 AI 키를 GitHub 비밀값(GEMINI_API_KEY)에서 넣음. 비어 있으면 앱 설정에서 직접 입력
        buildConfigField("String", "GEMINI_KEY", "\"" + (System.getenv("GEMINI_API_KEY") ?: "") + "\"")
    }

    // 테스트 APK가 매번 같은 서명 → 위에 덮어서 업데이트됨
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("html") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
}
