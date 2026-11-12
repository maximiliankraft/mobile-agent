# Mobile Agent

A native Android prototype for a user-controlled, local-first assistant. This first version demonstrates a key safety rule: the app shows an explicit confirmation before launching an Android action.

## Current prototype

- Presents a sample calendar-event proposal.
- Requires confirmation before handing the event to a calendar app.
- Leaves the final save decision to the calendar app.
- Uses no calendar permission and does not connect to an AI model, read contacts, or run shell commands.

The sample event is intentionally static; this is a starting point for the agent and tool architecture, not a connected AI assistant.

## Build

Open the project in Android Studio, or build it from the repository root with:

```shell
./gradlew assembleDebug
```

The app requires Android 8.0 (API 26) or later.