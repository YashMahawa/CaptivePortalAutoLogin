import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    application
    alias(libs.plugins.buildlogic.kotlin.jvm)
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(projects.portalLogin)
    implementation(projects.api.client)
    implementation(projects.liberator)
    implementation(projects.util.logger)
    implementation(libs.clikt)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    implementation(libs.okhttp)
}

val mainClass = "de.binarynoise.captiveportalautologin.MainKt"
application.mainClass = mainClass
tasks.withType<Jar> {
    manifest {
        attributes(mapOf("Main-Class" to mainClass))
    }
}

tasks.withType<ShadowJar> {
    archiveClassifier.set("shadow")
    mergeServiceFiles()
    minimize {
        exclude(dependency("com.github.ajalt.mordant:mordant-jvm-jna"))
    }
    exclude("**/*.kotlin_*")
}

tasks.withType<Test> { useJUnitPlatform() }
