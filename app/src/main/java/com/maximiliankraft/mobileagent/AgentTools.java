package com.maximiliankraft.mobileagent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class AgentTools {
    static final int MAX_FILE_BYTES = 16 * 1024;
    static final int MAX_COMMAND_OUTPUT_BYTES = 8 * 1024;

    private AgentTools() {
    }

    static File workspaceDirectory(File filesDirectory) throws IOException {
        File directory = new File(filesDirectory, "workspace").getCanonicalFile();
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create the private workspace.");
        }
        return directory;
    }

    static File workspaceFile(File workspaceDirectory, String relativePath) throws IOException {
        if (relativePath.trim().isEmpty() || relativePath.length() > 240
                || relativePath.startsWith("/") || relativePath.indexOf('\n') >= 0
                || relativePath.indexOf('\r') >= 0) {
            throw new IOException("Use a non-empty relative workspace path.");
        }
        File root = workspaceDirectory.getCanonicalFile();
        File target = new File(root, relativePath).getCanonicalFile();
        if (!target.toPath().startsWith(root.toPath()) || target.equals(root)) {
            throw new IOException("Path must stay inside the private workspace.");
        }
        return target;
    }

    static String createFile(File workspaceDirectory, String path, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            return "File not created: content exceeds 16 KiB.";
        }
        File target = workspaceFile(workspaceDirectory, path);
        File parent = target.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            return "File not created: could not create its parent directory.";
        }
        Files.write(target.toPath(), bytes);
        return "Created " + path + " (" + bytes.length + " bytes) in the private workspace.";
    }

    static String runCommand(File workspaceDirectory, File cacheDirectory, String name,
                             List<String> suppliedArgs) throws Exception {
        if (suppliedArgs.size() > 2) {
            return "Command rejected: at most two arguments are allowed.";
        }
        List<String> command = new ArrayList<>();
        command.add(name);
        switch (name) {
            case "pwd":
                if (!suppliedArgs.isEmpty()) {
                    return "Command rejected: pwd takes no arguments.";
                }
                break;
            case "ls":
                if (suppliedArgs.size() > 1) {
                    return "Command rejected: ls accepts one workspace path.";
                }
                command.add(suppliedArgs.isEmpty()
                        ? "." : workspaceFile(workspaceDirectory, suppliedArgs.get(0)).getPath());
                break;
            case "cat":
            case "wc":
                if (suppliedArgs.size() != 1) {
                    return "Command rejected: " + name + " requires one workspace file path.";
                }
                File target = workspaceFile(workspaceDirectory, suppliedArgs.get(0));
                if (!target.isFile() || target.length() > MAX_COMMAND_OUTPUT_BYTES) {
                    return "Command rejected: file must exist and be at most 8 KiB.";
                }
                command.add(target.getPath());
                break;
            default:
                return "Command rejected: only pwd, ls, cat, and wc are allowed.";
        }
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workspaceDirectory)
                .redirectErrorStream(true);
        File output = File.createTempFile("agent-command-", ".txt", cacheDirectory);
        try {
            builder.redirectOutput(output);
            Process process = builder.start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "Command stopped after the 5-second time limit.";
            }
            return "Exit code " + process.exitValue() + ":\n"
                    + readLimited(output, MAX_COMMAND_OUTPUT_BYTES);
        } finally {
            if (!output.delete()) {
                output.deleteOnExit();
            }
        }
    }

    static CalendarEvent validateCalendarEvent(String title, String start, String end) {
        String normalizedTitle = title.trim();
        long startMillis;
        long endMillis;
        try {
            startMillis = Instant.parse(start).toEpochMilli();
            endMillis = Instant.parse(end).toEpochMilli();
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Event times must use ISO-8601 UTC format.", exception);
        }
        if (normalizedTitle.isEmpty() || normalizedTitle.length() > 120 || endMillis <= startMillis) {
            throw new IllegalArgumentException("Check the title and event times.");
        }
        return new CalendarEvent(normalizedTitle, startMillis, endMillis);
    }

    static boolean runApprovedAction(boolean approved, Runnable action) {
        if (!approved) {
            return false;
        }
        action.run();
        return true;
    }

    private static String readLimited(File file, int maximumCharacters) throws IOException {
        StringBuilder value = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            int character;
            while ((character = reader.read()) != -1) {
                if (value.length() >= maximumCharacters) {
                    value.append("\n…output truncated…");
                    break;
                }
                value.append((char) character);
            }
        }
        return value.toString();
    }

    static final class CalendarEvent {
        final String title;
        final long startMillis;
        final long endMillis;

        CalendarEvent(String title, long startMillis, long endMillis) {
            this.title = title;
            this.startMillis = startMillis;
            this.endMillis = endMillis;
        }
    }
}
