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
    namespace = "com.kuli.fotos"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kuli.fotos"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.5"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/INDEX.LIST",
                "META-INF/versions/**",
                "**/module-info.class"
            )
        }
    }
}
dependencies {
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("com.google.mlkit:image-labeling:17.0.9")
}
