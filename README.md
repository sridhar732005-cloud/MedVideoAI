# MedNotes PDF

Native Android medical-notes formatter built with Kotlin and Jetpack Compose.

## Features

- Paste medical study text in English or Tamil.
- Translate Tamil text to English using Google's free on-device ML Kit translation model (the model may need to download on first use).
- Organize text into concise sections: Definition, Etiology/Causes, Classification, Mechanism of Action, Pathogenesis, Clinical Features, Investigations, Treatment, Complications, Prevention, and Important Points.
- Preserve supplied English definition wording and organize mechanism/pathogenesis lines as flowcharts.
- Preview notes before exporting.
- Save a PDF using Android's system document picker.
- No paid API key or video-generation service is used.

## Important limitations

- This is a rule-based formatter, not a medical AI model. It organizes the text you provide; it does not reliably invent missing medical facts or validate accuracy.
- Tamil-to-English translation uses a downloadable on-device language model and requires internet access for the initial model download.
- Flowcharts use the order of the supplied lines. For best results, label sections clearly in the source text.
- Always check medical definitions and facts against your prescribed textbook before exam use.

## Build

- Package: `com.example.medvideoai`
- Use JDK 17 and Android SDK 35.
- Open the repository in Android Studio or AndroidIDE and sync Gradle.
- Build with `./gradlew clean assembleDebug`.
- Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

No API key is required. Never commit API keys, `local.properties`, or signing keys.
