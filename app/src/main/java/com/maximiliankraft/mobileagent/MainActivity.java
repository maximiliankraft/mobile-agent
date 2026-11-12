package com.maximiliankraft.mobileagent;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.net.Uri;
import android.util.Base64;
import android.view.inputmethod.EditorInfo;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
    private static final int MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024;
    private static final int MAX_TEXT_FILE_BYTES = 32 * 1024;
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor();

    private final List<JSONObject> conversation = new ArrayList<>();
    private LinearLayout messages;
    private EditText messageInput;
    private ScrollView messageScroll;
    private Button sendButton;
    private TextView attachmentStatus;
    private String apiKey;
    private String pendingImageDataUrl;
    private String pendingContext = "";
    private boolean busy;
    private int apiTurnsRemaining;

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
        setContentView(createContent());
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
        Button fileButton = attachmentButton("File", () -> chooseContent(PICK_FILE));
        Button contactButton = attachmentButton("Contact", this::chooseContact);
        attachmentActions.addView(imageButton);
        attachmentActions.addView(fileButton);
        attachmentActions.addView(contactButton);
        page.addView(attachmentActions);
        attachmentStatus = text("", 12, MUTED, false);
        page.addView(attachmentStatus);
        Button clearAttachments = attachmentButton("Clear selected items", () -> {
            pendingImageDataUrl = null;
            pendingContext = "";
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

    private void sendMessage() {
        String content = messageInput.getText().toString().trim();
        if (content.isEmpty() || busy) {
            return;
        }
        if (!pendingContext.isEmpty()) {
            content += "\n\nSelected context:\n" + pendingContext;
        }
        messageInput.setText("");
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
        conversation.add(userMessage);
        addMessage("user", content);
        if (pendingImageDataUrl != null) {
            addMessage("user", "Image attached.");
        }
        pendingImageDataUrl = null;
        pendingContext = "";
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == EDIT_API_KEY) {
            apiKey = ((MobileAgentApplication) getApplication()).getApiKey();
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
                pendingImageDataUrl = "data:" + mimeType + ";base64,"
                        + Base64.encodeToString(imageBytes, Base64.NO_WRAP);
                updateAttachmentStatus();
                showToast("Image attached. Add a prompt and send it.");
            } else if (requestCode == PICK_FILE) {
                String fileText = new String(readUriBytes(uri, MAX_TEXT_FILE_BYTES),
                        StandardCharsets.UTF_8);
                pendingContext += "\nSelected text file (" + uri.getLastPathSegment()
                        + "):\n" + fileText;
                updateAttachmentStatus();
                showToast("File attached to the next prompt.");
            } else if (requestCode == PICK_CONTACT) {
                pendingContext += "\nSelected contact:\n" + readContact(uri);
                updateAttachmentStatus();
                showToast("Contact added to the next prompt.");
            }
        } catch (Exception exception) {
            showToast("Could not read the selection: " + safeError(exception.getMessage()));
        }
    }

    private void updateAttachmentStatus() {
        if (attachmentStatus == null) {
            return;
        }
        StringBuilder status = new StringBuilder();
        if (pendingImageDataUrl != null) {
            status.append("Image selected");
        }
        if (!pendingContext.isEmpty()) {
            if (status.length() > 0) {
                status.append(" · ");
            }
            status.append("File/contact context selected");
        }
        if (status.length() > 0) {
            status.append(" — sent with your next message");
        }
        attachmentStatus.setText(status.toString());
    }

    private byte[] readUriBytes(Uri uri, int maximumBytes) throws IOException {
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
        showToolApproval(name, arguments, approved -> {
            boolean started = AgentTools.runApprovedAction(approved, () -> {
                if ("open_calendar".equals(name)) {
                    appendToolResult(callId, executeCalendarTool(arguments));
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

    private void showToolApproval(String name, JSONObject arguments,
                                  ApprovalCallback callback) {
        String title;
        String details;
        try {
            switch (name) {
                case "create_file":
                    title = "Create this file?";
                    details = "Workspace path: " + arguments.getString("path")
                            + "\n\nContents:\n" + arguments.getString("content");
                    break;
                case "run_command":
                    JSONArray args = arguments.getJSONArray("args");
                    List<String> command = new ArrayList<>();
                    command.add(arguments.getString("command"));
                    for (int i = 0; i < args.length(); i++) {
                        command.add(args.getString(i));
                    }
                    title = "Run this command?";
                    details = "Runs inside the private workspace. Allowed commands are pwd, ls, cat, and wc.\n\n"
                            + new JSONArray(command);
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
            if ("create_file".equals(name)) {
                return AgentTools.createFile(workspaceDirectory(), arguments.getString("path"),
                        arguments.getString("content"));
            }
            JSONArray suppliedArgs = arguments.getJSONArray("args");
            List<String> args = new ArrayList<>();
            for (int i = 0; i < suppliedArgs.length(); i++) {
                args.add(suppliedArgs.getString(i));
            }
            return AgentTools.runCommand(workspaceDirectory(), getCacheDir(),
                    arguments.getString("command"), args);
        } catch (Exception exception) {
            return "Action failed: " + safeError(exception.getMessage());
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
            return AgentTools.workspaceDirectory(getFilesDir());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private JSONArray toolDefinitions() throws JSONException {
        JSONArray tools = new JSONArray();
        tools.put(tool("get_current_datetime", "Get the current local date, time, and timezone.",
                new JSONObject(), new JSONArray()));
        tools.put(tool("create_file", "Create or replace a small text file in the private workspace.",
                new JSONObject()
                        .put("path", stringSchema("Relative workspace path, for example notes/todo.txt."))
                        .put("content", stringSchema("Complete UTF-8 file contents, at most 16 KiB.")),
                new JSONArray().put("path").put("content")));
        tools.put(tool("run_command", "Run one allowlisted, read-only command in the private workspace. "
                        + "Only pwd, ls, cat, and wc are supported; this is not a shell.",
                new JSONObject()
                        .put("command", stringSchema("One of pwd, ls, cat, or wc."))
                        .put("args", new JSONObject().put("type", "array")
                                .put("items", stringSchema("Workspace-relative path when required."))
                                .put("maxItems", 1)),
                new JSONArray().put("command").put("args")));
        tools.put(tool("open_calendar", "Open the calendar app with a proposed event after user approval.",
                new JSONObject()
                        .put("title", stringSchema("Event title, at most 120 characters."))
                        .put("start", stringSchema("Start timestamp in ISO-8601 UTC format."))
                        .put("end", stringSchema("End timestamp in ISO-8601 UTC format.")),
                new JSONArray().put("title").put("start").put("end")));
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
                + "limited to pwd, ls, cat, and wc in the app-private workspace, not a general shell.";
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
                || "open_calendar".equals(name) || "get_current_datetime".equals(name);
    }

    private String safeError(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "unknown error";
        }
        return value.length() > 300 ? value.substring(0, 300) : value;
    }

    private void showToast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
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
