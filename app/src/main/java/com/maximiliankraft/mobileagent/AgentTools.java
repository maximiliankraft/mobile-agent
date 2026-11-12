package com.maximiliankraft.mobileagent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class AgentTools {
    static final int MAX_FILE_BYTES = 16 * 1024;
    static final int MAX_READ_FILE_BYTES = 64 * 1024;
    static final int MAX_DIRECTORY_ENTRIES = 500;
    static final int MAX_DIRECTORY_LISTING_BYTES = 32 * 1024;
    static final int MAX_COMMAND_OUTPUT_BYTES = 8 * 1024;

    private AgentTools() {
    }

    static File workspaceDirectory(File sharedStorageRoot) throws IOException {
        File storageRoot = sharedStorageRoot.getCanonicalFile();
        File expectedDirectory = new File(storageRoot, "mobileagent").getAbsoluteFile();
        File directory = expectedDirectory.getCanonicalFile();
        if (!directory.toPath().startsWith(storageRoot.toPath())
                || !directory.equals(expectedDirectory)) {
            throw new IOException("The agent workspace must be a real directory inside shared storage, not a symlink.");
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create /storage/emulated/0/mobileagent. "
                    + "Grant shared-storage access in Android settings and retry.");
        }
        return directory;
    }

    static File workspaceForPath(File sharedStorageRoot, String path) throws IOException {
        String suppliedPath = path == null ? "" : path;
        if (new File(suppliedPath).isAbsolute()) {
            File root = sharedStorageRoot.getCanonicalFile();
            File workspace = new File(root, "mobileagent").getAbsoluteFile();
            File target = new File(suppliedPath).getCanonicalFile();
            if (!target.toPath().startsWith(workspace.toPath())) {
                return workspace;
            }
        }
        return workspaceDirectory(sharedStorageRoot);
    }

    static String agentFilesPromptInstruction() {
        return "The user-visible agent workspace may contain user.md, soul.md, and a skills/ "
                + "directory. These files are not loaded automatically. When relevant, use "
                + "list_directory and read_file to inspect and read only what is needed. "
                + "before stating an action is unsupported, inspect relevant skills/ and "
                + "consider all available tools, including launch_intent when appropriate. "
                + "Do not claim a workflow is impossible only because there is no dedicated tool. "
                + "File reads require user approval, and their contents are sent to DeepSeek.";
    }

    static String contactSearchToolResult(String localResults) {
        String prefix = "Contact matches (";
        if (localResults.startsWith(prefix)) {
            int countEnd = localResults.indexOf("):", prefix.length());
            String count = countEnd > prefix.length()
                    ? localResults.substring(prefix.length(), countEnd) : "some";
            return "Found " + count + " matching contacts. Names and phone numbers were "
                    + "displayed locally and were not sent to DeepSeek. Ask the user to select "
                    + "the intended person from the on-device results and provide the chosen "
                    + "recipient details in a submitted message before preparing a message.";
        }
        if (localResults.startsWith("No contacts matched")) {
            return "No contacts matched. No contact details were sent to DeepSeek; ask the user "
                    + "for another identifier or recipient details.";
        }
        return localResults;
    }

    static File resolvePath(File sharedStorageRoot, File workspaceDirectory, String path)
            throws IOException {
        String suppliedPath = path == null ? "" : path;
        if (suppliedPath.length() > 4096 || suppliedPath.indexOf('\n') >= 0
                || suppliedPath.indexOf('\r') >= 0) {
            throw new IOException("Invalid path in shared storage.");
        }
        File root = sharedStorageRoot.getCanonicalFile();
        boolean absolutePath = new File(suppliedPath).isAbsolute();
        File base = absolutePath ? null : workspaceDirectory.getCanonicalFile();
        if (!absolutePath && !base.toPath().startsWith(root.toPath())) {
            throw new IOException("The agent workspace must remain inside shared storage.");
        }
        File requested = suppliedPath.trim().isEmpty() ? base
                : absolutePath ? new File(suppliedPath) : new File(base, suppliedPath);
        File target = requested.getCanonicalFile();
        if (!absolutePath && !suppliedPath.trim().isEmpty()
                && !target.toPath().startsWith(base.toPath())) {
            throw new IOException("Relative paths must stay inside the agent workspace.");
        }
        return target;
    }

    static String readTextFile(File sharedStorageRoot, File workspaceDirectory, String path)
            throws IOException {
        File target = resolvePath(sharedStorageRoot, workspaceDirectory, path);
        if (!target.isFile()) {
            throw new IOException("Path is not a readable file: " + target.getPath());
        }
        if (target.length() > MAX_READ_FILE_BYTES) {
            throw new IOException("File exceeds the 64 KiB read limit.");
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(target.toPath());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_READ_FILE_BYTES) {
                    throw new IOException("File exceeds the 64 KiB read limit.");
                }
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static String writeTextFile(File sharedStorageRoot, File workspaceDirectory, String path,
                                String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            return "File not written: content exceeds 16 KiB.";
        }
        File target = resolvePath(sharedStorageRoot, workspaceDirectory, path);
        if (target.equals(sharedStorageRoot.getCanonicalFile()) || target.isDirectory()) {
            throw new IOException("Target must be a file path in shared storage.");
        }
        File parent = target.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            throw new IOException("Could not create the target directory.");
        }
        File temporary = Files.createTempFile(parent.toPath(), ".mobile-agent-", ".tmp").toFile();
        try {
            Files.write(temporary.toPath(), bytes);
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
        return "Written " + target.getPath() + " (" + bytes.length + " bytes).";
    }

    static String listDirectory(File sharedStorageRoot, File workspaceDirectory, String path)
            throws IOException {
        File directory = resolvePath(sharedStorageRoot, workspaceDirectory, path);
        if (!directory.isDirectory()) {
            throw new IOException("Path is not a directory: " + directory.getPath());
        }
        List<File> children = new ArrayList<>();
        boolean truncated = false;
        try (java.nio.file.DirectoryStream<java.nio.file.Path> stream =
                     Files.newDirectoryStream(directory.toPath())) {
            for (java.nio.file.Path child : stream) {
                if (children.size() >= MAX_DIRECTORY_ENTRIES) {
                    truncated = true;
                    break;
                }
                children.add(child.toFile());
            }
        }
        children.sort((left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        StringBuilder listing = new StringBuilder();
        int entries = 0;
        int listingBytes = 0;
        int contentLimit = MAX_DIRECTORY_LISTING_BYTES
                - ("\n…listing truncated; refine the path to see fewer entries."
                .getBytes(StandardCharsets.UTF_8).length);
        for (File child : children) {
            String entry = (listing.length() > 0 ? "\n" : "") + child.getName()
                    + (child.isDirectory() ? "/" : "");
            int entryBytes = entry.getBytes(StandardCharsets.UTF_8).length;
            if (entries >= MAX_DIRECTORY_ENTRIES
                    || listingBytes + entryBytes > contentLimit) {
                truncated = true;
                break;
            }
            listing.append(entry);
            listingBytes += entryBytes;
            entries++;
        }
        if (truncated) {
            listing.append("\n…listing truncated; refine the path to see fewer entries.");
        }
        return listing.length() == 0 ? "(empty directory)" : listing.toString();
    }

    static String createDirectory(File sharedStorageRoot, File workspaceDirectory, String path)
            throws IOException {
        File directory = resolvePath(sharedStorageRoot, workspaceDirectory, path);
        if (directory.equals(sharedStorageRoot.getCanonicalFile())) {
            throw new IOException("Choose a directory below shared storage.");
        }
        if (directory.isDirectory()) {
            return "Directory already exists: " + directory.getPath();
        }
        if (directory.exists() || !directory.mkdirs()) {
            throw new IOException("Could not create directory: " + directory.getPath());
        }
        return "Created directory " + directory.getPath() + ".";
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
            throw new IOException("Path must stay inside the agent workspace.");
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
        return "Created " + path + " (" + bytes.length + " bytes) in the agent workspace.";
    }

    static String commandPolicyWarning(String name, List<String> suppliedArgs) {
        if (!"pwd".equals(name) && !"ls".equals(name)
                && !"cat".equals(name) && !"wc".equals(name)) {
            return "Warning: command is not in the safe command set (pwd, ls, cat, wc); it will not execute.";
        }
        int maximumArguments = "pwd".equals(name) ? 0 : 1;
        if (suppliedArgs.size() > maximumArguments) {
            return "Warning: too many arguments for " + name + "; the command will not execute.";
        }
        if (("cat".equals(name) || "wc".equals(name)) && suppliedArgs.isEmpty()) {
            return "Warning: " + name + " requires one file argument; the command will not execute.";
        }
        return null;
    }

    static String runCommand(File workspaceDirectory, File cacheDirectory, String name,
                             List<String> suppliedArgs) throws Exception {
        String warning = commandPolicyWarning(name, suppliedArgs);
        if (warning != null) {
            return warning;
        }
        if ("pwd".equals(name)) {
            return "Exit code 0:\n" + workspaceDirectory.getCanonicalPath();
        }
        if ("ls".equals(name)) {
            String path = suppliedArgs.isEmpty() ? "" : suppliedArgs.get(0);
            String listing = listDirectory(workspaceDirectory, workspaceDirectory, path);
            if (listing.length() > MAX_COMMAND_OUTPUT_BYTES) {
                listing = listing.substring(0, MAX_COMMAND_OUTPUT_BYTES) + "\n…output truncated…";
            }
            return "Exit code 0:\n" + listing;
        }
        if (suppliedArgs.size() != 1) {
            return "Command rejected: " + name + " requires one workspace file path.";
        }
        File target = workspaceFile(workspaceDirectory, suppliedArgs.get(0));
        if (!target.isFile() || target.length() > MAX_COMMAND_OUTPUT_BYTES) {
            return "Command rejected: file must exist and be at most 8 KiB.";
        }
        byte[] content = Files.readAllBytes(target.toPath());
        if ("cat".equals(name)) {
            return "Exit code 0:\n" + new String(content, StandardCharsets.UTF_8);
        }
        long lines = 0;
        long words = 0;
        boolean inWord = false;
        String text = new String(content, StandardCharsets.UTF_8);
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '\n') {
                lines++;
            }
            if (Character.isWhitespace(character)) {
                inWord = false;
            } else if (!inWord) {
                words++;
                inWord = true;
            }
        }
        return "Exit code 0:\n" + lines + " " + words + " " + content.length
                + " " + target.getPath();
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
