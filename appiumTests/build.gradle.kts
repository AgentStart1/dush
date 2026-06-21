plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.appium.java.client)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    dependsOn(":androidApp:installDebug")
    systemProperty("project.root.dir", rootProject.projectDir.absolutePath)
}
