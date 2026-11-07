import java.util.Properties

plugins {
    id("com.android.application")
}

val releasePropertiesFile = rootProject.file("../key/keystore.properties")
val releaseKeystoreFile = rootProject.file("../key/infinix-hotspot-release.jks")

val releaseProperties = Properties().apply {
    if (!releasePropertiesFile.isFile) {
        throw GradleException("Keystore properties file not found: ${releasePropertiesFile.absolutePath}")
    }
    releasePropertiesFile.inputStream().use { input ->
        load(input)
    }
}

val releaseStorePassword = releaseProperties.getProperty("storePassword")
    ?: throw GradleException("Missing storePassword")
val releaseKeyPassword = releaseProperties.getProperty("keyPassword")
    ?: throw GradleException("Missing keyPassword")
val releaseKeyAlias = releaseProperties.getProperty("keyAlias")
    ?: throw GradleException("Missing keyAlias")

if (!releaseKeystoreFile.isFile) {
    throw GradleException("Release keystore not found: ${releaseKeystoreFile.absolutePath}")
}

android {
    namespace = "uwu.waguri.hotspotmanager"
    compileSdk = 36

    defaultConfig {
        applicationId = "uwu.waguri.hotspotmanager"
        minSdk = 29
        targetSdk = 36
        versionCode = 17
        versionName = "0.6.7"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystoreFile
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // Keep reflective Xposed hook entry points and target methods intact.
            isMinifyEnabled = false
            isDebuggable = false
        }
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.15.0")
    compileOnly("io.github.libxposed:api:102.0.0")
}
