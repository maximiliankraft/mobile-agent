# Mobile Agent

A native Android assistant prototype powered by DeepSeek. It can propose a small set of local tools and requires explicit approval before every action.

## Features and safety limits

- Connects to the DeepSeek chat completions API using the `deepseek-chat` model.
- Enter your DeepSeek API key in the app. It is held in memory only and cleared when the app closes; it is not saved in the project or preferences.
- Can propose creating a UTF-8 text file in its private workspace (`files/workspace`), limited to 16 KiB.
- Can propose the read-only commands `pwd`, `ls`, `cat`, and `wc` inside that workspace. This is intentionally not an unrestricted shell; no shell expansion or arbitrary commands are allowed.
- Can propose opening the calendar app with an event. The calendar app handles the final save.
- Shows the proposed path and full file contents, command, or event details and asks before each tool call. Canceling a prompt does not execute that action.
- Does not request calendar or contacts permissions. Chat messages are sent to DeepSeek to generate replies and tool proposals.

Keep your API key private. This prototype does not provide production-grade key management, conversation persistence, or unrestricted terminal access.

## Build

Open the project in Android Studio, or build it from the repository root with:

```shell
./gradlew assembleDebug
```

The app requires Android 8.0 (API 26) or later.

## Tests and CI

Run the local JVM unit tests with:

```shell
./gradlew testDebugUnitTest
```

GitHub Actions runs the unit tests on pushes and pull requests. After they pass, it builds the debug APK and publishes it as the `mobile-agent-debug-apk` workflow artifact.