import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.screenshot)
    alias(libs.plugins.easylauncher)
}

val signPath: String? = System.getenv("storyteller_f_sign_path")
val signKey: String? = System.getenv("storyteller_f_sign_key")
val signAlias: String? = System.getenv("storyteller_f_sign_alias")
val signStorePassword: String? = System.getenv("storyteller_f_sign_store_password")
val signKeyPassword: String? = System.getenv("storyteller_f_sign_key_password")

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(projects.shared)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.viewmodelCompose)
    implementation(libs.androidx.lifecycle.viewmodelNavigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtimeKtx)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.kermit)
    implementation(libs.koog.agents)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.compose.uiTooling)
}

android {
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
    namespace = "com.storyteller_f.dush"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.storyteller_f.dush"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        val signStorePath = when {
            signPath != null -> File(signPath)
            signKey != null -> File(System.getProperty("user.home"), "signing_key.jks")
            else -> null
        }
        if (signStorePath != null && signAlias != null && signStorePassword != null && signKeyPassword != null) {
            create("release") {
                keyAlias = signAlias
                keyPassword = signKeyPassword
                storeFile = signStorePath
                storePassword = signStorePassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            val releaseSignConfig = signingConfigs.findByName("release")
            if (releaseSignConfig != null)
                signingConfig = releaseSignConfig
        }
        create("daily") {
            initWith(getByName("release"))
            applicationIdSuffix = ".daily"
            versionNameSuffix = "-daily"
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

easylauncher {
    iconNames.addAll("@mipmap/ic_launcher", "@mipmap/ic_launcher_round")
    buildTypes {
        register("daily") {
            filters(customRibbon(label = "DAILY", ribbonColor = "#FF6D00"))
        }
    }
}
