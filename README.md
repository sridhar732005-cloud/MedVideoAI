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

- The app now uses an offline extractive summarizer: it scores sentences from the supplied text and selects a shorter set in their original order. It does not generate new sentences, translate, browse textbooks, or add outside medical facts.
- The Definition section is kept verbatim after the heading/bullet marker is removed. Other sections are condensed by selecting source sentences; mechanism/pathogenesis sections are displayed as a step flowchart.
- Extractive summarization can omit context or miss an important sentence. Always review the preview and compare it with the source before using notes for exams or clinical learning. This is not a substitute for medical judgment.
- Sentence splitting is English-rule based and may not handle every abbreviation or unusual formatting perfectly.

## Build

- Package: `com.example.medvideoai`
- Use JDK 17 and Android SDK 35.
- Open the repository in Android Studio or AndroidIDE and sync Gradle.
- Build with `./gradlew clean assembleDebug`.
- Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

No API key is required. Never commit API keys, `local.properties`, or signing keys.
