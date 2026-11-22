plugins { id("com.android.application") }
android {
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file(".kuli/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    namespace = "com.kuli.arcade"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kuli.arcade"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    productFlavors {
        create("tv") {
            minSdk = 21
            targetSdk = 35
            // Versión TV para juegos arcade
        }

        create("phone") {
            minSdk = 26
            targetSdk = 35
            // Versión phone original
        }
    }
}
