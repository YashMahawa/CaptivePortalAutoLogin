plugins { alias(libs.plugins.buildlogic.kotlin.jvm) }
dependencies {
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    compileOnly("org.jspecify:jspecify:1.0.0")
    testImplementation("com.squareup.okhttp3:mockwebserver3:${libs.versions.okhttp.get()}")
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
tasks.withType<Test> { useJUnitPlatform() }
