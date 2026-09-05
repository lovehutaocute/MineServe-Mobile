plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

composeCompiler {
    if (providers.gradleProperty("composeCompilerReports").orNull == "true") {
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
    }
}

// 正式包名（影响 APK 输出文件名）
base {
    archivesName.set("MineServeMobile")
}

android {
    namespace = "com.mineserve.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mineserve.mobile"
        minSdk = 26
        targetSdk = 28
        versionCode = 40
        versionName = "1.2.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // ABI 分包：仅打包 arm64-v8a（绝大多数手机），x86_64 模拟器不再产出
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    // 使用 debug keystore 签名 release 包，便于直接安装
    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // 正式包始终启用 R8 混淆与资源压缩。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.VERSION_NAME / VERSION_CODE（软件更新用）
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES}" }
    }
    lint {
        abortOnError = false
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.xz)
    implementation(libs.commons.compress)
    implementation(libs.zstd.jni)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.apache.ftpserver)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)

    debugImplementation(libs.androidx.ui.tooling)
}
