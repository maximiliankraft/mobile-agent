package com.maximiliankraft.mobileagent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class SettingsActivity extends Activity {
    private EditText apiKeyInput;
    private TextView description;
    private Button forgetButton;
    private boolean returnToChat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        returnToChat = getIntent().getBooleanExtra("return_to_chat", false);
        MobileAgentApplication application = (MobileAgentApplication) getApplication();
        boolean storageError = false;
        try {
            String savedKey = application.getApiKey();
            if (!returnToChat && savedKey != null && !savedKey.isEmpty()) {
                startActivity(new Intent(this, MainActivity.class));
                finish();
                return;
            }
        } catch (GeneralSecurityException | IOException exception) {
            storageError = true;
        }
        boolean hasSavedKey = application.hasSavedApiKey();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        page.setPadding(padding, padding, padding, padding);
        page.setBackgroundColor(Color.rgb(246, 247, 249));

        TextView heading = new TextView(this);
        heading.setText("API settings");
        heading.setTextSize(26);
        heading.setTextColor(Color.rgb(29, 34, 43));
        page.addView(heading);

        description = new TextView(this);
        description.setText(storageError
                ? "The saved API key could not be read. It has not been deleted. Enter a replacement or forget it."
                : hasSavedKey
                ? "A DeepSeek API key is saved encrypted in this app's private storage. Enter a replacement below or forget it."
                : "Enter your DeepSeek API key. It will be encrypted in this app's private storage.");
        description.setTextSize(15);
        description.setPadding(0, padding, 0, 0);
        page.addView(description);

        apiKeyInput = new EditText(this);
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setHint(hasSavedKey ? "Enter a replacement API key" : "Enter API key");
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = padding / 2;
        page.addView(apiKeyInput, inputParams);

        CheckBox showKey = new CheckBox(this);
        showKey.setText("Show API key");
        showKey.setOnCheckedChangeListener((button, checked) -> {
            apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT
                    | (checked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            apiKeyInput.setSelection(apiKeyInput.getText().length());
        });
        page.addView(showKey);

        Button saveButton = new Button(this);
        saveButton.setText(returnToChat ? "Save and return to chat" : "Save and open chat");
        saveButton.setAllCaps(false);
        page.addView(saveButton);
        saveButton.setOnClickListener(view -> saveAndOpenChat());

        forgetButton = new Button(this);
        forgetButton.setText("Forget saved API key");
        forgetButton.setAllCaps(false);
        forgetButton.setVisibility(hasSavedKey ? View.VISIBLE : View.GONE);
        page.addView(forgetButton);
        forgetButton.setOnClickListener(view -> confirmForgetApiKey());

        setContentView(page);
    }

    private void saveAndOpenChat() {
        String enteredKey = apiKeyInput.getText().toString().trim();
        if (enteredKey.isEmpty()) {
            Toast.makeText(this, "Enter your DeepSeek API key first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            ((MobileAgentApplication) getApplication()).setApiKey(enteredKey);
        } catch (GeneralSecurityException | IOException exception) {
            Toast.makeText(this, "Could not securely save the API key. It was not saved.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        apiKeyInput.getText().clear();
        if (returnToChat) {
            setResult(RESULT_OK);
            finish();
            return;
        }
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void confirmForgetApiKey() {
        new AlertDialog.Builder(this)
                .setTitle("Forget saved API key?")
                .setMessage("This removes the API key from this app. You will need to enter it again before using the chat.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Forget", (dialog, which) -> forgetApiKey())
                .show();
    }

    private void forgetApiKey() {
        MobileAgentApplication application = (MobileAgentApplication) getApplication();
        boolean cleanupWarning = false;
        try {
            application.clearApiKey();
        } catch (GeneralSecurityException | IOException exception) {
            if (application.hasSavedApiKey()) {
                description.setText("Could not complete removal. The saved API key may still be stored.");
                Toast.makeText(this, "Could not remove the saved API key.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            cleanupWarning = true;
        }
        apiKeyInput.getText().clear();
        forgetButton.setVisibility(View.GONE);
        if (returnToChat) {
            if (cleanupWarning) {
                Toast.makeText(this, "The API key was removed, but encryption-key cleanup did not complete.",
                        Toast.LENGTH_LONG).show();
            }
            setResult(RESULT_OK);
            finish();
        } else {
            description.setText(cleanupWarning
                    ? "The API key was removed, but encryption-key cleanup did not complete."
                    : "The saved API key was removed.");
            Toast.makeText(this, cleanupWarning
                    ? "The API key was removed, but encryption-key cleanup did not complete."
                    : "Saved API key forgotten.", Toast.LENGTH_SHORT).show();
        }
    }
}
