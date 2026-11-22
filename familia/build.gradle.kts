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
    namespace = "com.kuli.familia"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kuli.familia"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

    productFlavors {
        create("tv") {
            minSdk = 21
            targetSdk = 35
            // Versión TV para familiar/familia
        }

        create("phone") {
            minSdk = 26
            targetSdk = 35
            // Versión phone original
        }
    }
}
dependencies { implementation("com.hierynomus:smbj:0.13.0") }
