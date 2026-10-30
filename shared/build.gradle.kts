import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    applyDefaultHierarchyTemplate()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // listOf(
    //     iosArm64(),
    //     iosSimulatorArm64()
    // ).forEach { iosTarget ->
    //     iosTarget.binaries.framework {
    //         baseName = "Shared"
    //         isStatic = true
    //     }
    // }
    //
    jvm()
    //
    // js {
    //     browser()
    // }
    //
    // @OptIn(ExperimentalWasmDsl::class)
    // wasmJs {
    //     browser()
    // }
    
    android {
       namespace = "com.storyteller_f.dush.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
    }
    
    sourceSets {
        androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
        jvmMain.dependencies { implementation(libs.ktor.client.okhttp) }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kermit)
            implementation(libs.koog.openai)
            implementation(libs.koog.openrouter)
            implementation(libs.koog.http.ktor)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.room.paging)
            api(libs.androidx.paging.common)
            implementation(libs.androidx.paging.compose)
        }
        jvmTest {
            resources.srcDir("schemas")
            dependencies {
                implementation(libs.androidx.sqlite.bundled)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlin.testJunit)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        // jsMain.dependencies {
        //     implementation(libs.wrappers.browser)
        // }
    }
}

room {
    // This Android KMP library has no build types. All apps use the same database
    // definition and migration history; applicationId isolates their runtime data.
    // See DEVELOPMENT.md and https://github.com/storytellerF/dush/issues/18.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspJvm", libs.androidx.room.compiler)
}
