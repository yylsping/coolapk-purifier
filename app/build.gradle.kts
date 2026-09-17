import java.util.Properties

plugins {
    id("com.android.application")
}

val signingPropertiesFile = rootProject.file("signing-private/signing.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) {
        signingPropertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "io.github.yylsping.coolapkpurifier"
    // libxposed service 102 publishes minCompileSdk 37. Runtime behavior stays
    // pinned by targetSdk/minSdk below; this only exposes its compile symbols.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.yylsping.coolapkpurifier"
        minSdk = 28
        targetSdk = 35
        versionCode = 15
        versionName = "2.4.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (signingPropertiesFile.isFile) {
                storeFile = rootProject.file(requireNotNull(signingProperties.getProperty("storeFile")))
                storePassword = requireNotNull(signingProperties.getProperty("storePassword"))
                keyAlias = requireNotNull(signingProperties.getProperty("keyAlias"))
                keyPassword = requireNotNull(signingProperties.getProperty("keyPassword"))
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        create("compatible") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = if (signingPropertiesFile.isFile) {
                signingConfigs.getByName("release")
            } else {
                null
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        // Unit tests read the real bundled target manifest for the 16.6.1
        // contract regression instead of duplicating its values.
        getByName("test") {
            resources.srcDir("src/main/assets")
        }
    }

    compileOptions {
        // Required for AGP to emit/merge the global record synthetics used by
        // the official libxposed service 102 AAR on minSdk 28.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    testImplementation("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:1.5.0")
    implementation("org.luckypray:dexkit:2.0.6")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:1.5.0")
    // Real org.json for unit tests: the android.jar stub returns defaults
    // (null) from JSONObject methods, which would silently break every
    // cache serialization test.
    testImplementation("org.json:json:20240303")
}
