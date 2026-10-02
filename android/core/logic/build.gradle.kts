plugins {
    alias(libs.plugins.kotlin.jvm)

}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core:model"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }
