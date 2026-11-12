package com.maximiliankraft.mobileagent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.net.Uri;
import android.util.Base64;
import android.view.inputmethod.EditorInfo;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PAGE_BACKGROUND = Color.rgb(246, 247, 249);
    private static final int INK = Color.rgb(29, 34, 43);
    private static final int MUTED = Color.rgb(100, 108, 120);
    private static final int ACCENT = Color.rgb(54, 91, 214);
    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    private static final String MODEL = "deepseek-chat";
    private static final int MAX_API_TURNS = 8;
    private static final int MAX_TOOL_CALLS_PER_RESPONSE = 4;
    private static final int PICK_IMAGE = 1;
    private static final int PICK_FILE = 2;
    private static final int PICK_CONTACT = 3;
    private static final int EDIT_API_KEY = 4;
    private static final int PICK_CAMERA = 5;
    private static final int REQUEST_STORAGE_ACCESS = 6;
    private static final int REQUEST_LEGACY_STORAGE = 7;
    private static final int REQUEST_CONTACTS = 8;
    private static final int MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024;
    private static final int MAX_TEXT_FILE_BYTES = 32 * 1024;
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor();

    private final List<JSONObject> conversation = new ArrayList<>();
    private final List<String> pendingAttachmentLabels = new ArrayList<>();
    private LinearLayout messages;
    private LinearLayout loadingIndicator;
    private EditText messageInput;
    private ScrollView messageScroll;
    private Button sendButton;
    private TextView attachmentStatus;
    private String apiKey;
    private String pendingImageDataUrl;
    private File pendingCameraFile;
    private Uri pendingCameraUri;
    private String pendingContext = "";
    private boolean busy;
    private int apiTurnsRemaining;
    private Runnable contactPermissionGranted;
    private Runnable contactPermissionDenied;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        apiKey = ((MobileAgentApplication) getApplication()).getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
            return;
        }
        conversation.add(systemMessage());
        LinearLayout content = createContent();
        setContentView(content);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            content.requestApplyInsets();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private LinearLayout createContent() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(20), dp(20), dp(16));
        page.setBackgroundColor(PAGE_BACKGROUND);
        configureKeyboardInsets(page);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = text("Mobile Agent", 26, INK, true);
        titleRow.addView(heading, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button settingsButton = new Button(this);
        settingsButton.setText("API key");
        settingsButton.setAllCaps(false);
        titleRow.addView(settingsButton);
        settingsButton.setOnClickListener(view ->
                startActivityForResult(new Intent(this, SettingsActivity.class)
                        .putExtra("return_to_chat", true), EDIT_API_KEY));
        page.addView(titleRow);
        Button filesButton = new Button(this);
        filesButton.setText("Agent files");
        filesButton.setAllCaps(false);
        filesButton.setOnClickListener(view ->
                startActivity(new Intent(this, FilesActivity.class)));
        titleRow.addView(filesButton);
        TextView subtitle = text("DeepSeek · actions always need your approval", 14, MUTED, false);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        page.addView(subtitle);

        messageScroll = new ScrollView(this);
        messageScroll.setFillViewport(true);
        messageScroll.setBackground(cardBackground(Color.WHITE, dp(16)));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        scrollParams.setMargins(0, dp(12), 0, dp(12));
        page.addView(messageScroll, scrollParams);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(dp(14), dp(14), dp(14), dp(14));
        messageScroll.addView(messages);
        addMessage("assistant",
                "Ask me to create a file, run a safe read-only command, or draft a calendar event. I will show each action and ask before doing it.");

        LinearLayout attachmentActions = new LinearLayout(this);
        attachmentActions.setGravity(Gravity.CENTER_VERTICAL);
        Button imageButton = attachmentButton("Image", () -> chooseContent(PICK_IMAGE));
        Button cameraButton = attachmentButton("Camera", this::capturePhoto);
        Button fileButton = attachmentButton("File", () -> chooseContent(PICK_FILE));
        Button contactButton = attachmentButton("Contact", this::chooseContact);
        attachmentActions.addView(imageButton);
        attachmentActions.addView(cameraButton);
        attachmentActions.addView(fileButton);
        attachmentActions.addView(contactButton);
        page.addView(attachmentActions);
        attachmentStatus = text("", 12, MUTED, false);
        page.addView(attachmentStatus);
        Button clearAttachments = attachmentButton("Clear selected items", () -> {
            pendingImageDataUrl = null;
            pendingContext = "";
            pendingAttachmentLabels.clear();
            updateAttachmentStatus();
        });
        page.addView(clearAttachments);

        LinearLayout composer = new LinearLayout(this);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        messageInput = new EditText(this);
        messageInput.setHint("Ask Mobile Agent…");
        messageInput.setMinLines(1);
        messageInput.setMaxLines(4);
        messageInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        messageInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                page.requestApplyInsets();
            }
        });
        composer.addView(messageInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        sendButton = new Button(this);
        sendButton.setText("Send");
        sendButton.setAllCaps(false);
        composer.addView(sendButton);
        page.addView(composer);
        sendButton.setOnClickListener(view -> sendMessage());
        return page;
    }

    private void configureKeyboardInsets(LinearLayout page) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        getWindow().setDecorFitsSystemWindows(false);
        page.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsets.Type.systemBars());
            Insets ime = windowInsets.getInsets(WindowInsets.Type.ime());
            view.setPadding(dp(20) + systemBars.left, dp(20) + systemBars.top,
                    dp(20) + systemBars.right,
                    ChatInsets.bottomPadding(dp(16), systemBars.bottom, ime.bottom));
            return windowInsets;
        });
    }

    private void sendMessage() {
        String content = messageInput.getText().toString().trim();
        if (content.isEmpty() || busy) {
            return;
        }
        if (!pendingContext.isEmpty()) {
            content += "\n\nSelected context:\n" + pendingContext;
        }
        JSONObject userMessage = message("user", content);
        if (pendingImageDataUrl != null) {
            try {
                JSONArray contentParts = new JSONArray()
                        .put(new JSONObject().put("type", "text").put("text", content))
                        .put(new JSONObject().put("type", "image_url")
                                .put("image_url", new JSONObject().put("url", pendingImageDataUrl)));
                userMessage.put("content", contentParts);
            } catch (JSONException exception) {
                showToast("Could not attach the selected image.");
                return;
            }
        }
        messageInput.setText("");
        conversation.add(userMessage);
        addMessage("user", content);
        if (pendingImageDataUrl != null) {
            addMessage("user", "Image attached.");
        }
        pendingImageDataUrl = null;
        pendingContext = "";
        pendingAttachmentLabels.clear();
        updateAttachmentStatus();
        busy = true;
        apiTurnsRemaining = MAX_API_TURNS;
        setComposerEnabled(false);
        requestAssistantResponse();
    }

    private Button attachmentButton(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void chooseContent(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(requestCode == PICK_IMAGE ? "image/*" : "text/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException exception) {
            showToast("No file picker is available.");
        }
    }

    private void chooseContact() {
        Intent intent = new Intent(Intent.ACTION_PICK,
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        try {
            startActivityForResult(intent, PICK_CONTACT);
        } catch (ActivityNotFoundException exception) {
            showToast("No contacts app is available.");
        }
    }

    private void capturePhoto() {
        try {
            File pictures = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (pictures == null || (!pictures.exists() && !pictures.mkdirs())) {
                throw new IOException("App-specific photo storage is unavailable.");
            }
            File outputFile = File.createTempFile("capture-", ".jpg", pictures);
            Uri outputUri = new Uri.Builder()
                    .scheme(ContentResolver.SCHEME_CONTENT)
                    .authority(getPackageName() + ".capture")
                    .appendPath("capture")
                    .appendPath(outputFile.getName())
                    .build();
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("Mobile Agent photo", outputUri));
            pendingCameraFile = outputFile;
            pendingCameraUri = outputUri;
            startActivityForResult(intent, PICK_CAMERA);
        } catch (ActivityNotFoundException exception) {
            deletePendingCameraFile();
            showToast("No camera app is available.");
        } catch (IOException exception) {
            deletePendingCameraFile();
            showToast("Could not prepare camera capture: " + safeError(exception.getMessage()));
        } catch (Exception exception) {
            deletePendingCameraFile();
            showToast("Could not start camera capture: " + safeError(exception.getMessage()));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == EDIT_API_KEY) {
            apiKey = ((MobileAgentApplication) getApplication()).getApiKey();
            return;
        }
        if (requestCode == REQUEST_STORAGE_ACCESS || requestCode == REQUEST_LEGACY_STORAGE) {
            return;
        }
        if (requestCode == PICK_CAMERA) {
            File capturedFile = pendingCameraFile;
            Uri capturedUri = pendingCameraUri;
            pendingCameraFile = null;
            pendingCameraUri = null;
            if (resultCode != RESULT_OK) {
                deleteFile(capturedFile);
                showToast("Photo capture was canceled. Your prompt is unchanged.");
                return;
            }
            try {
                if (capturedUri == null || capturedFile == null || !capturedFile.isFile()
                        || capturedFile.length() == 0) {
                    throw new IOException("The camera did not write a photo.");
                }
                byte[] imageBytes = readUriBytes(capturedUri, MAX_ATTACHMENT_BYTES);
                clearPendingImageLabel();
                pendingImageDataUrl = "data:image/jpeg;base64,"
                        + Base64.encodeToString(imageBytes, Base64.NO_WRAP);
                pendingAttachmentLabels.add("Camera photo");
                updateAttachmentStatus();
                showToast("Photo ready. Add a prompt and send it.");
            } catch (Exception exception) {
                showToast("Photo capture failed: " + safeError(exception.getMessage())
                        + " Your prompt is unchanged.");
            } finally {
                deleteFile(capturedFile);
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            if (requestCode == PICK_IMAGE) {
                String mimeType = getContentResolver().getType(uri);
                if (mimeType == null || !mimeType.startsWith("image/")) {
                    showToast("The selected file is not an image.");
                    return;
                }
                byte[] imageBytes = readUriBytes(uri, MAX_ATTACHMENT_BYTES);
                clearPendingImageLabel();
                pendingImageDataUrl = "data:" + mimeType + ";base64,"
                        + Base64.encodeToString(imageBytes, Base64.NO_WRAP);
                pendingAttachmentLabels.add("Image: " + uri.getLastPathSegment());
                updateAttachmentStatus();
                showToast("Image attached. Add a prompt and send it.");
            } else if (requestCode == PICK_FILE) {
                String fileText = new String(readUriBytes(uri, MAX_TEXT_FILE_BYTES),
                        StandardCharsets.UTF_8);
                pendingContext += "\nSelected text file (" + uri.getLastPathSegment()
                        + "):\n" + fileText;
                pendingAttachmentLabels.add("Text file: " + uri.getLastPathSegment());
                updateAttachmentStatus();
                showToast("File attached to the next prompt.");
            } else if (requestCode == PICK_CONTACT) {
                String contact = readContact(uri);
                pendingContext += "\nSelected contact:\n" + contact;
                pendingAttachmentLabels.add("Contact: " + contact.split("\\n", 2)[0]);
                updateAttachmentStatus();
                showToast("Contact added to the next prompt.");
            }
        } catch (Exception exception) {
            showToast("Could not read the selection: " + safeError(exception.getMessage()));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CONTACTS) {
            Runnable continuation = grantResults.length > 0 && grantResults[0]
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ? contactPermissionGranted : contactPermissionDenied;
            contactPermissionGranted = null;
            contactPermissionDenied = null;
            if (continuation != null) {
                continuation.run();
            }
        } else if (requestCode == REQUEST_LEGACY_STORAGE
                && !hasSharedStorageAccess()) {
            showToast("Shared-storage access was not granted; retry the action after granting access.");
        }
    }

    private void updateAttachmentStatus() {
        if (attachmentStatus == null) {
            return;
        }
        StringBuilder status = new StringBuilder();
        for (String label : pendingAttachmentLabels) {
            if (status.length() > 0) {
                status.append(" · ");
            }
            status.append(label);
        }
        if (status.length() > 0) {
            status.append(" — sent with your next message");
        }
        attachmentStatus.setText(status.toString());
    }

    private void clearPendingImageLabel() {
        for (int i = pendingAttachmentLabels.size() - 1; i >= 0; i--) {
            String label = pendingAttachmentLabels.get(i);
            if (label.startsWith("Image:") || "Camera photo".equals(label)) {
                pendingAttachmentLabels.remove(i);
            }
        }
    }

    private void deletePendingCameraFile() {
        deleteFile(pendingCameraFile);
        pendingCameraFile = null;
        pendingCameraUri = null;
    }

    private void deleteFile(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    private byte[] readUriBytes(Uri uri, int maximumBytes) throws IOException {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            throw new IOException("Only content-provider files selected through Android are supported.");
        }
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IOException("File could not be opened.");
            }
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > maximumBytes) {
                    throw new IOException("Selection exceeds the size limit.");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private String readContact(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{ContactsContract.Contacts.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return "No contact details were available.";
            }
            int nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME);
            int phoneIndex = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.NUMBER);
            return "Name: " + cursor.getString(nameIndex)
                    + "\nPhone: " + cursor.getString(phoneIndex);
        }
    }

    private void requestAssistantResponse() {
        if (apiTurnsRemaining-- <= 0) {
            finishAssistantTurn("Stopped after the tool-call limit. Send another message to continue.");
            return;
        }
        showLoadingIndicator();
        IO_EXECUTOR.execute(() -> {
            try {
                conversation.get(0).put("content", systemPrompt());
                JSONObject request = new JSONObject()
                        .put("model", MODEL)
                        .put("messages", new JSONArray(conversation))
                        .put("tools", toolDefinitions())
                        .put("tool_choice", "auto")
                        .put("stream", false);
                JSONObject response = postToDeepSeek(request);
                JSONObject assistantMessage = response.getJSONArray("choices")
                        .getJSONObject(0).getJSONObject("message");
                runOnUiThread(() -> handleAssistantResponse(assistantMessage));
            } catch (Exception exception) {
                runOnUiThread(() -> finishAssistantTurn(
                        "DeepSeek request failed: " + safeError(exception.getMessage())));
            }
        });
    }

    private JSONObject postToDeepSeek(JSONObject request) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }
        int status = connection.getResponseCode();
        InputStream responseStream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        if (responseStream == null) {
            connection.disconnect();
            throw new IOException("DeepSeek returned HTTP " + status + ".");
        }
        StringBuilder responseBody = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(responseStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                responseBody.append(line);
                if (responseBody.length() > 1024 * 1024) {
                    throw new IOException("DeepSeek response was too large.");
                }
            }
        } finally {
            connection.disconnect();
        }
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + ": " + responseBody);
        }
        return new JSONObject(responseBody.toString());
    }

    private void handleAssistantResponse(JSONObject assistantMessage) {
        hideLoadingIndicator();
        conversation.add(assistantMessage);
        JSONArray toolCalls = assistantMessage.optJSONArray("tool_calls");
        if (toolCalls != null && toolCalls.length() > 0) {
            if (toolCalls.length() > MAX_TOOL_CALLS_PER_RESPONSE) {
                finishAssistantTurn("The response requested too many actions. Please try a smaller request.");
                return;
            }
            processToolCall(assistantMessage, toolCalls, 0);
            return;
        }
        String answer = assistantMessage.optString("content", "").trim();
        finishAssistantTurn(answer.isEmpty() ? "DeepSeek returned an empty response." : answer);
    }

    private void processToolCall(JSONObject assistantMessage, JSONArray calls, int index) {
        if (index >= calls.length()) {
            requestAssistantResponse();
            return;
        }
        JSONObject call = calls.optJSONObject(index);
        if (call == null) {
            appendToolResult("unknown", "Invalid tool call.");
            processToolCall(assistantMessage, calls, index + 1);
            return;
        }
        String callId = call.optString("id", "");
        JSONObject function = call.optJSONObject("function");
        String name = function == null ? "" : function.optString("name", "");
        JSONObject arguments;
        try {
            arguments = function == null
                    ? new JSONObject() : new JSONObject(function.optString("arguments", "{}"));
        } catch (JSONException exception) {
            appendToolResult(callId, "Invalid JSON arguments; no action was taken.");
            processToolCall(assistantMessage, calls, index + 1);
            return;
        }

        if ("get_current_datetime".equals(name)) {
            appendToolResult(callId, ZonedDateTime.now().toString());
            processToolCall(assistantMessage, calls, index + 1);
            return;
        }
        if (!isSupportedTool(name)) {
            appendToolResult(callId, "Unsupported tool; no action was taken.");
            processToolCall(assistantMessage, calls, index + 1);
            return;
        }
        if ("run_command".equals(name)) {
            try {
                JSONArray suppliedArgs = arguments.getJSONArray("args");
                List<String> commandArgs = new ArrayList<>();
                for (int i = 0; i < suppliedArgs.length(); i++) {
                    commandArgs.add(suppliedArgs.getString(i));
                }
                String warning = AgentTools.commandPolicyWarning(
                        arguments.getString("command"), commandArgs);
                if (warning != null) {
                    appendToolResult(callId, warning);
                    processToolCall(assistantMessage, calls, index + 1);
                    return;
                }
            } catch (JSONException exception) {
                String warning = "Warning: invalid command arguments; no command was executed.";
                appendToolResult(callId, warning);
                processToolCall(assistantMessage, calls, index + 1);
                return;
            }
        }
        showToolApproval(name, arguments, approved -> {
            if (approved && "search_contacts".equals(name)
                    && checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestContactsPermission(callId, assistantMessage, calls, index, arguments);
                return;
            }
            if (approved && requiresSharedStorage(name) && !hasSharedStorageAccess()) {
                new AlertDialog.Builder(this)
                        .setTitle("Device storage access required")
                        .setMessage("Mobile Agent needs Android storage access for the requested "
                                + "path. Grant all-files access in Android settings, then retry. "
                                + "Android may still deny protected system and other-app private "
                                + "data; this app does not bypass those restrictions.")
                        .setNegativeButton("Cancel", (dialog, which) -> {
                            appendToolResult(callId, "Shared-storage access was not granted; no file was read or changed.");
                            processToolCall(assistantMessage, calls, index + 1);
                        })
                        .setPositiveButton("Open settings", (dialog, which) -> {
                            openSharedStorageSettings();
                            appendToolResult(callId, "Grant shared-storage access in Android settings, then retry this action.");
                            processToolCall(assistantMessage, calls, index + 1);
                        })
                        .setOnCancelListener(dialog -> {
                            appendToolResult(callId, "Shared-storage access was not granted; no file was read or changed.");
                            processToolCall(assistantMessage, calls, index + 1);
                        })
                        .show();
                return;
            }
            boolean started = AgentTools.runApprovedAction(approved, () -> {
                if ("open_calendar".equals(name) || "launch_intent".equals(name)
                        || "prepare_message".equals(name)) {
                    String result = "open_calendar".equals(name)
                            ? executeCalendarTool(arguments)
                            : "launch_intent".equals(name)
                            ? executeIntentTool(arguments) : executeMessageTool(arguments);
                    appendToolResult(callId, result);
                    processToolCall(assistantMessage, calls, index + 1);
                    return;
                }
                IO_EXECUTOR.execute(() -> {
                    String result = executeLocalTool(name, arguments);
                    runOnUiThread(() -> {
                        appendToolResult(callId, result);
                        processToolCall(assistantMessage, calls, index + 1);
                    });
                });
            });
            if (!started) {
                appendToolResult(callId, "User declined this action; nothing was changed.");
                processToolCall(assistantMessage, calls, index + 1);
            }
        });
    }

    private void requestContactsPermission(String callId, JSONObject assistantMessage,
                                           JSONArray calls, int index, JSONObject arguments) {
        contactPermissionGranted = () -> executeApprovedContactSearch(callId,
                assistantMessage, calls, index, arguments);
        contactPermissionDenied = () -> {
            appendToolResult(callId, "Contacts permission was denied; no contacts were read.");
            processToolCall(assistantMessage, calls, index + 1);
        };
        new AlertDialog.Builder(this)
                .setTitle("Allow contact search?")
                .setMessage("Mobile Agent needs contacts access to find matching names. "
                        + "Names and phone numbers will be displayed only on this device and "
                        + "will not be sent to DeepSeek. You can deny access.")
                .setNegativeButton("Cancel", (dialog, which) -> contactPermissionDenied.run())
                .setPositiveButton("Continue", (dialog, which) -> requestPermissions(
                        new String[]{android.Manifest.permission.READ_CONTACTS}, REQUEST_CONTACTS))
                .setOnCancelListener(dialog -> contactPermissionDenied.run())
                .show();
    }

    private void executeApprovedContactSearch(String callId, JSONObject assistantMessage,
                                              JSONArray calls, int index, JSONObject arguments) {
        IO_EXECUTOR.execute(() -> {
            String localResults = executeLocalTool("search_contacts", arguments);
            String toolResult = AgentTools.contactSearchToolResult(localResults);
            runOnUiThread(() -> {
                if (localResults.startsWith("Contact matches (")) {
                    addMessage("assistant", "Contact search results (shown only on this device):\n"
                            + localResults);
                }
                appendToolResult(callId, toolResult);
                processToolCall(assistantMessage, calls, index + 1);
            });
        });
    }

    private void showToolApproval(String name, JSONObject arguments,
                                  ApprovalCallback callback) {
        String title;
        String details;
        try {
            switch (name) {
                case "create_file":
                case "write_file":
                    title = "Write or replace this file?";
                    details = "Path: " + displayToolPath(arguments)
                            + "\n\nContents:\n" + arguments.getString("content");
                    break;
                case "read_file":
                    title = "Read this file?";
                    details = "Path: " + displayToolPath(arguments)
                            + "\n\nThe file contents will be returned to DeepSeek as tool output.";
                    break;
                case "list_directory":
                    title = "List this directory?";
                    details = "Path: " + displayToolPath(arguments)
                            + "\n\nThe listing will be returned to DeepSeek as tool output.";
                    break;
                case "create_directory":
                    title = "Create this directory?";
                    details = "Path: " + arguments.getString("path");
                    break;
                case "run_command":
                    JSONArray args = arguments.getJSONArray("args");
                    List<String> command = new ArrayList<>();
                    command.add(arguments.getString("command"));
                    for (int i = 0; i < args.length(); i++) {
                        command.add(args.getString(i));
                    }
                    title = "Run this command?";
                    details = "Working directory: /storage/emulated/0/mobileagent"
                            + "\nOnly pwd, ls, cat, and wc are supported; no root or arbitrary shell."
                            + "\nExact command and arguments:\n" + new JSONArray(command)
                            + "\n\nCaptured output will be returned to DeepSeek as tool output.";
                    break;
                case "launch_intent":
                    title = "Launch this Android action?";
                    details = "Exact intent fields:\n" + arguments.toString(2)
                            + "\n\nNo intent is dispatched unless you tap Allow. Review all "
                            + "recipients and extras; an external app may perform an action "
                            + "that cannot be undone here. Launch does not confirm completion.";
                    break;
                case "prepare_message":
                    title = "Prepare this message?";
                    details = "Channel: " + arguments.getString("channel")
                            + "\nRecipient: " + arguments.getString("recipient")
                            + "\n\nMessage:\n" + arguments.getString("body")
                            + "\n\nThe messaging app will show a draft; it will not be sent automatically.";
                    break;
                case "search_contacts":
                    title = "Search device contacts?";
                    details = "Name query: " + arguments.getString("name")
                            + "\n\nMatching names and phone numbers will be displayed only "
                            + "on this device and will not be sent to DeepSeek.";
                    break;
                default:
                    title = "Open calendar with this event?";
                    details = "Title: " + arguments.getString("title")
                            + "\nStarts: " + arguments.getString("start")
                            + "\nEnds: " + arguments.getString("end")
                            + "\n\nYour calendar app will let you review it before saving.";
                    break;
            }
            LinearLayout approvalBubble = new LinearLayout(this);
            approvalBubble.setOrientation(LinearLayout.VERTICAL);
            approvalBubble.setPadding(dp(12), dp(10), dp(12), dp(10));
            approvalBubble.setBackground(cardBackground(Color.rgb(255, 246, 222), dp(12)));
            TextView titleText = text(title, 16, INK, true);
            approvalBubble.addView(titleText);
            TextView detailText = text(details, 15, INK, false);
            detailText.setTextIsSelectable(true);
            ScrollView detailScroller = new ScrollView(this);
            detailScroller.addView(detailText);
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            detailParams.topMargin = dp(8);
            approvalBubble.addView(detailScroller, detailParams);
            LinearLayout actions = new LinearLayout(this);
            actions.setGravity(Gravity.END);
            Button denyButton = new Button(this);
            denyButton.setText("Deny");
            denyButton.setAllCaps(false);
            Button allowButton = new Button(this);
            allowButton.setText("Allow");
            allowButton.setAllCaps(false);
            actions.addView(denyButton);
            actions.addView(allowButton);
            LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            actionsParams.topMargin = dp(6);
            approvalBubble.addView(actions, actionsParams);
            LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bubbleParams.setMargins(0, 0, 0, dp(10));
            messages.addView(approvalBubble, bubbleParams);
            messageScroll.post(() -> messageScroll.fullScroll(ScrollView.FOCUS_DOWN));
            denyButton.setOnClickListener(view -> {
                denyButton.setEnabled(false);
                allowButton.setEnabled(false);
                callback.onResult(false);
            });
            allowButton.setOnClickListener(view -> {
                denyButton.setEnabled(false);
                allowButton.setEnabled(false);
                callback.onResult(true);
            });
        } catch (JSONException exception) {
            callback.onResult(false);
        }
    }

    private String executeLocalTool(String name, JSONObject arguments) {
        try {
            if ("search_contacts".equals(name)) {
                return searchContacts(arguments.getString("name"));
            }
            File root = sharedStorageRoot();
            String path = arguments.optString("path", "");
            File workspace = "run_command".equals(name)
                    ? workspaceDirectory() : AgentTools.workspaceForPath(root, path);
            if ("create_file".equals(name) || "write_file".equals(name)) {
                return AgentTools.writeTextFile(root, workspace, path, arguments.getString("content"));
            }
            if ("read_file".equals(name)) {
                return AgentTools.readTextFile(root, workspace, path);
            }
            if ("list_directory".equals(name)) {
                return AgentTools.listDirectory(root, workspace, path);
            }
            if ("create_directory".equals(name)) {
                return AgentTools.createDirectory(root, workspace, arguments.getString("path"));
            }
            JSONArray suppliedArgs = arguments.getJSONArray("args");
            List<String> args = new ArrayList<>();
            for (int i = 0; i < suppliedArgs.length(); i++) {
                args.add(suppliedArgs.getString(i));
            }
            return AgentTools.runCommand(workspace, getCacheDir(),
                    arguments.getString("command"), args);
        } catch (Exception exception) {
            return "Action failed: " + safeError(exception.getMessage());
        }
    }

    private String searchContacts(String name) {
        String query = name.trim();
        if (query.isEmpty()) {
            return "No contact search performed: provide a name.";
        }
        StringBuilder matches = new StringBuilder();
        int count = 0;
        boolean truncated = false;
        try (Cursor cursor = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                new String[]{"%" + query + "%"},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            if (cursor == null) {
                return "Contacts provider returned no results.";
            }
            while (cursor.moveToNext()) {
                if (count == 20) {
                    truncated = true;
                    break;
                }
                if (matches.length() > 0) {
                    matches.append('\n');
                }
                matches.append("Name: ").append(cursor.getString(0))
                        .append("\nPhone: ").append(cursor.getString(1));
                count++;
            }
            if (count == 0) {
                return "No contacts matched that name.";
            }
            if (truncated) {
                matches.append("\nAdditional matches omitted (20-result limit).");
            }
            return "Contact matches (" + count + "):\n" + matches
                    + "\nAsk the user to select the intended person before preparing a message.";
        } catch (SecurityException exception) {
            return "Contacts permission is unavailable; no contacts were read.";
        }
    }

    private String executeMessageTool(JSONObject arguments) {
        try {
            String channel = arguments.getString("channel").trim()
                    .toLowerCase(java.util.Locale.ROOT);
            String recipient = arguments.getString("recipient").trim();
            String body = arguments.getString("body");
            if (recipient.isEmpty() || !recipient.matches("[+0-9(). -]{1,40}")
                    || body.trim().isEmpty() || body.length() > 2000) {
                return "Message not prepared: recipient and body are required (body limit 2,000 characters).";
            }
            Intent intent;
            if ("sms".equals(channel)) {
                intent = new Intent(Intent.ACTION_SENDTO,
                        Uri.fromParts("smsto", recipient, null));
                intent.putExtra("sms_body", body);
            } else if ("whatsapp".equals(channel)) {
                String digits = recipient.replaceAll("[^0-9]", "");
                if (digits.isEmpty()) {
                    return "Message not prepared: recipient has no phone-number digits.";
                }
                String packageName = installedPackage("com.whatsapp")
                        ? "com.whatsapp" : installedPackage("com.whatsapp.w4b")
                        ? "com.whatsapp.w4b" : null;
                if (packageName == null) {
                    return "WhatsApp is not installed.";
                }
                Uri uri = Uri.parse("https://wa.me/" + digits + "?text=" + Uri.encode(body));
                intent = new Intent(Intent.ACTION_VIEW, uri).setPackage(packageName);
            } else {
                return "Unsupported message channel. Use sms or whatsapp.";
            }
            startActivity(intent);
            return "Opened a message draft in the selected application. It has not been sent.";
        } catch (ActivityNotFoundException exception) {
            return "No installed application can prepare this message.";
        } catch (Exception exception) {
            return "Message preparation failed: " + safeError(exception.getMessage());
        }
    }

    private boolean installedPackage(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private String executeCalendarTool(JSONObject arguments) {
        try {
            AgentTools.CalendarEvent event = AgentTools.validateCalendarEvent(
                    arguments.getString("title"), arguments.getString("start"),
                    arguments.getString("end"));
            Intent intent = new Intent(Intent.ACTION_INSERT)
                    .setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, event.title)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.startMillis)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.endMillis);
            startActivity(intent);
            return "Opened a calendar app with the event. The user must still save it there.";
        } catch (ActivityNotFoundException exception) {
            return "No calendar app is available.";
        } catch (Exception exception) {
            return "Calendar action failed: " + safeError(exception.getMessage());
        }
    }

    private File workspaceDirectory() {
        try {
            return AgentTools.workspaceDirectory(sharedStorageRoot());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private File sharedStorageRoot() {
        return Environment.getExternalStorageDirectory();
    }

    private String displayToolPath(JSONObject arguments) {
        String path = arguments.optString("path", "");
        return path.trim().isEmpty() ? "/storage/emulated/0/mobileagent" : path;
    }

    private boolean requiresSharedStorage(String name) {
        return "create_file".equals(name) || "read_file".equals(name)
                || "write_file".equals(name) || "list_directory".equals(name)
                || "create_directory".equals(name) || "run_command".equals(name);
    }

    private boolean hasSharedStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void openSharedStorageSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivityForResult(settingsIntent, REQUEST_STORAGE_ACCESS);
            } catch (ActivityNotFoundException exception) {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                        REQUEST_STORAGE_ACCESS);
            }
        } else {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_LEGACY_STORAGE);
        }
    }

    private String executeIntentTool(JSONObject arguments) {
        try {
            JSONArray suppliedKeys = arguments.names();
            List<String> allowedKeys = java.util.Arrays.asList(
                    "action", "data", "mime_type", "package_name", "extras");
            for (int i = 0; suppliedKeys != null && i < suppliedKeys.length(); i++) {
                if (!allowedKeys.contains(suppliedKeys.getString(i))) {
                    return "Intent rejected: it contains an unsupported field.";
                }
            }
            String action = arguments.getString("action").trim();
            if (action.isEmpty()) {
                return "Intent rejected: action is required.";
            }
            Intent intent = new Intent(action);
            String data = arguments.optString("data", "");
            String mimeType = arguments.optString("mime_type", "");
            if (!data.isEmpty() && !mimeType.isEmpty()) {
                intent.setDataAndType(Uri.parse(data), mimeType);
            } else if (!data.isEmpty()) {
                intent.setData(Uri.parse(data));
            } else if (!mimeType.isEmpty()) {
                intent.setType(mimeType);
            }
            String packageName = arguments.optString("package_name", "");
            if (!packageName.isEmpty()) {
                intent.setPackage(packageName);
            }
            JSONObject extras = arguments.optJSONObject("extras");
            if (extras != null) {
                JSONArray keys = extras.names();
                for (int i = 0; keys != null && i < keys.length(); i++) {
                    String key = keys.getString(i);
                    Object value = extras.get(key);
                    if (!(value instanceof String)) {
                        return "Intent rejected: extras must contain text values only.";
                    }
                    intent.putExtra(key, (String) value);
                }
            }
            startActivity(intent);
            return "Android accepted the intent launch. The external action's outcome is unknown.";
        } catch (ActivityNotFoundException exception) {
            return "No eligible application can handle this intent.";
        } catch (Exception exception) {
            return "Intent failed: " + safeError(exception.getMessage());
        }
    }

    private JSONArray toolDefinitions() throws JSONException {
        JSONArray tools = new JSONArray();
        tools.put(tool("get_current_datetime", "Get the current local date, time, and timezone.",
                new JSONObject(), new JSONArray()));
        tools.put(tool("create_file", "Legacy alias: create or replace a UTF-8 text file at a device path accessible to this app, after approval.",
                new JSONObject()
                        .put("path", stringSchema("Optional path: relative paths use the agent workspace; an absolute path may target any location Android permits."))
                        .put("content", stringSchema("Complete UTF-8 file contents, at most 16 KiB.")),
                new JSONArray().put("path").put("content")));
        tools.put(tool("read_file", "Read a UTF-8 text file at a device path Android permits. Omitted path defaults to /storage/emulated/0/mobileagent. The exact path is shown for approval, and contents are returned to DeepSeek.",
                new JSONObject().put("path", stringSchema("Optional path: relative paths use the agent workspace; an absolute path may target any location Android permits.")),
                new JSONArray()));
        tools.put(tool("write_file", "Create or replace a UTF-8 text file at a device path Android permits, after approval. Omitted path defaults to the agent workspace.",
                new JSONObject()
                        .put("path", stringSchema("Optional path: relative paths use the agent workspace; an absolute path may target any location Android permits."))
                        .put("content", stringSchema("Complete UTF-8 contents, at most 16 KiB.")),
                new JSONArray().put("content")));
        tools.put(tool("list_directory", "List an actual directory at a device path Android permits. Omitted path defaults to the agent workspace.",
                new JSONObject().put("path", stringSchema("Optional path: relative paths use the agent workspace; an absolute path may target any location Android permits.")),
                new JSONArray()));
        tools.put(tool("create_directory", "Create a directory at a device path Android permits, after approval.",
                new JSONObject().put("path", stringSchema("Relative to the agent workspace, or an absolute path Android permits.")),
                new JSONArray().put("path")));
        tools.put(tool("search_contacts", "Find contacts by part of the name after user approval and Android contacts permission. Results are displayed locally only and are not sent to DeepSeek; ask the user to choose from the on-device results.",
                new JSONObject().put("name", stringSchema("Name or part of a name to search for.")),
                new JSONArray().put("name")));
        tools.put(tool("prepare_message", "Prepare an SMS or WhatsApp draft after approval; the external app retains the final Send action.",
                new JSONObject()
                        .put("channel", stringSchema("sms or whatsapp."))
                        .put("recipient", stringSchema("Selected recipient phone number."))
                        .put("body", stringSchema("Message text, at most 2,000 characters.")),
                new JSONArray().put("channel").put("recipient").put("body")));
        tools.put(tool("run_command", "Run a read-only command in the agent workspace. "
                        + "Unsupported commands or excessive arguments produce an in-chat warning and are not run.",
                new JSONObject()
                        .put("command", stringSchema("Supported commands: pwd, ls, cat, and wc."))
                        .put("args", new JSONObject().put("type", "array")
                                .put("items", stringSchema("Workspace-relative path when required."))
                                .put("maxItems", 16)),
                new JSONArray().put("command").put("args")));
        tools.put(tool("open_calendar", "Open the calendar app with a proposed event after user approval.",
                new JSONObject()
                        .put("title", stringSchema("Event title, at most 120 characters."))
                        .put("start", stringSchema("Start timestamp in ISO-8601 UTC format."))
                        .put("end", stringSchema("End timestamp in ISO-8601 UTC format.")),
                new JSONArray().put("title").put("start").put("end")));
        JSONObject intentProperties = new JSONObject()
                .put("action", stringSchema("Android intent action, for example android.intent.action.VIEW."))
                .put("data", stringSchema("Optional data URI."))
                .put("mime_type", stringSchema("Optional MIME type."))
                .put("package_name", stringSchema("Optional target package."))
                .put("extras", new JSONObject().put("type", "object")
                        .put("additionalProperties", stringSchema("Text extra value.")));
        tools.put(tool("launch_intent", "Create and launch an Android activity intent after approval. Only activity launches are supported; Android enforces app visibility and permissions. External completion is not inferred.",
                intentProperties, new JSONArray().put("action")));
        return tools;
    }

    private JSONObject tool(String name, String description, JSONObject properties,
                            JSONArray required) throws JSONException {
        JSONObject parameters = new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", required)
                .put("additionalProperties", false);
        return new JSONObject()
                .put("type", "function")
                .put("function", new JSONObject()
                        .put("name", name)
                        .put("description", description)
                        .put("parameters", parameters));
    }

    private JSONObject stringSchema(String description) throws JSONException {
        return new JSONObject().put("type", "string").put("description", description);
    }

    private JSONObject systemMessage() {
        return message("system", systemPrompt());
    }

    private String systemPrompt() {
        return "You are Mobile Agent, an Android assistant. The current local date and time is "
                + ZonedDateTime.now() + ". Use this for relative dates and times, and use only the provided tools "
                + "when an action is needed. Never claim an action happened before the tool result. "
                + "All actions require explicit user approval; the app enforces this. Commands are "
                + "limited to pwd, ls, cat, and wc in the user-visible /storage/emulated/0/mobileagent workspace, not a general shell. "
                + AgentTools.agentFilesPromptInstruction() + " "
                + "Contact-search names and phone numbers are displayed locally and are not sent to DeepSeek; ask the user to select from the on-device results and submit the chosen recipient details before preparing a message. "
                + "A messaging app draft is not a sent message, and an intent launch does not prove external completion.";
    }

    private void appendToolResult(String callId, String content) {
        try {
            conversation.add(new JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", callId)
                    .put("content", content));
            addMessage("assistant", "Tool result: " + content);
        } catch (JSONException exception) {
            finishAssistantTurn("Could not process the tool result.");
        }
    }

    private void finishAssistantTurn(String response) {
        hideLoadingIndicator();
        if (response != null && !response.isEmpty()) {
            addMessage("assistant", response);
        }
        busy = false;
        setComposerEnabled(true);
    }

    private void setComposerEnabled(boolean enabled) {
        sendButton.setEnabled(enabled);
        messageInput.setEnabled(enabled);
        sendButton.setAlpha(enabled ? 1f : 0.55f);
    }

    private void showLoadingIndicator() {
        if (loadingIndicator == null) {
            loadingIndicator = new LinearLayout(this);
            loadingIndicator.setGravity(Gravity.CENTER_VERTICAL);
            loadingIndicator.setPadding(dp(12), dp(10), dp(12), dp(10));
            loadingIndicator.setBackground(cardBackground(Color.WHITE, dp(12)));
            loadingIndicator.setContentDescription("Generating a response");

            ProgressBar spinner = new ProgressBar(this, null,
                    android.R.attr.progressBarStyleSmall);
            spinner.setIndeterminate(true);
            spinner.setContentDescription("Generating");
            loadingIndicator.addView(spinner,
                    new LinearLayout.LayoutParams(dp(20), dp(20)));

            TextView status = text("Generating response…", 14, MUTED, false);
            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            statusParams.leftMargin = dp(10);
            loadingIndicator.addView(status, statusParams);
        }
        if (loadingIndicator.getParent() != messages) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 0, dp(10));
            messages.addView(loadingIndicator, params);
        } else if (messages.indexOfChild(loadingIndicator) != messages.getChildCount() - 1) {
            messages.removeView(loadingIndicator);
            messages.addView(loadingIndicator);
        }
        messageScroll.post(() -> messageScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void hideLoadingIndicator() {
        if (loadingIndicator != null && loadingIndicator.getParent() == messages) {
            messages.removeView(loadingIndicator);
        }
    }

    private void addMessage(String role, String value) {
        TextView bubble = text(value, 15, INK, false);
        bubble.setTextIsSelectable(true);
        bubble.setPadding(dp(12), dp(10), dp(12), dp(10));
        bubble.setBackground(cardBackground(
                "user".equals(role) ? Color.rgb(230, 237, 255) : PAGE_BACKGROUND, dp(12)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(10));
        messages.addView(bubble, params);
        messageScroll.post(() -> messageScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private JSONObject message(String role, String content) {
        try {
            return new JSONObject().put("role", role).put("content", content);
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private boolean isSupportedTool(String name) {
        return "create_file".equals(name) || "run_command".equals(name)
                || "open_calendar".equals(name) || "get_current_datetime".equals(name)
                || "read_file".equals(name) || "write_file".equals(name)
                || "list_directory".equals(name) || "create_directory".equals(name)
                || "launch_intent".equals(name) || "search_contacts".equals(name)
                || "prepare_message".equals(name);
    }

    private String safeError(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "unknown error";
        }
        return value.length() > 300 ? value.substring(0, 300) : value;
    }

    private void showToast(String value) {
        addMessage("assistant", value);
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private GradientDrawable cardBackground(int color, int cornerRadiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(cornerRadiusDp));
        return background;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface ApprovalCallback {
        void onResult(boolean approved);
    }
}
