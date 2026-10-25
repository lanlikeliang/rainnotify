plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "cn.rainnotify.app"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "cn.rainnotify.app"
        minSdk = 36
        targetSdk = 36
        versionCode = providers.gradleProperty("releaseVersionCode").orElse("20").get().toInt()
        versionName = providers.gradleProperty("releaseVersionName").orElse("0.1.0-internal").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_COMMIT", "\"${providers.gradleProperty("gitCommit").orElse("local").get().replace(Regex("[^a-zA-Z0-9-]"), "")}\"")
    }
    signingConfigs {
        getByName("debug") {
            val local = java.util.Properties().apply {
                rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
            }
            (providers.environmentVariable("RAINNOTIFY_DEBUG_KEYSTORE").orNull
                ?: local.getProperty("rainnotify.debugKeystore"))?.let {
                storeFile = file(it)
            }
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            // Release signing is performed locally by package_release.ps1.
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.androidx.activity)
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
