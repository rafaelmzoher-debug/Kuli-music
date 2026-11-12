plugins { id("com.android.application") }
android {
    namespace = "com.kuli.ordenes"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kuli.ordenes"
        minSdk = 26
        targetSdk = 28
        versionCode = 21
        versionName = "2.10"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    // Una sola versión de Kotlin para todas las librerías (evita "Duplicate class kotlin...").
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.8.22"))
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("androidx.activity:activity:1.9.3")
}
