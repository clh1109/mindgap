plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.mindgap.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mindgap.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0"

        // sherpa-onnx AAR 含四种 ABI，只保留真机与模拟器常用的两种
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // M2 端侧感知：离线 ASR（Paraformer int8 + Silero VAD）与中文 OCR
    // 官方预编译 AAR（含 JNI 与 classes.jar）；@aar + 关闭传递依赖，避免拉入桌面原生库
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:2.0.1@aar") {
        isTransitive = false
    }
    // 中文 OCR 内置模型，不依赖 Google Play 服务
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // 解压 .tar.bz2 模型包
    implementation("org.apache.commons:commons-compress:1.26.2")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 纯 JVM 单测：反事实改写、证据校验、题目生成，不需要真机
    testImplementation("junit:junit:4.13.2")
}
