# Mobile Agent — Functional Specification Document

- **Document status:** Product requirements and implementation baseline
- **Repository provenance:** `a97e80d0a613e9996df009e41eba620b9f60e319`
- **Anchor convention:** GitHub-style heading slugs (lowercase; punctuation removed; spaces become hyphens).
- **Purpose:** Define the required behavior of Mobile Agent on Android and distinguish it from the prototype behavior currently evidenced in the repository.

## Contents

1. [Purpose and scope](#1-purpose-and-scope)
2. [Users and system context](#2-users-and-system-context)
3. [Operating model](#3-operating-model)
4. [Functional requirements](#4-functional-requirements)
5. [Information, privacy, and permissions](#5-information-privacy-and-permissions)
6. [Non-functional requirements](#6-non-functional-requirements)
7. [Acceptance test cases](#7-acceptance-test-cases)
8. [Implementation gaps and open decisions](#8-implementation-gaps-and-open-decisions)
9. [Requirement index](#9-requirement-index)
10. [Evidence sources](#10-evidence-sources)

## 1. Purpose and scope

Mobile Agent is an Android assistant that sends user prompts to an AI service and can request permission to perform actions on the device. It is intended to provide a Hermes-like assistant workflow on a phone: chat, local file and command tools, Android intents, contact-assisted communication, image input, and visible agent-maintained notes and skills.

This FSD specifies the target product, not a claim that every requirement is already implemented. The current repository is an early prototype. Requirements marked **Existing** describe behavior already evidenced in source or tests; **Gap** means the draft establishes desired behavior that is not evidenced in the current implementation; **Open** means the requirement is known but a product or platform decision remains before it can be implemented and accepted.

The FSD covers the Android application, local device interactions it initiates, and its DeepSeek chat-completions integration. It does not specify the internals or availability of DeepSeek, Android system apps, third-party messaging apps, or root-management software.

## 2. Users and system context

### 2.1 Primary user

The primary user interacts with Mobile Agent directly on their Android device. The user supplies an API key, sends prompts, selects input items, reviews proposed device actions, and decides whether to approve them.

### 2.2 System boundary

The application comprises:

- A chat interface and a separate API-key settings screen.
- A model client that sends prompts and selected context to DeepSeek.
- A local tool executor for approved device actions.
- Android system and third-party applications, which receive intents and retain control over their own permissions and final actions.
- A default, user-visible workspace at `/storage/emulated/0/mobileagent` and an agent-files view for inspecting its contents.

The application must not claim that a third-party app completed an action merely because Android accepted an intent. It reports only what it can observe, such as an intent being launched or an external application being unavailable.

## 3. Operating model

### 3.1 Main user flow

1. The user opens the application. If no in-memory DeepSeek API key is available, the application opens API settings.
2. The user enters a key and opens chat. The application creates a conversation and submits user messages to DeepSeek.
3. The user may select supported context (text file, image, contact) or capture a new image. The application shows what is queued for the next message.
4. The model returns text and, where appropriate, a proposed local action.
5. Before any action that changes device state or sends information to another app, the application presents the exact action and waits for an explicit Allow or Deny decision.
6. The application executes only an allowed action, reports the result, and continues the conversation.
7. The user can open the agent-files view to inspect files and directories maintained in the workspace.

### 3.2 Action lifecycle

Each model-proposed action has the states **proposed**, **awaiting approval**, **denied**, **running**, and **completed** or **failed**. A denial must not execute the action. Approval applies only to the exact action shown; changing its command, path, intent fields, recipient, or payload requires a new approval. Android permission dialogs, root-manager prompts, and confirmation screens in external applications remain under their respective system/application control.

Read-only model utilities that do not access or modify private user data may run without an approval prompt. Any action that reads user-selected or broader private data, writes data, executes a command, launches an intent, or transfers information externally requires approval or a user-initiated selection/confirmation step.

## 4. Functional requirements

### FR-001: API key and provider configuration

**Status: Existing behavior retained.** The application shall provide a settings screen for a DeepSeek API key and use the `deepseek-chat` model through the chat-completions endpoint. The key shall be held in application memory only and shall not be written to preferences or project files. If the process is recreated without the key, the user shall be asked to enter it again. The key field shall mask its contents.

The application shall not send a chat request when the key is empty. It shall report connection, HTTP, or response errors to the user without claiming a successful model response.

### FR-002: Chat and model interaction

**Status: Existing behavior retained.** The user shall be able to compose and submit a non-empty message. While a request or tool round is in progress, duplicate sends shall be prevented. The application shall include the supported tool definitions in model requests, process tool calls sequentially, and continue the model turn with each tool result. It shall not claim an action occurred before receiving the local tool result.

The application shall bound a single model/tool interaction so malformed or looping tool requests cannot run indefinitely. Current implementation limits are eight model turns and four tool calls in a response; any change to these limits is an implementation decision, not a user-visible guarantee in this FSD.

**Evidence:** `app/src/main/java/com/maximiliankraft/mobileagent/MainActivity.java:166-200,329-405`.

### FR-003: Chat composer and keyboard layout

**Status: Gap.** When the prompt field receives focus and the on-screen keyboard opens, the prompt field and Send control shall remain visible immediately above the keyboard. The message history shall resize or scroll so the newest message and the active composer are not obscured. The user shall be able to enter and submit text without dismissing the keyboard to recover the composer.

The keyboard shall not cover the focused prompt field on supported Android versions and supported device orientations. Layout shall remain usable when the keyboard changes height or the display is resized.

### FR-004: Select and attach input context

**Status: Existing behavior with a capture gap.** The user shall be able to select an image, a text file, and a phone contact using Android-provided selection interfaces. Selected text and contact details, and the selected image, shall be visibly identified as pending and included only with the next message the user sends. The user shall be able to clear pending selections before sending.

The application shall reject unsupported content types and report read failures. Current limits are 5 MiB per selected image and 32 KiB per selected text file. It shall not request broad contacts or storage permission merely to use the system pickers.

A selected contact or file is not sent to DeepSeek merely by selecting it; it is sent only when the user submits a message containing that context. The application shall make this data flow clear in the interface.

**Evidence:** `MainActivity.java:130-145,210-269,272-310`; `README.md:7-16`.

### FR-005: Default user-visible workspace

**Status: Gap.** The default workspace shall be the directory `/storage/emulated/0/mobileagent`. Workspace-relative file and directory operations shall resolve beneath this location by default. The application shall create the directory when it can do so and shall show a clear, actionable error if Android has not granted the required access.

The application shall not silently substitute its private app directory when the requested shared workspace is inaccessible. It shall preserve user files and shall disclose whether a requested path is outside the accessible storage boundary. The exact Android access mechanism is subject to [OD-001](#od-001-storage-access-and-root-boundary).

### FR-006: Read, write, and list device files

**Status: Gap.** The agent shall be able to propose reading files, listing directories, creating or replacing UTF-8 text files, and creating directories at user-authorized paths on the device. If a tool call omits an absolute path, it shall use the default workspace. Paths supplied to a tool shall be shown in the approval prompt before a read or write occurs.

The application shall reject malformed paths and shall not follow a path outside the selected/granted root unless the user explicitly requested that path and the operating system grants access. Before a read, the approval prompt shall state that the file contents will be returned to the model service as tool output. A write shall report the path and whether it succeeded. A failed or denied operation shall leave existing data unchanged. The application shall not imply that Android app-private data belonging to other applications is accessible on an unrooted device.

The current prototype only creates files within an app-private workspace and limits each file to 16 KiB; it has no general read or directory-management tool. The target storage scope and any size limits must be implemented and verified.

**Evidence:** `AgentTools.java:17-56`; `AgentToolsTest.java:36-70`.

### FR-007: Run local device commands

**Status: Gap.** The agent shall be able to propose and, after explicit user approval, run arbitrary commands on the Android device. The default working directory shall be the workspace; a different working directory shall be shown and approved before execution. The approval view shall show the exact command, arguments, working directory, and whether elevated execution was requested. The application shall return the observed exit status and captured output, or a clear timeout, cancellation, permission, or execution error.

On a rooted device, the user shall be able to approve a command that invokes the installed `su` mechanism. Root access shall never be attempted silently. Android and any installed root manager remain authoritative over whether elevation is granted. The approval prompt shall state that captured command output will be returned to the model service as tool output. On a non-rooted device, commands run with the app's ordinary OS identity and permissions; the application shall not claim that arbitrary commands bypass Android's sandbox.

The command interface shall not execute a modified command after approval. Output, duration, cancellation, and interactive-command limits require resolution under [OD-002](#od-002-command-execution-policy). The current implementation is deliberately not a general shell: it permits only `pwd`, `ls`, `cat`, and `wc` inside a private workspace, with two arguments maximum, a 5-second execution timeout, and bounded output.

**Evidence:** `AgentTools.java:59-110`; `MainActivity.java:463-490,541-557,595-602`; `AgentToolsTest.java:72-116`.

### FR-008: Create and execute Android intents

**Status: Gap.** The agent shall be able to propose an Android intent for a device action and, after explicit approval, ask Android to dispatch it using the appropriate platform mechanism. The approval view shall present all material fields the application is about to submit, including action, target or package when specified, data URI, MIME type, and extras. If a field cannot be rendered safely or meaningfully, execution shall be refused and the user told why.

The application shall respect Android component visibility, export status, permissions, background-execution restrictions, URI grants, and any system confirmation. It shall not silently grant additional URI access or bypass a platform restriction. If multiple eligible apps exist, the user shall be able to choose or use the Android resolver. If no eligible app exists, the operation shall fail without claiming success.

The existing calendar action is a supported instance: after approval it opens an event draft in a calendar application; the calendar application controls the final save. The target capability is not limited to calendar intents.

**Evidence:** `MainActivity.java:463-492,559-576`; `AgentTools.java:113-127`.

### FR-009: Search contacts and prepare a message

**Status: Gap.** When a prompt refers to a person by name, the agent shall be able to search the device's contacts after the user grants the required Android contacts permission. It shall show matching names and relevant available communication details. If more than one person matches, it shall ask the user to select the intended person before constructing a communication action. If no match exists, it shall ask the user for another identifier or report that no match was found.

For a selected person, the agent shall be able to prepare a message using an installed application capable of handling the requested channel, including SMS, WhatsApp, or another compatible application. The selected recipient and message shall be displayed for approval. The external application shall retain the final ability to send; Mobile Agent shall not claim a message was sent just because a compose screen or intent was opened.

The application shall request contacts permission only when this feature is invoked, explain why it is needed, and continue to support manual contact selection or user-provided recipient details when permission is denied, where Android permits. Contact data shall be sent to DeepSeek only when included in a user-submitted prompt.

**Evidence:** `MainActivity.java:222-229,313-327`; `AndroidManifest.xml:1-22` currently declares no contacts permission.

### FR-010: Capture a photo

**Status: Gap.** The chat screen shall provide an action to capture a new photo using the device's camera capability. After capture, the application shall show that a photo is ready to attach, permit the user to discard it, and include it only with the next submitted prompt. The application shall handle user cancellation, camera unavailability, capture failure, and storage/permission failure without losing the chat draft.

A photo shall not be uploaded merely because the camera was opened or a capture completed. It is sent to DeepSeek only when the user submits a message with the captured image attached. If capture is performed by another camera application, that application controls its own capture UI and permissions.

### FR-011: Agent files and notes view

**Status: Gap.** The application shall provide a third top-level view, in addition to chat and API settings, where the user can browse and open files and directories in the agent workspace. It shall display actual stored content and shall not display example or generated file entries that do not exist.

The view shall support agent-maintained notes and skill documents, including conventional names such as `user.md`, `soul.md`, and a `skills/` directory when those items exist. The workspace files shall remain available after the app process closes. Any model-proposed write or replacement to these files shall follow [FR-012](#fr-012-explicit-action-approval). The view shall report unreadable, missing, or inaccessible files without presenting stale content as current.

Whether workspace notes are automatically added to model context, and how skills are selected or loaded, are not specified by the draft; see [OD-003](#od-003-agent-memory-and-skill-loading).

### FR-012: Explicit action approval

**Status: Existing for current tools; extended requirement.** Before executing any model-proposed file write, file read outside the user-selected attachment flow, command, intent, or other state-changing local action, the application shall show a review prompt with Allow and Deny controls. The prompt shall contain enough detail for the user to understand the specific action and its target. Deny, dismissal, or an invalid request shall not execute it.

For a sequence of proposed actions, each action shall be independently reviewable; approval of one shall not authorize the remainder. Repeated taps or delayed callbacks shall not execute an action more than once. Following execution, the application shall report the tool result separately from the model's narrative.

**Evidence:** `MainActivity.java:407-460,463-538`; `AgentTools.java:129-135`; `AgentToolsTest.java:118-136`.

### FR-013: Calendar event draft

**Status: Existing.** After approval, the agent shall validate a non-empty title and a start/end time, then open a calendar application with a draft event. End time shall be later than start time. Relative dates shall use the current local date, time, and timezone in the prompt context. The calendar application, not Mobile Agent, commits the final event.

**Evidence:** `MainActivity.java:631-641,559-576`; `AgentTools.java:113-127`.

### FR-014: Failure reporting and recovery

**Status: Existing in part; extended to new tools.** When a provider, picker, camera, contact query, file operation, command, or intent cannot complete, the application shall report the failure and shall not report success. It shall keep the user able to continue or retry where safe. If the application cannot determine whether an external action completed, it shall state that the outcome is unknown rather than retrying a potentially duplicative action automatically.

## 5. Information, privacy, and permissions

### 5.1 Data sent to the model

Chat text and any context the user attaches and submits—including image bytes, text-file contents, and contact details—are transmitted to DeepSeek to generate a response. The interface shall make the selected context visible before submission. The application shall not send a selected item merely because it was selected. The DeepSeek API key is sent to the configured endpoint as an authorization credential and must not be included in chat content or tool output.

Conversation persistence is not required by the draft. The current prototype holds conversation state in the activity and does not implement a conversation-history store. The product shall not claim that chat history is retained across process restarts unless persistence is separately specified and implemented.

### 5.2 Local data and permissions

The default workspace is user-visible shared storage, not the current app-private `files/workspace`. Access to shared or otherwise restricted storage shall follow the Android permission and user-grant model appropriate to the device's API level. Contacts access shall be permission-gated. Camera use shall be permission-gated if the application itself accesses the camera; a delegated camera application retains its own permission flow.

Root is an optional device capability, not a permission the application can assume. An `su` request requires an explicit user-approved command and any root-manager approval. The application shall explain when the device or OS denies access.

### 5.3 Sensitive actions

Reading, writing, or executing a command against an absolute path outside the default workspace is sensitive and requires a prompt that shows the exact path. Read and command approvals shall disclose when their output will be returned to DeepSeek. Intents that carry recipient, message, file, or other user data shall display those values before launch. The application shall use the least data needed for the action and shall not log API keys or full private file/contact contents as diagnostic output.

## 6. Non-functional requirements

### NFR-001: Platform compatibility

The application shall support Android 8.0 (API 26) or later, matching the current minimum SDK. Storage, intent, camera, contact-permission, and keyboard behavior shall be tested on representative supported API levels, including the current target SDK of 35.

**Evidence:** `app/build.gradle.kts:5-15`.

### NFR-002: Safety and user control

No denied or unapproved model-proposed action may execute. The user must be able to understand the command, path, recipient, intent target, and data payload before approving the relevant action. Platform permission enforcement must not be bypassed.

### NFR-003: Data bounds and responsiveness

The application shall keep network I/O off the UI thread, give the user visible progress while an operation is pending, and restore an interactive state after success or failure. Existing image, text-file, tool-output, and request time limits remain current prototype behavior until the product explicitly revises them. New limits must be documented and tested rather than silently truncating data.

### NFR-004: Recoverability

A rejected permission, missing handler, canceled picker/camera, denied root request, malformed model tool call, or interrupted operation shall leave the user able to return to chat. A partial write shall not be reported as a successful complete write.

### NFR-005: Testability

Each functional requirement shall have at least one reproducible acceptance test. Unit tests shall cover path validation, command policy, date validation, approval gating, and other deterministic logic; device-level tests shall cover Android permissions, intents, camera handoff, IME layout, storage grants, and external-app behavior.

## 7. Acceptance test cases

These cases are acceptance criteria for the target FSD. Existing unit tests are noted where they provide partial coverage; passing them does not establish device-level acceptance.

### TC-001: API setup and chat

Launch without an API key; verify settings is shown. Enter a key and submit a prompt; verify a request reaches the configured DeepSeek endpoint and the response appears. Close/restart the app process; verify the key is not restored from persistent app storage and settings is requested again. Simulate a failed request and verify a clear error with no false success.

### TC-002: Keyboard-visible composer

Focus the prompt on a device with the software keyboard. Verify the prompt and Send control remain above the keyboard while the transcript resizes or scrolls. Repeat after changing keyboard height and screen orientation where supported.

### TC-003: Attachment selection and disclosure

Select a text file, image, and contact using Android pickers. Verify each queued item is shown, can be cleared, and is not transmitted before Send. Submit a prompt and verify the selected context is included once. Verify an oversized or unsupported item is rejected with a visible error.

### TC-004: Default workspace and ordinary file operations

On a supported device, verify the application resolves the default workspace to `/storage/emulated/0/mobileagent`, creates it only with an applicable grant, and can list, read, create, and replace a test text file there. Revoke the grant and verify access fails clearly without silently using a private workspace. Verify inaccessible paths are not modified.

### TC-005: Path containment and failed writes

Attempt empty, malformed, unauthorized, and traversal paths. Verify no file outside the approved target is read or changed. Deny a write approval and verify the target file remains unchanged. Simulate a failed write and verify the UI reports failure rather than success.

### TC-006: Command execution and root handling

Propose a harmless local command and verify the approval shows the exact command, arguments, directory, and privilege level. Deny it and verify it does not run. Approve it and verify status/output are returned. On a rooted test device, verify an `su` command is not attempted until approved and that root-manager denial is reported. On a non-rooted device, verify the application reports ordinary sandbox permission failures rather than claiming root access.

### TC-007: Intent dispatch

Propose a supported calendar intent and verify the approval opens a draft without saving it. Test another installed handler, multiple handlers, no handler, invalid data, and platform permission denial. Verify intent fields are shown before approval and no intent is dispatched after Deny.

### TC-008: Contact lookup and message preparation

Grant contacts access and search a name with zero, one, and multiple matches. Verify multiple matches require user selection. Prepare an SMS or other installed-app message and verify recipient and body are shown before launch. Verify the target application retains the final Send action. Deny contacts permission and verify the app continues without silently accessing contacts.

### TC-009: Camera capture

Capture an image, cancel a capture, and test with no camera handler. Verify successful capture is shown as pending, can be discarded, and is sent only after the user submits a message. Verify cancel/failure does not clear unrelated chat text.

### TC-010: Agent-files view

Create workspace documents named `user.md`, `soul.md`, and files under `skills/`. Verify the third view lists and displays actual contents, persists after process restart, and reports missing or unreadable files accurately. Verify a model-proposed write is approval-gated.

### TC-011: Approval and action sequencing

Return multiple tool proposals. Verify each has an independent approval. Deny the first and allow the next; verify only the allowed action runs. Double-tap Allow or deliver a delayed callback; verify the action executes at most once. Verify unsupported or malformed tools do not cause side effects.

### TC-012: External action uncertainty

Simulate an external application closing or returning an ambiguous result after an intent launch. Verify Mobile Agent reports that launch/result status accurately and does not automatically resubmit a potentially duplicative action.

## 8. Implementation gaps and open decisions

### 8.1 Known implementation gaps

| Area | Target requirement | Current evidence | Status |
|---|---|---|---|
| Shared workspace | `/storage/emulated/0/mobileagent` | `AgentTools.workspaceDirectory()` creates app-private `files/workspace` | Gap; see [FR-005](#fr-005-default-user-visible-workspace) |
| General filesystem | Read/write/list at user-authorized locations | Create-only text files in the private workspace, 16 KiB maximum | Gap; see [FR-006](#fr-006-read-write-and-list-device-files) |
| Commands | Arbitrary device command, optional approved `su` | Four read-only commands in private workspace | Gap; see [FR-007](#fr-007-run-local-device-commands) |
| Intents | General Android intent execution | Calendar event intent only | Gap; see [FR-008](#fr-008-create-and-execute-android-intents) |
| Contacts | Search all matching contacts and disambiguate | User picks one phone contact | Gap; see [FR-009](#fr-009-search-contacts-and-prepare-a-message) |
| Camera | Capture new image for prompt | Existing image picker only | Gap; see [FR-010](#fr-010-capture-a-photo) |
| Navigation/files | Third view for agent-maintained workspace files | Settings and chat screens; no file browser evidenced | Gap; see [FR-011](#fr-011-agent-files-and-notes-view) |
| IME layout | Composer stays visible above keyboard | Manifest uses `adjustResize`; explicit layout acceptance is not evidenced | Validation required; see [FR-003](#fr-003-chat-composer-and-keyboard-layout) |

### OD-001: Storage access and root boundary

- **Owner:** Product owner and Android implementer.
- **Decision needed:** Specify which paths must be accessible on each supported Android version, how shared-storage access is requested, and whether rooted devices must expose paths beyond Android's ordinary app permissions. `/storage/emulated/0/mobileagent` is the required default, but this FSD does not assume that granting access to shared storage also permits access to other applications' private data.
- **Evidence to close:** Verify the chosen mechanism on API 26, API 29, and the current target API 35, with both ordinary and restricted paths; document unsupported paths and user-visible permission flows.

### OD-002: Command execution policy

- **Owner:** Product owner.
- **Decision needed:** Set command duration, output-size limits, cancellation behavior, environment/working-directory policy, and whether commands requiring interactive input are supported. Confirm whether shell syntax is part of the user-visible contract. The draft requires arbitrary local commands and optional `su`; the current 5-second timeout and 8 KiB output bound are prototype implementation values, not yet accepted target limits.
- **Evidence to close:** Test harmless representative commands, timeout and cancellation, output truncation, non-root behavior, and root-manager denial on a rooted test device.

### OD-003: Agent memory and skill loading

- **Owner:** Product owner.
- **Decision needed:** Decide whether `user.md`, `soul.md`, and skill files are only persistent files available on demand, or whether selected contents are automatically loaded into model context; define when the model may update memory and how skill files are selected. No automatic upload or execution of workspace content is implied until this is decided.
- **Evidence to close:** Review a user-visible flow showing what content is read locally, what is sent to DeepSeek, and what approval is required for writes.

### OD-004: Intent scope and recipient confirmation

- **Owner:** Product owner and Android implementer.
- **Decision needed:** Confirm which intent dispatch classes are supported and how the approval preview represents complex extras, unresolved recipients, and intents whose effects cannot be predicted. The requirement remains “any intent that Android permits”; this does not imply bypassing Android restrictions or silently sending messages.
- **Evidence to close:** Exercise supported intents against installed and missing handlers and verify approval fidelity and external-app handoff.

## 9. Requirement index

| ID | Requirement | Acceptance coverage |
|---|---|---|
| [FR-001](#fr-001-api-key-and-provider-configuration) | API key and provider configuration | [TC-001](#tc-001-api-setup-and-chat) |
| [FR-002](#fr-002-chat-and-model-interaction) | Chat and model interaction | [TC-001](#tc-001-api-setup-and-chat), [TC-011](#tc-011-approval-and-action-sequencing) |
| [FR-003](#fr-003-chat-composer-and-keyboard-layout) | Chat composer and keyboard layout | [TC-002](#tc-002-keyboard-visible-composer) |
| [FR-004](#fr-004-select-and-attach-input-context) | Select and attach input context | [TC-003](#tc-003-attachment-selection-and-disclosure) |
| [FR-005](#fr-005-default-user-visible-workspace) | Default user-visible workspace | [TC-004](#tc-004-default-workspace-and-ordinary-file-operations) |
| [FR-006](#fr-006-read-write-and-list-device-files) | Read, write, and list device files | [TC-004](#tc-004-default-workspace-and-ordinary-file-operations), [TC-005](#tc-005-path-containment-and-failed-writes) |
| [FR-007](#fr-007-run-local-device-commands) | Run local device commands | [TC-006](#tc-006-command-execution-and-root-handling) |
| [FR-008](#fr-008-create-and-execute-android-intents) | Create and execute Android intents | [TC-007](#tc-007-intent-dispatch), [TC-012](#tc-012-external-action-uncertainty) |
| [FR-009](#fr-009-search-contacts-and-prepare-a-message) | Search contacts and prepare a message | [TC-008](#tc-008-contact-lookup-and-message-preparation) |
| [FR-010](#fr-010-capture-a-photo) | Capture a photo | [TC-009](#tc-009-camera-capture) |
| [FR-011](#fr-011-agent-files-and-notes-view) | Agent files and notes view | [TC-010](#tc-010-agent-files-view) |
| [FR-012](#fr-012-explicit-action-approval) | Explicit action approval | [TC-005](#tc-005-path-containment-and-failed-writes), [TC-006](#tc-006-command-execution-and-root-handling), [TC-011](#tc-011-approval-and-action-sequencing) |
| [FR-013](#fr-013-calendar-event-draft) | Calendar event draft | [TC-007](#tc-007-intent-dispatch) |
| [FR-014](#fr-014-failure-reporting-and-recovery) | Failure reporting and recovery | [TC-001](#tc-001-api-setup-and-chat), [TC-008](#tc-008-contact-lookup-and-message-preparation), [TC-009](#tc-009-camera-capture), [TC-012](#tc-012-external-action-uncertainty) |
| [NFR-001](#nfr-001-platform-compatibility) | Platform compatibility | Device matrix for TC-002–TC-010 |
| [NFR-002](#nfr-002-safety-and-user-control) | Safety and user control | [TC-005](#tc-005-path-containment-and-failed-writes), [TC-006](#tc-006-command-execution-and-root-handling), [TC-007](#tc-007-intent-dispatch), [TC-011](#tc-011-approval-and-action-sequencing) |
| [NFR-003](#nfr-003-data-bounds-and-responsiveness) | Data bounds and responsiveness | [TC-001](#tc-001-api-setup-and-chat), [TC-003](#tc-003-attachment-selection-and-disclosure) |
| [NFR-004](#nfr-004-recoverability) | Recoverability | [TC-001](#tc-001-api-setup-and-chat), [TC-008](#tc-008-contact-lookup-and-message-preparation), [TC-009](#tc-009-camera-capture), [TC-012](#tc-012-external-action-uncertainty) |
| [NFR-005](#nfr-005-testability) | Testability | All acceptance cases |

## 10. Evidence sources

This FSD was derived from the user-provided draft `fsd-draft.md` and checked against the repository at provenance commit `a97e80d0a613e9996df009e41eba620b9f60e319`.

- `README.md:3-16` — prototype purpose, current features and explicit safety/privacy limits.
- `app/src/main/AndroidManifest.xml:1-22` — declared permissions, activities, and keyboard resize mode.
- `app/build.gradle.kts:5-15` — Android SDK levels and app version.
- `app/src/main/java/com/maximiliankraft/mobileagent/MainActivity.java:46-85` — startup and key requirement; `:130-310` — composer and attachments; `:329-405` — model API flow; `:407-576` — approval, local tools, and calendar; `:586-640` — model tool definitions and prompt.
- `app/src/main/java/com/maximiliankraft/mobileagent/AgentTools.java:17-110` — private workspace, file creation, command allowlist and execution limits; `:113-135` — calendar validation and approval gate.
- `app/src/main/java/com/maximiliankraft/mobileagent/SettingsActivity.java:33-71` and `MobileAgentApplication.java:5-15` — API key entry and in-memory storage.
- `app/src/test/java/com/maximiliankraft/mobileagent/AgentToolsTest.java:21-161` — current JVM unit-test coverage.
- `.github/workflows/android-tests.yml:11-44` — CI unit-test and debug-APK build steps.

### Local test execution note (informative)

A host Android SDK is not required when using the repository's container builder. From the repository root, run:

```sh
sudo nerdctl compose run --build --interactive=false --entrypoint ./gradlew android-builder testDebugUnitTest
```

The `Dockerfile` installs the Android SDK in the builder image. The command above completed successfully for `testDebugUnitTest` in the local container environment. `--interactive=false` is needed for non-TTY runs; otherwise nerdctl may fail with “provided file is not a console.”

The Compose service's default command is different: `sudo nerdctl compose up` builds the release APK and copies it to `dist/mobile-agent-release.apk`, replacing any existing file at that path. Use the test command above when only running unit tests.
