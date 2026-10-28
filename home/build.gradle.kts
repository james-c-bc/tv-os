plugins {
    id("com.android.application") version "8.11.0"
    id("org.jetbrains.kotlin.android") version "2.1.20"
}

android {
    namespace = "io.github.james_c_bc.home"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.james_c_bc.home"
        minSdk = 30
        targetSdk = 30
        versionCode = 1
        versionName = "1.0"
    }

    // bin/home passes the signing key in through the environment. The key
    // lives outside git, in ~/.config/tcl-os/.
    val keystore = System.getenv("HOME_APP_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("HOME_APP_KEYSTORE_PASSWORD")
                keyAlias = "home"
                keyPassword = System.getenv("HOME_APP_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // config/apps.txt, the owner's app order, is built in as an asset.
    sourceSets {
        getByName("main") {
            assets.srcDir("config")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // The app is only ever sideloaded, never published, so targeting
        // Android 11 on purpose isn't a problem.
        disable += "ExpiredTargetSdkVersion"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
