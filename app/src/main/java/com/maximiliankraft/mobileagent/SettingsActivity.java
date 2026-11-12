package com.maximiliankraft.mobileagent;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    private EditText apiKeyInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        TextView description = new TextView(this);
        description.setText("DeepSeek API key (kept in memory only)");
        description.setTextSize(15);
        description.setPadding(0, padding, 0, 0);
        page.addView(description);

        apiKeyInput = new EditText(this);
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setHint("Enter API key");
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = padding / 2;
        page.addView(apiKeyInput, inputParams);

        Button saveButton = new Button(this);
        saveButton.setText("Save and open chat");
        saveButton.setAllCaps(false);
        page.addView(saveButton);
        saveButton.setOnClickListener(view -> saveAndOpenChat());

        setContentView(page);
    }

    private void saveAndOpenChat() {
        String enteredKey = apiKeyInput.getText().toString().trim();
        if (enteredKey.isEmpty()) {
            Toast.makeText(this, "Enter your DeepSeek API key first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        ((MobileAgentApplication) getApplication()).setApiKey(enteredKey);
        if (getIntent().getBooleanExtra("return_to_chat", false)) {
            setResult(RESULT_OK);
            finish();
            return;
        }
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
