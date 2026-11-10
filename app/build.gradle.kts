plugins { id("com.android.application") }
android {
    namespace = "com.kuli.music"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kuli.music"
        minSdk = 26
        targetSdk = 28
        versionCode = 32
        versionName = "3.20"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation("com.hierynomus:smbj:0.13.0") }
