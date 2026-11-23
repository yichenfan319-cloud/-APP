import java.util.Properties

plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.wuyi.kebiao"
    // 37 是 backdrop（Compose 液态玻璃）的 aar-metadata 下限，
    // 34 会在 checkDebugAarMetadata 阶段直接被拒。
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wuyi.kebiao"
        // 24 是液态玻璃库的下限（它的 C++/NEON 降级管线要求 API 24）。
        // 21~23（Android 5~6）在这个年代已基本绝迹，提到 24 换取真实的折射玻璃。
        minSdk = 24
        targetSdk = 34
        versionCode = 18
        versionName = "2.1.7-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 发布签名：密码放在工程根的 keystore.properties（已在 .gitignore 里）。
    // 用 Reader + UTF-8 读，因为 Properties.load(InputStream) 按 ISO-8859-1 解，
    // 路径里一有中文就会乱码。
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) {
            keystorePropsFile.reader(Charsets.UTF_8).use { load(it) }
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
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
            // 没有 keystore.properties 时（比如别人 clone 下来）照样能编译，
            // 只是出 unsigned 包，不会因为签名缺失导致构建失败。
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        // AGP 9 已经移除 android.kotlinOptions，只能在这里设
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 依赖统一由 gradle/libs.versions.toml 管理，不再在此手写版本号。
    //
    // 清理前的状况（留作记录）：
    //   appcompat 声明了 4 次、material 声明了 2 次（1.11.0 与 1.9.0 各一次），
    //   实际生效的是版本较高的那个，与 libs.versions.toml 里写的值并不一致。
    //   navigation 同时引了 -ktx:2.6.0 与普通版:2.7.7，前者是 Kotlin 扩展包，
    //   对本 Java 工程无用，只会多拖进 Kotlin stdlib。

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)

    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)

    implementation(libs.okhttp)
    implementation(libs.gson)
    // jsoup：教务系统返回的课表是一个表格片段，用 DOM 解析比正则可靠得多，
    // 而且是纯 Java 实现，可以直接在 JVM 单元测试里跑，不用开模拟器。
    implementation(libs.jsoup)

    // 液态玻璃底栏：Compose 实现，玻璃珠用 backdrop 的 lens() 做真折射。
    // 只引 ui + foundation，不引 material3 —— 导航栏是自己画的，
    // 少一个依赖就少一份体积和一份开源许可要交代。
    implementation(libs.backdrop)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
