# MedNotes PDF

Native Android medical-notes formatter built with Kotlin and Jetpack Compose.

## Features

- Accept English medical text pasted by the user.
- Use only the text supplied by the user; no textbook lookup, translation, or added outside medical facts.
- Organize content under headings such as Definition, Etiology/Causes, Classification, Mechanism of Action, Pathogenesis, Clinical Features, Investigations, Treatment, Complications, Prevention, and Important Points.
- Display mechanism/pathogenesis content as a simple flowchart when the supplied text is arranged in steps or lines.
- Preview notes and save them as a PDF through Android's system document picker.
- Offline mode requires no API key. AI mode requires a Gemini API key and network access; any free quota or pricing depends on Google's current API terms.

## Important limitations

- **Offline mode:** scores and selects source sentences in their original order. It does not send text over the network, generate new sentences, translate, browse textbooks, or add outside medical facts.
- **Optional AI mode:** the user may enter their own Gemini API key at runtime. The pasted text is sent to Google Gemini only when the AI button is tapped. The prompt requests source-only summarization, and the app restores the source Definition section verbatim afterward. Model output can still be wrong or omit context, so review it carefully. The key is not saved by the app.
- The Definition section is kept verbatim after the heading/bullet marker is removed. Causes and other short structured lists are preserved as points; mechanism/pathogenesis sections are displayed as a step flowchart.
- Both modes can omit context or miss important information. Always review the preview and compare it with the source before using notes for exams or clinical learning. This is not a substitute for medical judgment.
- Sentence splitting is English-rule based and may not handle every abbreviation or unusual formatting perfectly.

## Build

- Package: `com.example.medvideoai`
- Use JDK 17 and Android SDK 35.
- Open the repository in Android Studio or AndroidIDE and sync Gradle.
- Build with `./gradlew clean assembleDebug`.
- Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

No API key is required. Never commit API keys, `local.properties`, or signing keys.
