plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "kr.ac.yu.smombieproject"
    compileSdk = 36 // 버전 함수 래핑 제거, 직관적으로 변경

    // ★ [핵심 방어 로직] 최신 NDK의 libcpufeatures 삭제 버그를 피하기 위해 검증된 NDK 21버전 강제 고정
    ndkVersion = "21.4.7075529"

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
            version = "3.22.1"
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
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}