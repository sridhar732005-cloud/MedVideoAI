# MedVideoAI

Native Android medical storyboard and animation prototype built with Kotlin and Jetpack Compose.

## Current free-mode features

- Enter medical text in Tamil or English.
- Split text into a storyboard of up to 12 scenes.
- Rule-based topic matching for kidney, heart, lungs, ear, brain, microbes, and eye.
- Preview simple animated educational illustrations for each scene.
- Export a captioned template MP4 to Movies/MedVideoAI in 30-second, 1-minute, or 3-minute durations.
- No paid video-generation API or API key is used by the current free-mode code.

## Important current limitations

This version uses offline keyword/rule-based matching. It is **not** a general-purpose AI model and cannot reliably understand every medical description. The illustrations are simple shapes rather than detailed anatomical images. The current MP4 export does not yet include spoken narration, imported AI images, or generated AI video. Please verify medical details against trusted medical references.

## Planned improvements

1. Improve medical text analysis and the scene-by-scene storyboard.
2. Add more topic-specific medical illustrations and motion.
3. Add Android Text-to-Speech narration and synchronize captions.
4. Allow users to import images they generated separately, including images created in ChatGPT.
5. Evaluate optional AI services only after checking free-tier limits, privacy, and fallback behavior. Do not assume any external image/video generation API is free.

## Build

- Package: `com.example.medvideoai`
- Use JDK 17 and Android SDK 35.
- Open the repository in Android Studio or AndroidIDE, sync Gradle, then run `./gradlew clean assembleDebug`.
- The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

No API key is required for free mode. Never commit API keys, `local.properties`, or signing keys to the repository.
