plugins {
    alias(libs.plugins.android.application)
}

// Public TS7 baseline packages never carry accessory identity, including external inputs.
val localAuthenticationAssets: File? = null
check(providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR").orNull == null) {
    "TS7 public baseline forbids bundled authentication assets; provision an authorized provider privately."
}

android {
    namespace = "com.shilapi.xcertplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.shihab.diplay"
        minSdk = 19
        targetSdk = 37
        multiDexEnabled = true
        versionCode = 26
        versionName = "0.2.7"
        testInstrumentationRunner = "com.shilapi.xcertplay.baseline.BaselineInstrumentation"

    }


    localAuthenticationAssets?.let { sourceSets.getByName("main").assets.srcDir(it) }

    signingConfigs {
        create("release") {
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .getOrElse("missing-release-keystore.jks"),
            )
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".hudtest"
            versionNameSuffix = "-hud-test"
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
        create("baseline") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".ts7"
            versionNameSuffix = "-ts7-baseline-r1"
            matchingFallbacks += "debug"
        }
    }
    androidResources { ignoreAssetsPattern = "byd-hud-icons" }
    testBuildType = "baseline"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation("androidx.multidex:multidex:2.0.1")
}

// No credential input is allowed in any public TS7 variant.
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
})
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in APK assets."
    val filesToCheck = credentialAssets
    val allowed = localAuthenticationAssets?.let { dir ->
        listOf("identity.pk8", "certificate.p7b").map { dir.resolve("offline-mfi/$it").canonicalFile }.toSet()
    } ?: emptySet()
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile }) { "Explicit local authentication assets are incomplete" }
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets" }
    }
}
tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

// Keep the historical task name, but explicitly disable credential-bundled TS7 builds.
val verifyStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Reject historical credential-bundled standalone packages."
    doLast {
        error("Credential-bundled builds are disabled. Use assembleBaseline and legally authorized external provisioning.")
    }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneAuthentication) }
tasks.register("assembleStandaloneDebug") {
    group = "build"
    description = "Disabled historical packaging entry; use assembleBaseline."
    dependsOn(verifyStandaloneAuthentication, "assembleDebug")
}
