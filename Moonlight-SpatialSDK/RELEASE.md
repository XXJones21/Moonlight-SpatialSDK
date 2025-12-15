# Release Guide

This guide provides step-by-step instructions for building signed release APKs and distributing them via GitHub releases.

## Prerequisites

- Java Development Kit (JDK) 17 or later
- Android SDK with build tools installed
- Gradle (included via wrapper)
- A keystore file for signing (see "Creating a Keystore" below)

## Step 1: Create a Keystore

If you don't already have a keystore for signing release builds, create one using the `keytool` command:

**Important**: All commands in this guide should be run from the `Moonlight-SpatialSDK/Moonlight-SpatialSDK` directory (the project root where `build.gradle.kts` is located).

### On Windows (PowerShell):

First, navigate to the project directory:
```powershell
cd Moonlight-SpatialSDK
```

Then create the keystore:
```powershell
keytool -genkey -v -keystore app/release.keystore -alias release_key -keyalg RSA -keysize 2048 -validity 10000
```

### On macOS/Linux:

First, navigate to the project directory:
```bash
cd Moonlight-SpatialSDK
```

Then create the keystore:
```bash
keytool -genkey -v -keystore app/release.keystore -alias release_key -keyalg RSA -keysize 2048 -validity 10000
```

**Important Information:**
- **Keystore file location**: Store the keystore file in `app/release.keystore` (or another secure location)
- **Validity period**: The example above sets validity to 10000 days (~27 years). Adjust as needed.
- **Passwords**: You'll be prompted to enter:
  - A keystore password (protects the keystore file)
  - A key password (protects the specific key; can be same as keystore password)
- **Certificate information**: You'll be asked for your name, organizational unit, organization, city, state, and country code

**Security Note**:
- Keep your keystore file and passwords secure. If you lose them, you won't be able to update your app on the Play Store or Quest Store.
- Never commit the keystore file or passwords to version control.

## Step 2: Configure Signing Credentials

### Option A: Using keystore.properties (Recommended for Local Development)

1. Copy the example file:

   ```bash
   cp keystore.properties.example keystore.properties
   ```

2. Edit `keystore.properties` and fill in your actual values:

   ```properties
   storeFile=app/release.keystore
   storePassword=your_actual_keystore_password
   keyAlias=release_key
   keyPassword=your_actual_key_password
   ```

3. Verify that `keystore.properties` is in `.gitignore` (it should be).

### Option B: Using Environment Variables (Recommended for CI/CD)

Set the following environment variables:

- `KEYSTORE_FILE`: Path to your keystore file
- `KEYSTORE_PASSWORD`: Keystore password
- `KEY_ALIAS`: Key alias name
- `KEY_PASSWORD`: Key password

**Windows (PowerShell):**
```powershell
$env:KEYSTORE_FILE="app/release.keystore"
$env:KEYSTORE_PASSWORD="your_keystore_password"
$env:KEY_ALIAS="release_key"
$env:KEY_PASSWORD="your_key_password"
```

**macOS/Linux:**
```bash
export KEYSTORE_FILE="app/release.keystore"
export KEYSTORE_PASSWORD="your_keystore_password"
export KEY_ALIAS="release_key"
export KEY_PASSWORD="your_key_password"
```

## Step 3: Update Version Information

Before building a release, update the version information in `app/build.gradle.kts`:

```kotlin
defaultConfig {
    versionCode = 2  // Increment for each release
    versionName = "1.1"  // Update version string
    // ... other config
}
```

**Version Guidelines:**
- `versionCode`: Integer that must be incremented for each release. This is used by Android to determine which version is newer.
- `versionName`: Human-readable version string (e.g., "1.0", "1.1.0", "2.0.0-beta1")

## Step 4: Build the Release APK

### Build Command

**Important**: Navigate to the project root directory (`Moonlight-SpatialSDK/Moonlight-SpatialSDK`) where `build.gradle.kts` is located, then run:

**Windows:**
```powershell
.\gradlew.bat assembleRelease
```

**macOS/Linux:**
```bash
./gradlew assembleRelease
```

### Output Location

The signed release APK will be generated at:
```
app/build/outputs/apk/release/app-release.apk
```

### Verify the APK is Signed

You can verify the APK is properly signed using:

```bash
jarsigner -verify -verbose -certs app/build/outputs/apk/release/app-release.apk
```

Or using `apksigner` (if available):
```bash
apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```

## Step 5: Test the Release APK

Before distributing, test the release APK on a Quest 3 device:

1. **Uninstall any existing debug version** (if installed):
   ```bash
   adb uninstall com.example.moonlight_spatialsdk
   ```

2. **Install the release APK**:
   ```bash
   adb install app/build/outputs/apk/release/app-release.apk
   ```

