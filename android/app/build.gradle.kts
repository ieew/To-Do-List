plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.todo.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.todo.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 统一签名：debug 与 release 共用同一个 keystore，保证两者能互相覆盖安装。
        // 路径优先级：DSH_KEYSTORE 环境变量 > local.properties 的 dsh.keystore > 手机默认路径。
        // 换机器构建（如服务器）时用环境变量或 local.properties 指到本地那份副本，
        // 绝不能让 Gradle 自动生成 debug.keystore —— 签名不同会装不上去。
        create("dsh") {
            val fallback = "/storage/emulated/0/MT2/keystore/qianxue.jks"
            val fromProps = rootProject
                .file("local.properties")
                .takeIf { it.exists() }
                ?.let { file ->
                    java.util.Properties().apply { file.inputStream().use { load(it) } }
                        .getProperty("dsh.keystore")
                }
            storeFile = rootProject.file(System.getenv("DSH_KEYSTORE") ?: fromProps ?: fallback)
            storePassword = System.getenv("DSH_KEYSTORE_PASS") ?: "Fw34r@3468"
            keyAlias = System.getenv("DSH_KEY_ALIAS") ?: "qianxue"
            keyPassword = System.getenv("DSH_KEY_PASS") ?: "Fw34r@3468"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dsh")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dsh")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":core:sync"))
    implementation(project(":core:dsh"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ai"))
    implementation(project(":core:reminder"))
    implementation(project(":feature:ai"))
    implementation(project(":feature:widget"))
    implementation(project(":feature:ime"))
    implementation(project(":feature:tasks"))
    implementation(project(":feature:detail"))
    implementation(project(":feature:calendar"))
    implementation(project(":feature:stats"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:members"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
