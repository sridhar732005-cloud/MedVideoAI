# MedVideoAI

Native Android starter project for an AI Medical Video Generator, built with Kotlin and Jetpack Compose.

## Features
- Medical topic input and quick topic presets
- Tamil, English, or bilingual prompt preference
- Preferred final duration and visual style
- Higgsfield API test generation button
- Generation status and Open video action

## Local Higgsfield test setup

1. In the project root (the same folder as `settings.gradle.kts`), create or edit `local.properties`.
2. Add this line, using your own API key locally:

   ```properties
   HIGGSFIELD_API_KEY=YOUR_COMPLETE_HIGGSFIELD_API_KEY
   ```

3. Do not add the key to GitHub, screenshots, or chat. The root `.gitignore` excludes `local.properties`.
4. Sync Gradle and build the debug APK.
5. In the app, enter a medical topic and tap **Generate Test Video (5 sec)**. This sends a single five-second text-to-video request using the Seedance 2.0 endpoint, then polls the returned status URL. It may consume paid API credits.

## Important security limitation

This is a private prototype integration only. The Gradle configuration injects the API key into Android BuildConfig, which means it is embedded in the APK and can be extracted by someone who obtains the APK. Do not distribute this build or publish it to Play Store. Before sharing/releasing the app, move provider requests to a secure backend and rotate the test API key if the APK was shared.

The selected duration is currently prompt guidance only; the live API test deliberately generates a five-second clip. The chosen visual style and language are included in the prompt, but do not guarantee narration or a specific presentation style. API access, billing, and model availability depend on the Higgsfield account.

## Android build
Open this repository as a Gradle project in Android Studio or a compatible Android IDE. Use JDK 17 and Android SDK 35, sync Gradle, then build the `app` configuration.

Package: `com.example.medvideoai`