3. **Test all functionality**:
   - Launch the app
   - Test connection and pairing
   - Test streaming functionality
   - Verify all features work as expected

## Step 6: Create a GitHub Release

### Using GitHub Web Interface

1. **Navigate to your repository** on GitHub
2. **Click "Releases"** in the right sidebar (or go to `https://github.com/YOUR_USERNAME/YOUR_REPO/releases`)
3. **Click "Draft a new release"** or "Create a new release"
4. **Fill in release information**:
   - **Tag version**: Create a new tag (e.g., `v1.0.0`) or select an existing tag
   - **Release title**: Descriptive title (e.g., "Moonlight SpatialSDK v1.0.0")
   - **Description**: Release notes describing changes, features, and fixes
5. **Attach the APK**:
   - Click "Attach binaries"
   - Upload `app/build/outputs/apk/release/app-release.apk`
   - Optionally rename it to something more descriptive like `Moonlight-SpatialSDK-v1.0.0.apk`
6. **Publish the release**

### Using GitHub CLI (gh)

If you have GitHub CLI installed:

```bash
gh release create v1.0.0 \
  app/build/outputs/apk/release/app-release.apk \
  --title "Moonlight SpatialSDK v1.0.0" \
  --notes "Release notes here"
```

### Using Git Tags and Manual Upload

1. **Create and push a tag**:
   ```bash
   git tag -a v1.0.0 -m "Release version 1.0.0"
   git push origin v1.0.0
   ```

2. **Create release on GitHub** and attach the APK manually

## Step 7: Distribution Instructions for Users

Provide users with instructions on how to install the APK:

### Installing on Quest 3

1. **Enable Developer Mode** on your Quest 3:
   - Open the Oculus mobile app
   - Go to Settings → Devices → Quest 3
   - Enable Developer Mode
   - Restart your Quest 3

2. **Enable USB Debugging** (for ADB installation):
   - Connect Quest 3 to your PC via USB
   - Put on the headset and allow USB debugging when prompted

3. **Download the APK** from the GitHub release page

4. **Install via ADB**:
   ```bash
   adb install path/to/Moonlight-SpatialSDK-v1.0.0.apk
   ```

5. **Or use SideQuest** (alternative method):
   - Install SideQuest on your PC
   - Connect Quest 3 via USB
   - Use SideQuest to install the APK

### Alternative: Direct Download on Quest 3

Users can also download the APK directly on the Quest 3 using a browser and install it using a file manager app, though this requires additional setup.

## Troubleshooting

### Build Fails: "Keystore file not found"

- Verify the `storeFile` path in `keystore.properties` is correct
- Ensure the keystore file exists at the specified location
- Use absolute paths if relative paths don't work

### Build Fails: "Keystore was tampered with, or password was incorrect"

- Double-check your keystore password
- Verify the key alias is correct
- Ensure you're using the correct keystore file

### APK Installation Fails: "INSTALL_FAILED_UPDATE_INCOMPATIBLE"

- The release APK has a different signature than the debug version
- Uninstall the debug version first: `adb uninstall com.example.moonlight_spatialsdk`
- Then install the release APK

### APK Installation Fails: "INSTALL_FAILED_VERSION_DOWNGRADE"

- The installed version has a higher `versionCode` than the APK you're trying to install
- Either uninstall the existing version or increment the `versionCode` in `build.gradle.kts`

## Security Best Practices

1. **Never commit keystore files or passwords** to version control
2. **Back up your keystore file** in a secure location (encrypted storage, password manager, etc.)
3. **Use strong passwords** for both keystore and key
4. **Limit access** to signing credentials to trusted team members only
5. **For CI/CD**: Use secure environment variables or secret management systems (GitHub Secrets, etc.)
6. **Document keystore location** in a secure, private location for team members who need it

## Continuous Integration (CI/CD)

For automated releases, consider setting up GitHub Actions:

1. Store signing credentials as GitHub Secrets
2. Create a workflow that:
   - Builds the release APK
   - Signs it using the stored credentials
   - Creates a GitHub release
   - Attaches the APK

Example workflow structure:
```yaml
name: Build Release APK

on:
  push:
    tags:
      - 'v*'

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
      - name: Build Release APK
        env:
          KEYSTORE_FILE: ${{ secrets.KEYSTORE_FILE }}
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
        run: ./gradlew assembleRelease
      - name: Create Release
        uses: softprops/action-gh-release@v1
        with:
          files: app/build/outputs/apk/release/app-release.apk
```

## Additional Resources

- [Android App Signing Documentation](https://developer.android.com/studio/publish/app-signing)
- [Gradle Signing Configuration](https://developer.android.com/studio/publish/app-signing#gradle-sign)
- [GitHub Releases Documentation](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)
