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
    namespace = "com.kuli.music"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kuli.music"
        minSdk = 26
        targetSdk = 35
        versionCode = 343
        versionName = "3.43"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

    productFlavors {
        create("tv") {
            minSdk = 21
            targetSdk = 35
            // Versión optimizada para Android TV/Google TV
        }

        create("phone") {
            minSdk = 26
            targetSdk = 35
            // Versión para dispositivos móviles normales
        }
    }
}
dependencies { implementation("com.hierynomus:smbj:0.13.0") }
