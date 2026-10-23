plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.clean.discord"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.clean.discord"
        minSdk = 24
        targetSdk = 36
        // CI passes the version from the release tag, e.g. v1.2.3 -> name 1.2.3, code 10203.
        val version = (project.findProperty("versionName") as String?) ?: "1.1.0"
        val parts = version.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 } + listOf(0, 0, 0)
        versionName = version
        versionCode = parts[0] * 10000 + parts[1] * 100 + parts[2]
        // Remote WebView debugging, only for local test builds (-PwebDebug=true).
        buildConfigField("boolean", "WEB_DEBUG", (project.findProperty("webDebug") == "true").toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        aidl = false
        buildConfig = true
        shaders = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
}
