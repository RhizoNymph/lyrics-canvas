plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/** The shared score vectors (repo root; read in place, never copied): the wire model must read them. */
val scoreVectors: File = rootProject.file("../spec/score_vectors.json")

tasks.test {
    useJUnitPlatform()
    inputs.file(scoreVectors)
    systemProperty("scoreVectors", scoreVectors.path)
}
