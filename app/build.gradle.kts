plugins {
    id("com.android.application")
}

android {
    namespace = "com.wuyi.kebiao"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wuyi.kebiao"
        // 24 是液态玻璃库的下限（它的 C++/NEON 降级管线要求 API 24）。
        // 21~23（Android 5~6）在这个年代已基本绝迹，提到 24 换取真实的折射玻璃。
        minSdk = 24
        targetSdk = 34
        versionCode = 14
        versionName = "2.1.3-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
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

    // 液态玻璃：底部导航栏要的 iOS 26 质感。
    implementation(libs.liquidglass)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
