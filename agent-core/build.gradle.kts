plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    implementation(libs.jsoup)
    // Interpreted JavaScript for the on-phone code sandbox (no JIT, works on Android).
    implementation(libs.rhino)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    // Point -Dpocketagent.nativeLib and -Dpocketagent.testModel at a host build of
    // libagentnative and a .gguf file to run the JNI end-to-end test.
    systemProperty("pocketagent.nativeLib", System.getProperty("pocketagent.nativeLib") ?: "")
    systemProperty("pocketagent.testModel", System.getProperty("pocketagent.testModel") ?: "")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
