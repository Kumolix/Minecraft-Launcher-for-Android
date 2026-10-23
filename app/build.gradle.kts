plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.mclauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.mclauncher"
        minSdk = 26
        targetSdk = 28
        versionCode = 14
        versionName = rootProject.file("VERSION").readText().trim()
    }

    signingConfigs {
        create("fixed") {
            storeFile = file("simplemc.keystore")
            storePassword = "simplemc123"
            keyAlias = "simplemc"
            keyPassword = "simplemc123"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // распаковка .tar.xz для Java-рантайма
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
}
