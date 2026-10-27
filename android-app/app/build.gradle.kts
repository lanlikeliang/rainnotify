import java.util.Properties
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "cn.rainnotify.app"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "cn.rainnotify.app"
        minSdk = 36
        targetSdk = 36
        versionCode = providers.gradleProperty("releaseVersionCode").orElse("23").get().toInt()
        versionName = providers.gradleProperty("releaseVersionName").orElse("0.1.2-internal").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_COMMIT", "\"${providers.gradleProperty("gitCommit").orElse("local").get().replace(Regex("[^a-zA-Z0-9-]"), "")}\"")
    }
    signingConfigs {
        getByName("debug") {
            val local = Properties().apply {
                rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
            }
            val localToolchain = rootProject.file("../.toolchain.local.json").takeIf { it.isFile }
                ?.let { JsonSlurper().parse(it) as Map<*, *> }
            (providers.environmentVariable("RAINNOTIFY_DEBUG_KEYSTORE").orNull
                ?: (localToolchain?.get("DebugKeystorePath") as? String)?.takeIf { it.isNotBlank() }
                ?: local.getProperty("rainnotify.debugKeystore"))?.let {
                storeFile = file(it)
            }
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        create("smoke") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".smoke"
            matchingFallbacks += listOf("debug")
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            // Release signing is performed locally by package_release.ps1.
        }
    }
    testBuildType = "smoke"
    buildFeatures { buildConfig = true; compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.preview)
    implementation(libs.compose.livedata)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.tooling)
    androidTestImplementation(libs.compose.test)
    implementation(libs.androidx.livedata)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso)
}
