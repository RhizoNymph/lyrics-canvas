plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release metadata. CI passes -PversionName (from the tag) and -PversionCode (monotonic run
// number); local builds fall back to the development values.
val releaseVersionName: String =
    (project.findProperty("versionName") as String?)?.takeIf { it.isNotBlank() } ?: "0.1.0"
val releaseVersionCode: Int =
    (project.findProperty("versionCode") as String?)?.takeIf { it.isNotBlank() }?.let {
        it.toIntOrNull() ?: throw GradleException("-PversionCode must be a positive integer, got '$it'")
    } ?: 1

/** How the release build type is signed. Decided once from the environment, never half-configured. */
sealed interface ReleaseSigning {
    /** Keystore supplied through ANDROID_KEYSTORE_FILE / _PASSWORD, ANDROID_KEY_ALIAS / _PASSWORD. */
    data class Keystore(val file: File, val storePassword: String, val keyAlias: String, val keyPassword: String) : ReleaseSigning
    /** No keystore in the environment: sign with the debug key so the APK is still installable. */
    data object DebugKey : ReleaseSigning
}

fun releaseSigningFromEnv(): ReleaseSigning {
    val names = listOf("ANDROID_KEYSTORE_FILE", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")
    val values = names.associateWith { System.getenv(it)?.takeIf(String::isNotBlank) }
    val missing = values.filterValues { it == null }.keys
    if (missing.size == names.size) return ReleaseSigning.DebugKey
    if (missing.isNotEmpty()) {
        throw GradleException("release signing is partially configured; missing ${missing.joinToString()}")
    }
    val file = file(values.getValue("ANDROID_KEYSTORE_FILE")!!)
    if (!file.isFile) throw GradleException("ANDROID_KEYSTORE_FILE does not exist: $file")
    return ReleaseSigning.Keystore(
        file = file,
        storePassword = values.getValue("ANDROID_KEYSTORE_PASSWORD")!!,
        keyAlias = values.getValue("ANDROID_KEY_ALIAS")!!,
        keyPassword = values.getValue("ANDROID_KEY_PASSWORD")!!,
    )
}

val releaseSigning: ReleaseSigning = releaseSigningFromEnv()

android {
    namespace = "dev.nymph.lyrics.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.nymph.lyrics"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        // Backend base URL. The emulator reaches the host's localhost at 10.0.2.2;
        // override per build with -PapiBaseUrl=http://<lan-ip>:8000 for a phone.
        val apiBaseUrl = (project.findProperty("apiBaseUrl") as String?) ?: "http://10.0.2.2:8000"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        // Cluster alerts (GET /_status/alerts) exist only on the frontend nginx origin. Blank: derive
        // <apiBaseUrl>/_status/alerts (right when apiBaseUrl is that origin; a backend-direct URL
        // answers 404 and the app stays quiet). Set -PstatusAlertsUrl=http://<nginx>/_status/alerts
        // for a backend-direct apiBaseUrl, or "none" to turn alerts off.
        val statusAlertsUrl = ((project.findProperty("statusAlertsUrl") as String?) ?: "").trim()
        if (statusAlertsUrl.isNotEmpty() && statusAlertsUrl != "none" && !Regex("^https?://[^\\s\"]+$").matches(statusAlertsUrl)) {
            throw GradleException("-PstatusAlertsUrl must be blank, 'none' or an http(s) URL, got '$statusAlertsUrl'")
        }
        buildConfigField("String", "STATUS_ALERTS_URL", "\"$statusAlertsUrl\"")
    }

    signingConfigs {
        if (releaseSigning is ReleaseSigning.Keystore) {
            create("release") {
                storeFile = releaseSigning.file
                storePassword = releaseSigning.storePassword
                keyAlias = releaseSigning.keyAlias
                keyPassword = releaseSigning.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = when (releaseSigning) {
                is ReleaseSigning.Keystore -> signingConfigs.getByName("release")
                ReleaseSigning.DebugKey -> signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions { unitTests.all { it.useJUnitPlatform() } }
}

kotlin { jvmToolchain(21) }

gradle.taskGraph.whenReady {
    if (allTasks.any { it.path == ":app:assembleRelease" || it.path == ":app:packageRelease" }) {
        val signer = when (val s = releaseSigning) {
            is ReleaseSigning.Keystore -> "keystore ${s.file.name} (alias ${s.keyAlias})"
            ReleaseSigning.DebugKey -> "debug key (no ANDROID_KEYSTORE_* in the environment)"
        }
        logger.lifecycle("release APK: versionName=$releaseVersionName versionCode=$releaseVersionCode signing=$signer")
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:logic"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.text)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
