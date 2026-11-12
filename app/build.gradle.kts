plugins {
    id("com.android.application")
}

android {
    namespace = "com.maximiliankraft.mobileagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.maximiliankraft.mobileagent"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    dependencies {
        testImplementation("junit:junit:4.13.2")
    }
}
