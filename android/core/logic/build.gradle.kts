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

/** The shared tag-expression conformance vectors (repo root; read in place, never copied). */
val tagExprVectors: File = rootProject.file("../spec/tag_expression_vectors.json")

/** The shared score vectors (spec/score_vectors.json; read in place, never copied). */
val scoreVectors: File = rootProject.file("../spec/score_vectors.json")

tasks.test {
    useJUnitPlatform()
    inputs.file(tagExprVectors)
    systemProperty("tagExprVectors", tagExprVectors.path)
    inputs.file(scoreVectors)
    systemProperty("scoreVectors", scoreVectors.path)
}
