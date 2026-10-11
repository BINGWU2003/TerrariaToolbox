import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val publicVersion = "1.0.0"
val localSigning = Properties().apply {
    val location = providers.environmentVariable("TERRARIA_SIGNING_PROPERTIES").orNull
        ?: "${System.getProperty("user.home")}/.android/terraria-toolbox/signing.properties"
    val propertiesFile = file(location)
    if (propertiesFile.isFile) propertiesFile.inputStream().use { load(it) }
}
fun signingValue(environment: String, property: String): String? =
    providers.environmentVariable(environment).orNull?.takeIf { it.isNotBlank() }
        ?: localSigning.getProperty(property)?.takeIf { it.isNotBlank() }
val releaseStore = signingValue("RELEASE_KEYSTORE_PATH", "storeFile")
val releaseStorePassword = signingValue("RELEASE_STORE_PASSWORD", "storePassword")
val releaseAlias = signingValue("RELEASE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("RELEASE_KEY_PASSWORD", "keyPassword")
val releaseSigningConfigured = listOf(releaseStore, releaseStorePassword, releaseAlias, releaseKeyPassword).all { it != null }

android {
    namespace = "io.github.bingwu2003.terrariatoolbox"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.bingwu2003.terrariatoolbox"
        minSdk = 34
        targetSdk = 37
        versionCode = 8
        versionName = publicVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Opt-in tests require dedicated fixtures or a real v1.0 upgrade.
        // Ordinary device runs must never operate on a user's game configuration.
        testInstrumentationRunnerArguments["notAnnotation"] = "io.github.bingwu2003.terrariatoolbox.DedicatedFixture"
    }

    signingConfigs {
        if (releaseSigningConfigured) create("official") {
            storeFile = file(requireNotNull(releaseStore))
            storePassword = releaseStorePassword
            keyAlias = releaseAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("official")
            optimization {
                enable = true
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        aidl = true
    }
    sourceSets.getByName("main").assets.srcDir("../Masters Of Terraria")
    // Package the supplied saves without bundling the path documentation.
    androidResources.ignoreAssetsPattern = "path.md:!.svn:!.git:!CVS:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~"
}

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    val configured = releaseSigningConfigured
    doLast {
        check(configured) {
            "Release requires a permanent signing key. Configure signing.properties or RELEASE_* environment variables; see README.md."
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(validateReleaseSigning) }
tasks.register<Sync>("prepareRelease") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*.apk") }
    into(layout.buildDirectory.dir("outputs/distribution"))
    rename(".*\\.apk", "TerrariaToolbox-v$publicVersion.apk")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.espresso.intents)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
