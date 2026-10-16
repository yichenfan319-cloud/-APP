plugins {
    id("com.android.application")
}

android {
    namespace = "com.wuyi.kebiao"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wuyi.kebiao"
        minSdk = 21
        targetSdk = 34
        versionCode = 11
        versionName = "2.0.0-beta"

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

    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)

    implementation(libs.okhttp)
    implementation(libs.gson)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
