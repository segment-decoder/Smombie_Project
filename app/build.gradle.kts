plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "kr.ac.yu.smombieproject"
    compileSdk = 36 // 버전 함수 래핑 제거, 직관적으로 변경

    // AGP 9.2.0 환경에서는 NDK 버전을 명시하지 않아도 최적의 버전을 자동으로 선택합니다.
    // ndkVersion = "21.4.7075529"

    defaultConfig {
        applicationId = "kr.ac.yu.smombieproject"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                // [요소기술 1, 2] 연산을 위한 C++17 표준 적용
                cppFlags += "-std=c++17"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    buildFeatures {
        viewBinding = true
    }
    packagingOptions {
        jniLibs {
            useLegacyPackaging = true // 네이티브 라이브러리 로드 방식 안정화
        }
    }
}

dependencies {
    val camerax_version = "1.6.0"

    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-view:${camerax_version}")

    implementation(project(":opencv")) // OpenCV 모듈 정상 연결
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}