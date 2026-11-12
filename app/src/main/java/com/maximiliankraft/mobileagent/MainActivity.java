package com.maximiliankraft.mobileagent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.text.InputType;
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
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor();

    private final List<JSONObject> conversation = new ArrayList<>();
    private LinearLayout messages;
    private EditText messageInput;
    private EditText apiKeyInput;
    private ScrollView messageScroll;
    private Button sendButton;
    private String apiKey;
    private boolean busy;
    private int apiTurnsRemaining;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        conversation.add(systemMessage());
        setContentView(createContent());
    }

    @Override
    protected void onDestroy() {
        apiKey = null;
        super.onDestroy();
    }

    private LinearLayout createContent() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(20), dp(20), dp(16));
        page.setBackgroundColor(PAGE_BACKGROUND);

        TextView heading = text("Mobile Agent", 26, INK, true);
        page.addView(heading);
        TextView subtitle = text("DeepSeek · actions always need your approval", 14, MUTED, false);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        page.addView(subtitle);

        LinearLayout credentials = new LinearLayout(this);
        credentials.setOrientation(LinearLayout.HORIZONTAL);
        apiKeyInput = new EditText(this);
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setHint("DeepSeek API key (memory only)");
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        credentials.addView(apiKeyInput, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button useKeyButton = new Button(this);
        useKeyButton.setText("Use key");
        useKeyButton.setAllCaps(false);
        credentials.addView(useKeyButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
        page.addView(credentials);
        useKeyButton.setOnClickListener(view -> {
            String enteredKey = apiKeyInput.getText().toString().trim();
            if (enteredKey.isEmpty()) {
                showToast("Enter your DeepSeek API key first.");
                return;
            }
            apiKey = enteredKey;
            apiKeyInput.setText("");
            showToast("API key held in memory until the app closes.");
        });

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
                "Add your DeepSeek API key, then ask me to create a file, run a safe read-only command, or draft a calendar event. I will show the exact action and ask before doing it.");

        LinearLayout composer = new LinearLayout(this);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        messageInput = new EditText(this);
        messageInput.setHint("Ask Mobile Agent…");
        messageInput.setMinLines(1);
        messageInput.setMaxLines(4);
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
        if (apiKey == null || apiKey.isEmpty()) {
            showToast("Add your DeepSeek API key first.");
            return;
        }
        messageInput.setText("");
        conversation.add(message("user", content));
        addMessage("user", content);
        busy = true;
        apiTurnsRemaining = MAX_API_TURNS;
        setComposerEnabled(false);
        requestAssistantResponse();
    }

    private void requestAssistantResponse() {
        if (apiTurnsRemaining-- <= 0) {
            finishAssistantTurn("Stopped after the tool-call limit. Send another message to continue.");
            return;
        }
        IO_EXECUTOR.execute(() -> {
            try {
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
            ScrollView detailScroller = new ScrollView(this);
            TextView detailText = text(details, 15, INK, false);
            detailText.setTextIsSelectable(true);
            detailScroller.addView(detailText);
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setView(detailScroller)
                    .setNegativeButton("Cancel", (dialog, which) -> callback.onResult(false))
                    .setPositiveButton("Approve", (dialog, which) -> callback.onResult(true))
                    .setOnCancelListener(dialog -> callback.onResult(false))
                    .show();
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
        return message("system", "You are Mobile Agent, an Android assistant. Use only the provided tools "
                + "when an action is needed. Never claim an action happened before the tool result. "
                + "All actions require explicit user approval; the app enforces this. Commands are "
                + "limited to pwd, ls, cat, and wc in the app-private workspace, not a general shell.");
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
                || "open_calendar".equals(name);
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
