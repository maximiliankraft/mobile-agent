# Mobile Agent

A native Android assistant prototype powered by DeepSeek. It can propose a small set of local tools and requires explicit approval before every action.

## Features and safety limits

- Connects to the DeepSeek chat completions API using the `deepseek-chat` model.
- Enter your DeepSeek API key on a separate settings screen. It is held in memory only and cleared when the app process closes; it is not saved in the project or preferences.
- Attach an image, a new camera photo, a text file, or a contact selected through Android. Queued items are included only in the next message the user sends. Selected text files are limited to 32 KiB and images to 5 MiB.
- Uses the user-visible shared-storage workspace `/storage/emulated/0/mobileagent`. After explicit approval, tools can read UTF-8 files (up to 64 KiB), write/replace UTF-8 files (up to 16 KiB), list directories (up to 500 entries / 32 KiB), and create directories. Relative paths resolve inside the workspace; an explicitly supplied absolute path may target any location Android permits. Android can still deny protected system paths and other apps' private data; the app does not bypass those restrictions.
- On Android 11+, file tools request Android's “All files access” special permission after a file action is approved. This grants broader shared-storage access but does not bypass protected system paths or other apps' private-data sandbox. Older Android versions use the platform storage permission.
- Can propose the read-only commands `pwd`, `ls`, `cat`, and `wc` inside the workspace. Unsupported commands and invalid argument counts receive a warning in chat and are not executed. This intentionally is not an unrestricted shell: arbitrary commands and `su` are not exposed.
- Can propose Android activity intents. The approval preview shows the supplied fields; Android resolves the handler and enforces its own permissions. The app reports an intent launch, not completion of the external action.
- Can search contacts by name after the user approves the search and grants Android contacts permission. Matches are displayed locally only and are not sent to DeepSeek; the agent asks the user to identify the intended contact. It can prepare an SMS or WhatsApp draft after the user provides recipient details in a submitted message, but the external app retains the final Send action.
- Includes an Agent files view for browsing and opening actual files in the workspace.
- Shows proposed actions and informational messages in chat bubbles. Denying an action does not execute it.

Keep your API key private. Anything you submit in chat—including selected images, text files, contact details you provide, read-file contents, and command output—is sent to DeepSeek. Contact-search results are displayed locally and are not sent to DeepSeek unless you include selected details in a submitted message. File reads and other model-proposed actions require approval first. The app does not persist the API key or chat history. Android controls access to storage, contacts, and external apps.

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

GitHub Actions runs the unit tests on pushes and pull requests. After they pass, it builds the debug APK and publishes it as the `mobile-agent-debug-apk` workflow artifact. Download it from the workflow run's Artifacts section.