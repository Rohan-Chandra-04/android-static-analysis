# Android Static Analysis

We had setup this whole static analysis framework on Ubuntu 24.

## 1. Environment Setup & Image Extraction

1.  **Download a pixel factory image** from this link: [Google Developers - Factory Images](https://developers.google.com/android/images#oriole)

2.  **Unzip this factory image** and find the `system.img` present in unzipped image.

3.  **Download required tools on ubuntu:**
    ```bash
    sudo apt-get update
    sudo apt-get install openjdk-11-jdk android-sdk-platform-tools-common simg2img git python3
    ```

4.  **Locate `system.img`.**
    * If it is `system.img.br`, decompress it:
        ```bash
        brotli -d system.img.br
        ```
    * *(Optional)* Convert the sparse image to a raw image (if necessary):
        ```bash
        simg2img system.img system_raw.img
        ```

5.  **Mount the image** to access files:
    ```bash
    mkdir /mnt/pixel-image
    sudo mount -o loop,ro system.img /mnt/pixel-image
    ```

6.  **Locate the framework files** (usually at `/mnt/system/framework`).
    * You will likely find files like `services.jar`, `framework.jar`, etc.
    * *Note:* If these files contain only `classes.dex`, you are lucky.
    * *Common Case:* They are empty shells, and the code lives in `.oat`, `.vdex`, or `.odex` files in the `oat/` or `arm64/` subdirectories.
    * *Tool:* Use `vDexExtractor` or `baksmali` to convert these back to DEX/JAR.

7.  **Current Status:** We have `classes.dex` file readily available in `.jar` files of framework folder.

---

## 2. Dependency Setup

Here is exactly how to get both the `android.jar` (the Android SDK platform definition) and `soot.jar` (the analysis library) on your Ubuntu machine.

### How to get `android.jar`
This file is part of the Android SDK. You need the specific version that matches the API level of your pixel image (e.g., if you downloaded a Pixel 8 Android 14 image, you need API 34).

#### Option A: Using Android Studio (Easiest if installed)
If you already have Android Studio installed:
1.  Open Android Studio -> **Tools** -> **SDK Manager**.
2.  Go to **SDK Platforms**.
3.  Check the box for the Android version you need (e.g., Android 14.0 "UpsideDownCake").
4.  Click **Apply** to download it.

**Where is the file?**
On Ubuntu, it is typically located here:
`/home/YOUR_USERNAME/Android/Sdk/platforms/android-34/android.jar`

#### Option B: Command Line (Server/Headless)
If you don't want the full Android Studio, use the command line tools we installed earlier (`android-sdk-platform-tools-common` isn't enough for the full SDK, so we need the `cmdline-tools`).

1.  **Download the Command Line Tools:**
    ```bash
    mkdir -p ~/android-sdk/cmdline-tools
    cd ~/android-sdk/cmdline-tools
    wget [https://dl.google.com/android/repository/commandlinetools-linux-10406996_latest.zip](https://dl.google.com/android/repository/commandlinetools-linux-10406996_latest.zip)
    unzip commandlinetools-linux-*_latest.zip
    mv cmdline-tools latest
    ```
    *(Note: We move it to a folder named `latest` because the SDK manager requires this specific directory structure: `cmdline-tools/latest/bin`).*

2.  **Accept Licenses and Install Platform:**
    Set your path momentarily and install the specific platform version (e.g., 34 for Android 14).
    ```bash
    export ANDROID_HOME=$HOME/android-sdk
    export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin

    # List available packages (optional)
    # sdkmanager --list

    # Install the platform
    yes | sdkmanager "platforms;android-34"
    ```

3.  **Find the file:**
    It will be at: `~/android-sdk/platforms/android-34/android.jar`

### How to get `soot.jar`
You can download the compiled JAR directly from the Maven Central Repository. We used version 4.5.0 in the code example.

1.  **Run this command** in your project folder:
    ```bash
    wget [https://repo1.maven.org/maven2/org/soot-oss/soot/4.5.0/soot-4.5.0-jar-with-dependencies.jar](https://repo1.maven.org/maven2/org/soot-oss/soot/4.5.0/soot-4.5.0-jar-with-dependencies.jar)
    ```

2.  **Important:** Rename it to `soot-4.5.0.jar` to match the compile command I gave you earlier, or adjust your command to match this filename.
    ```bash
    mv soot-4.5.0-jar-with-dependencies.jar soot-4.5.0.jar
    ```

---

## 3. Final Setup Verification

Before compiling, your project folder (`~/analysis_work`) should look like this:

```text
~/analysis_work/
├── FrameworkAnalyzer.java   (The code I provided)
├── soot-4.5.0.jar           (Downloaded via wget)
├── services.jar             (Your extracted target file)
└── android.jar              (Copy this here for easier access, or link to SDK path)


```bash
javac -cp soot-4.5.0.jar FrameworkAnalyzer.java
java -Xmx16g -cp .:soot-4.5.0.jar FrameworkAnalyzer
```