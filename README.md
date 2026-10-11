# MedNotes PDF

Native Android medical-notes formatter built with Kotlin and Jetpack Compose.

## Features

- Accept English medical text pasted by the user.
- Use only the text supplied by the user; no textbook lookup, translation, or added outside medical facts.
- Organize content under headings such as Definition, Etiology/Causes, Classification, Mechanism of Action, Pathogenesis, Clinical Features, Investigations, Treatment, Complications, Prevention, and Important Points.
- Display mechanism/pathogenesis content as a simple flowchart when the supplied text is arranged in steps or lines.
- Preview notes and save them as a PDF through Android's system document picker.
- No paid API key or video-generation service is used.

## Important limitations

- This version is a rule-based formatter, not an AI medical summarizer. It organizes the supplied text and preserves its wording; it cannot reliably rewrite every long paragraph into a shorter version while guaranteeing that medical meaning is unchanged.
- For concise results, paste source material with headings and short sentences. Review the generated notes before using them for exams.
- Medical facts are not independently verified.

## Build

- Package: `com.example.medvideoai`
- Use JDK 17 and Android SDK 35.
- Open the repository in Android Studio or AndroidIDE and sync Gradle.
- Build with `./gradlew clean assembleDebug`.
- Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

No API key is required. Never commit API keys, `local.properties`, or signing keys.
