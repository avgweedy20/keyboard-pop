# Auto Focus for Telegram

A lightweight, independent Android background utility app that automatically focuses Telegram's message input box and raises the soft keyboard the instant you open any chat.

---

## File Structure Overview

```
keyboard-pop/
├── .github/
│   └── workflows/
│       └── release.yml                 # GitHub Actions CI/CD workflow for release builds
├── app/
│   ├── build.gradle.kts                # Module build configuration (Kotlin DSL)
│   ├── proguard-rules.pro              # Proguard rules
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml     # App manifest declaring components and permissions
│           ├── java/com/autofocus/telegram/
│           │   ├── MainActivity.kt                      # Status, switch toggle, disclosure dialog
│           │   └── TelegramFocusAccessibilityService.kt # Accessibility service logic
│           └── res/
│               ├── layout/
│               │   └── activity_main.xml                # App UI layout
│               ├── values/
│               │   ├── strings.xml                      # Localized string resources
│               │   └── themes.xml                       # Material3 themes
│               └── xml/
│                   └── accessibility_service_config.xml # Accessibility config
├── build.gradle.kts                    # Root build configuration
├── gradle/
│   └── libs.versions.toml              # Version catalog
├── gradle.properties                       # Gradle build environment properties
├── settings.gradle.kts                 # Project settings
└── README.md                           # Documentation
```

---

## How to Build Locally

### Prerequisites
- JDK 17
- Android SDK (API Level 34)

### Commands
To build the debug APK:
```bash
./gradlew assembleDebug
```
The output APK will be located at: `app/build/outputs/apk/debug/app-debug.apk`

To build the release APK locally:
```bash
./gradlew assembleRelease
```
*Note: If no release keystore environment variables are provided, Gradle defaults to using the debug keystore for local testing.*

---

## How to Grant Accessibility Permission on Device

1. Install the built APK on your Android device (minSdk 26+).
2. Open **Auto Focus for Telegram**.
3. Toggle the **Enable Accessibility Service** switch.
4. Read the prominent Accessibility Service Disclosure and tap **Proceed to Settings**.
5. In Android System Accessibility Settings, locate **Auto Focus for Telegram Service** and toggle it **ON**.
6. Open any chat in standard **Telegram** (`org.telegram.messenger`) or **Telegram X** (`org.telegram.messenger.web`). The input box will automatically receive focus and raise the keyboard.

---

## GitHub Secrets & Keystore Generation

To configure CI release signing via GitHub Actions, create a release keystore and add its details to your repository secrets.

### 1. Generate a Keystore File
Run the following command in your terminal:
```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias my-release-key \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

### 2. Encode the Keystore File to Base64
Convert the `release.keystore` file to a Base64 string:
- **Linux/macOS:**
  ```bash
  base64 -w 0 release.keystore > keystore_base64.txt
  ```
- **macOS (alternative):**
  ```bash
  openssl base64 -in release.keystore -out keystore_base64.txt
  ```

### 3. Add GitHub Repository Secrets
Go to **Settings > Secrets and variables > Actions** in your GitHub repository and add the following repository secrets:

- `KEYSTORE_BASE64`: Copy and paste the entire string from `keystore_base64.txt`.
- `KEYSTORE_PASSWORD`: Password specified during keystore creation.
- `KEY_ALIAS`: Alias used during creation (e.g. `my-release-key`).
- `KEY_PASSWORD`: Password specified for the key.

---

## How to Cut a Release

1. Commit all your changes and push them to `main`.
2. Create and push a new version tag:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
3. The `.github/workflows/release.yml` pipeline will automatically:
   - Build and sign `app-release.apk` using your configured secrets.
   - Create a GitHub Release for tag `v1.0.0`.
   - Upload `app-release.apk` as a release asset.
4. Alternatively, you can trigger the build manually from the **Actions** tab on GitHub using `workflow_dispatch`.

---

## Play Store Policy Caveat

Google Play Store policies strictly regulate the use of Android Accessibility Services. Apps using Accessibility Services for non-accessibility purposes (such as UI automation or auto-focus utility) risk rejection or removal from the Google Play Store unless granted specific policy exemptions.

**Notice:** This app is designed for **personal use and sideloading**. If you intend to publish this application on the Google Play Store, you must ensure compliance with Play Store Accessibility Service Policies or adjust the implementation accordingly.
