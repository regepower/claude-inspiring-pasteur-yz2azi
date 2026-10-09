import java.security.KeyStore
import java.time.LocalDate
import java.time.ZoneId

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.regepower.dualfiles"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.regepower.dualfiles"
        minSdk = 30
        targetSdk = 36
        // Major.minor by hand for bigger changes; the last part is the CI build number (#57 → 1.0.57).
        // versionCode follows it, so every CI build installs as an update. Local builds: 1.0.0.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = maxOf(build, 1)
        versionName = "1.0.$build"
        // Build day for the help dialog (manifest meta-data, no BuildConfig/resource needed).
        manifestPlaceholders["buildDate"] = LocalDate.now(ZoneId.of("Europe/Berlin")).toString()
        // 7z extraction in C (src/main/cpp): only 64-bit ARM, which every current phone has.
        ndk { abiFilters += "arm64-v8a" }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    // Release key from CI secrets. Only KEYSTORE_BASE64 + KEYSTORE_PASSWORD are required: without
    // KEY_ALIAS the first alias in the keystore is used, without KEY_PASSWORD the store password.
    val keystorePath: String? = System.getenv("KEYSTORE_FILE")
    if (keystorePath != null) {
        val storePw = System.getenv("KEYSTORE_PASSWORD").orEmpty()
        val alias =
            System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
                ?: KeyStore.getInstance(KeyStore.getDefaultType()).run {
                    file(keystorePath).inputStream().use { load(it, storePw.toCharArray()) }
                    aliases().nextElement()
                }
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = storePw
                keyAlias = alias
                keyPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: storePw
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without release secrets, sign with the debug key so the APK stays installable.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // Size (measured on AgendaGo): no Kotlin null-check/concat helpers in the dex.
            freeCompilerArgs.addAll("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions", "-Xstring-concat=inline")
        }
    }

    // Size (measured): deflate classes.dex in the APK (AGP stores it uncompressed for minSdk >= 28): -116 KB;
    // drop unused Kotlin builtins/metadata resources: -13 KB.
    packaging {
        dex { useLegacyPackaging = true }
        // Compress the 7z library in the APK as well (unpacked once at install)
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf("kotlin/**", "kotlin-tooling-metadata.json", "META-INF/*.version")
        }
    }

    lint {
        abortOnError = true
        textReport = true
        warningsAsErrors = false
    }
}

// No dependencies: framework widgets only (RecyclerView removed, ~110 KB). 7z: LZMA SDK sources in src/main/cpp.
