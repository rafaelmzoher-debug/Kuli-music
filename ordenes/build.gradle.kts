plugins { id("com.android.application") }
android {
    namespace = "com.kuli.ordenes"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kuli.ordenes"
        minSdk = 26
        targetSdk = 28
        versionCode = 8
        versionName = "1.7"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.hierynomus:smbj:0.13.0")
}
