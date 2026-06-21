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
    dependsOn(":androidApp:assembleDebug")
    systemProperty("project.root.dir", rootProject.projectDir.absolutePath)
    systemProperty("app.path", rootProject.layout.projectDirectory.file("androidApp/build/outputs/apk/debug/androidApp-debug.apk").asFile.absolutePath)
}
