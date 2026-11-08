plugins { id("com.android.application") }
android {
    namespace = "com.kuli.fotos"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kuli.fotos"
        minSdk = 26
        targetSdk = 28
        versionCode = 4
        versionName = "1.3"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation("com.hierynomus:smbj:0.13.0") }
