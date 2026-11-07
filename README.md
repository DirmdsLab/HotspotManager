<h1 align="center">HotspotManager</h1>

<p align="center">
  <img src="app/src/main/res/drawable/ic_launcher_foreground.png" width="180" alt="HotspotManager App Icon">
</p>

## Overview

HotspotManager is an Android application designed to manage hotspot functionality.

## Requirements

Before building and installing the application, make sure your device meets the following requirements.

### Android Requirements

- Android device with **root access**.
- **Magisk v31.1** installed.
  - [Download Magisk v31.1](https://github.com/topjohnwu/Magisk/releases/download/v31.1/Magisk-v31.1.apk)
  - [GitHub Repository](https://github.com/topjohnwu/Magisk)
- **LineageOS GSI by MisterZtr**.
  - [Download LineageOS GSI v2026.05.24-lineage23.2](https://github.com/MisterZtr/LineageOS_gsi/releases/tag/v2026.05.24-lineage23.2)
  - [GitHub Repository](https://github.com/MisterZtr/LineageOS_gsi)
- **Android Vector Loader** installed.
  - [Download Vector v2.2](https://github.com/JingMatrix/Vector/releases/download/v2.2/Vector-v2.2-3080-Release.zip)
  - [GitHub Repository](https://github.com/JingMatrix/Vector)

### Vector Configuration

After installing Vector:

1. Download the required module.
2. Install the module.
3. Enable **Zygisk**.
4. Open Vector and navigate to the **Modules** section.
5. Enable the module and check both options available in the HotspotManager application menu.

## Build Instructions

### 1. Clone the Repository

```
git clone https://github.com/DirmdsLab/HotspotManager.git

cd HotspotManager
```

### 2. Prepare the Keystore Directory

Before starting the build container, create a directory to store your Android signing keys.

You can choose any directory on your host machine. The following path is just an example:

```
mkdir -p $HOME/File/Software/App/env/Android/Key-HotspotManager
```

You can generate your own signing key inside the container or use an existing keystore. If you already have a keystore, adjust the paths and configuration accordingly.

### 3. Start the Build Container

Run the following command to start the Android SDK container:

```
podman run -it \
    --name HotspotManager-build \
    -v $(pwd):/workspace/HotspotManager:Z \
    -v $HOME/File/Software/App/env/Android/Key-HotspotManager:/workspace/key:Z \
    -w /workspace \
    ghcr.io/cirruslabs/android-sdk:36 \
    bash
```

This mounts the project directory and keystore directory into the container.

### 4. Generate a Signing Key

Inside the container, generate your own Android release signing key:

```
keytool -genkeypair -v \
  -keystore /workspace/key/infinix-hotspot-release.jks \
  -alias infinix-hotspot \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Follow the prompts to configure your keystore password and other certificate information.

**Note:** Remember the password you enter. You will need it when configuring the keystore properties.

### 5. Configure Keystore Properties

Install Nano and create the keystore configuration file:

```
apt update && apt install nano
nano key/keystore.properties
```

Add the following configuration:

```
storePassword=PW_KEY
keyPassword=PW_KEY
keyAlias=infinix-hotspot
```

Replace `PW_KEY` with the password you entered when generating your signing key.

Since the key generation process uses a single password, use the same password for both `storePassword` and `keyPassword`.

### 6. Build the Application

Navigate to the project directory:

```
cd HotspotManager
```

Run the Gradle build command:

```
./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
```

Wait for the build process to finish.

### 7. Locate the APK

If the build completes successfully, the release APK will be generated at:

```
app/build/outputs/apk/release/app-release.apk
```

### 8. Install the Application

From your host machine, navigate to the generated APK directory and install the application using ADB:

```
adb install app-release.apk
```

Make sure USB debugging is enabled and your Android device is connected.

## Development Notes

This project was developed with AI-assisted coding using:

- GPT-6.1 (Sol) — High
- GPT-6 — High

The code was generated through a vibe-coding workflow.
