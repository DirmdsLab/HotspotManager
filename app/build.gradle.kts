import java.util.Properties

plugins {
    id("com.android.application")
}

val releasePropertiesFile = rootProject.file("../key/keystore.properties")
val releaseKeystoreFile = rootProject.file("../key/infinix-hotspot-release.jks")

// Local release credentials are optional so source archives remain buildable.
val hasReleaseSigning = releasePropertiesFile.isFile || releaseKeystoreFile.isFile
val releaseProperties = Properties().apply {
    if (hasReleaseSigning) {
        require(releasePropertiesFile.isFile && releaseKeystoreFile.isFile) {
            "Release signing requires both ../key/keystore.properties and ../key/infinix-hotspot-release.jks"
        }
        releasePropertiesFile.inputStream().use { load(it) }
        for (name in listOf("storePassword", "keyPassword", "keyAlias")) {
            require(!getProperty(name).isNullOrBlank()) { "Missing release signing property: $name" }
        }
    }
}

android {
    namespace = "uwu.waguri.hotspotmanager"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "uwu.waguri.hotspotmanager"
        minSdk = 29
        targetSdk = 36
        versionCode = 20
        versionName = "0.6.10"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseProperties.getProperty("storePassword")
                keyAlias = releaseProperties.getProperty("keyAlias")
                keyPassword = releaseProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
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
    testImplementation("junit:junit:4.13.2")
}

// Report the exact deprecated call if one is introduced in a future change.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}
