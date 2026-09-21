plugins { id("com.android.application") }

android {
    namespace = "dev.mbaiforinstinct.rebornlauncher"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.mbaiforinstinct.rebornlauncher"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    signingConfigs {
        getByName("debug") {
            // One stable project debug key so every build installs over the last.
            // Generated for this repo only; not a personal key.
            storeFile = file("reborn-debug.keystore")
            storePassword = "android"
            keyAlias = "reborn"
            keyPassword = "android"
        }
    }
}
