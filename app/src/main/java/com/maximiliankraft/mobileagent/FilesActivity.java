package com.maximiliankraft.mobileagent;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class FilesActivity extends Activity {
    private static final int REQUEST_STORAGE_ACCESS = 21;
    private static final int REQUEST_LEGACY_STORAGE = 22;

    private LinearLayout entries;
    private TextView location;
    private File sharedStorageRoot;
    private File workspace;
    private File currentDirectory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sharedStorageRoot = Environment.getExternalStorageDirectory();
        setContentView(createContent());
        refresh();
    }

    private LinearLayout createContent() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        page.setPadding(padding, padding, padding, padding);

        TextView heading = new TextView(this);
        heading.setText("Agent files");
        heading.setTextSize(25);
        page.addView(heading);

        location = new TextView(this);
        location.setTextSize(13);
        page.addView(location);

        LinearLayout controls = new LinearLayout(this);
        Button up = new Button(this);
        up.setText("Up");
        up.setAllCaps(false);
        up.setOnClickListener(view -> navigateUp());
        controls.addView(up);
        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setAllCaps(false);
        refresh.setOnClickListener(view -> refresh());
        controls.addView(refresh);
        Button access = new Button(this);
        access.setText("Storage access");
        access.setAllCaps(false);
        access.setOnClickListener(view -> requestStorageAccess());
        controls.addView(access);
        page.addView(controls);

        ScrollView scroll = new ScrollView(this);
        entries = new LinearLayout(this);
        entries.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(entries);
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return page;
    }

    private void refresh() {
        entries.removeAllViews();
        if (!hasSharedStorageAccess()) {
            location.setText("Grant shared-storage access to browse /storage/emulated/0/mobileagent.");
            addMessage("No files shown. Android has not granted shared-storage access.");
            return;
        }
        try {
            workspace = AgentTools.workspaceDirectory(sharedStorageRoot);
            if (currentDirectory == null || !currentDirectory.isDirectory()
                    || !currentDirectory.getCanonicalFile().toPath()
                    .startsWith(workspace.getCanonicalFile().toPath())) {
                currentDirectory = workspace;
            }
            location.setText(currentDirectory.getAbsolutePath());
            List<File> files = new ArrayList<>();
            boolean truncated = false;
            try (DirectoryStream<java.nio.file.Path> stream =
                         Files.newDirectoryStream(currentDirectory.toPath())) {
                for (java.nio.file.Path child : stream) {
                    if (files.size() >= AgentTools.MAX_DIRECTORY_ENTRIES) {
                        truncated = true;
                        break;
                    }
                    files.add(child.toFile());
                }
            }
            files.sort((left, right) -> {
                if (left.isDirectory() != right.isDirectory()) {
                    return left.isDirectory() ? -1 : 1;
                }
                return left.getName().compareToIgnoreCase(right.getName());
            });
            if (files.isEmpty()) {
                addMessage("This directory is empty.");
                return;
            }
            for (File file : files) {
                Button entry = new Button(this);
                entry.setAllCaps(false);
                entry.setText(file.getName() + (file.isDirectory() ? "/" : ""));
                entry.setOnClickListener(view -> open(file));
                entries.addView(entry);
            }
            if (truncated) {
                addMessage("Showing the first " + AgentTools.MAX_DIRECTORY_ENTRIES
                        + " entries. Open a subdirectory to narrow the listing.");
            }
        } catch (Exception exception) {
            location.setText("/storage/emulated/0/mobileagent");
            addMessage("Workspace unavailable: " + safeError(exception.getMessage()));
        }
    }

    private void open(File file) {
        if (file.isDirectory()) {
            currentDirectory = file;
            refresh();
            return;
        }
        try {
            String content = AgentTools.readTextFile(sharedStorageRoot, workspace,
                    file.getAbsolutePath());
            new android.app.AlertDialog.Builder(this)
                    .setTitle(file.getName())
                    .setMessage(content)
                    .setPositiveButton("Close", null)
                    .show();
        } catch (Exception exception) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("File unavailable")
                    .setMessage(safeError(exception.getMessage()))
                    .setPositiveButton("Close", null)
                    .show();
        }
    }

    private void navigateUp() {
        if (workspace == null || currentDirectory == null
                || currentDirectory.equals(workspace)) {
            return;
        }
        File parent = currentDirectory.getParentFile();
        if (parent != null && parent.toPath().startsWith(workspace.toPath())) {
            currentDirectory = parent;
            refresh();
        }
    }

    private void addMessage(String text) {
        TextView message = new TextView(this);
        message.setText(text);
        message.setTextSize(15);
        int padding = Math.round(10 * getResources().getDisplayMetrics().density);
        message.setPadding(padding, padding, padding, padding);
        entries.addView(message);
    }

    private boolean hasSharedStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivityForResult(intent, REQUEST_STORAGE_ACCESS);
            } catch (Exception exception) {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                        REQUEST_STORAGE_ACCESS);
            }
        } else {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_LEGACY_STORAGE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_STORAGE_ACCESS) {
            refresh();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LEGACY_STORAGE) {
            refresh();
            if (!hasSharedStorageAccess()) {
                Toast.makeText(this, "Shared-storage access was not granted.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private String safeError(String value) {
        return value == null || value.trim().isEmpty() ? "unknown error"
                : value.length() > 300 ? value.substring(0, 300) : value;
    }
}
