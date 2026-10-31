plugins { id("com.android.application") version "8.7.3" }
android {
    namespace = "com.kuli.music"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kuli.music"
        minSdk = 26
        targetSdk = 28
        versionCode = 4
        versionName = "2.2"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation("com.hierynomus:smbj:0.13.0") }
