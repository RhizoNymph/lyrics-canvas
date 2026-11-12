plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

// Offline copy and sync (docs/features/android_offline_sync.md): the local store, the outbox, the
// sync engine and the local-first repositories. Pure Kotlin/JVM; only ApiClient touches the network.
dependencies {
    api(project(":core:data"))
    implementation(project(":core:logic"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }
