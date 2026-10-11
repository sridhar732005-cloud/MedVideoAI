# MedVideoAI

Native Android medical storyboard and animation prototype built with Kotlin and Jetpack Compose.

## Current free-mode features

- Enter medical text in Tamil or English.
- Split text into a storyboard of up to 12 scenes.
- Rule-based topic matching for kidney, heart, lungs, ear, brain, microbes, and eye.
- Preview simple animated educational illustrations for each scene.
- Export a captioned template MP4 to Movies/MedVideoAI in 30-second, 1-minute, or 3-minute durations.
- Import medical images from the device gallery and keep them in app-private storage.
- Add a title and comma-separated medical topic keywords to each imported image.
- Search imported images by title or topic keywords.
- No paid video-generation API or API key is used by the current free-mode code.

## Important current limitations

This version uses offline keyword/rule-based matching. It is **not** a general-purpose AI model and cannot reliably understand every medical description. The illustrations are simple shapes rather than detailed anatomical images. Imported images are currently stored and searchable in the image library; they are **not yet automatically matched into storyboard scenes or included in the exported MP4**. The current MP4 export does not yet include spoken narration or generated AI video. Please verify medical details against trusted medical references.

## Planned improvements

1. Improve medical text analysis and the scene-by-scene storyboard.
2. Add more topic-specific medical illustrations and motion.
3. Add Android Text-to-Speech narration and synchronize captions.
4. Connect imported images to automatic scene-by-scene matching and video export.
5. Add an online search for properly licensed medical images, with source and attribution metadata.
6. Evaluate optional AI services only after checking free-tier limits, privacy, and fallback behavior. Do not assume any external image/video generation API is free.

## Build

- Package: `com.example.medvideoai`
- Use JDK 17 and Android SDK 35.
- Open the repository in Android Studio or AndroidIDE, sync Gradle, then run `./gradlew clean assembleDebug`.
- The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

No API key is required for free mode. Never commit API keys, `local.properties`, or signing keys to the repository.
