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
    useJUnitPlatform {
        (project.findProperty("appiumTags") as String?)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { includeTags(*it.toTypedArray()) }
    }
    maxParallelForks = 1
    dependsOn(":androidApp:installDebug")
    systemProperty("project.root.dir", rootProject.projectDir.absolutePath)
}
