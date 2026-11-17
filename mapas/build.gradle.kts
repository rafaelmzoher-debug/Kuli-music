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
    namespace = "com.kuli.mapas"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kuli.mapas"
        minSdk = 26
        targetSdk = 33
        versionCode = 5
        versionName = "1.4"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")
}
