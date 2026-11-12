package com.maximiliankraft.mobileagent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class KnowledgeNotes {
    private static final Pattern CREDENTIAL_VALUE = Pattern.compile(
            "(?i)(?:api[\\s_-]*key|password|passphrase|access[\\s_-]*token|secret)"
                    + "\\s*[:=]\\s*[\\\"']?[^\\s\\\"'`]{6,}");
    private static final Pattern BEARER_VALUE = Pattern.compile(
            "(?i)\\bbearer\\s+[a-z0-9._~+/-]{16,}");
    private static final Pattern API_TOKEN = Pattern.compile("\\bsk-[A-Za-z0-9]{16,}\\b");
    private static final String SELECTED_CONTEXT_MARKER = "\n\nSelected context:\n";

    static final class Turn {
        final String role;
        final String text;
        final List<String> toolNames;

        Turn(String role, String text, List<String> toolNames) {
            this.role = role;
            this.text = text;
            this.toolNames = toolNames == null ? new ArrayList<>() : new ArrayList<>(toolNames);
        }
    }

    private KnowledgeNotes() {
    }

    static String buildTranscript(List<Turn> turns, int maximumCharacters) {
        if (turns == null || maximumCharacters <= 0) {
            return "";
        }
        StringBuilder transcript = new StringBuilder();
        for (Turn turn : turns) {
            if (turn == null || !("user".equals(turn.role) || "assistant".equals(turn.role))) {
                continue;
            }
            String text = turn.text == null ? "" : turn.text;
            int selectedContext = text.indexOf(SELECTED_CONTEXT_MARKER);
            if (selectedContext >= 0) {
                text = text.substring(0, selectedContext);
            }
            text = redactLikelySecrets(text).trim();
            if (!text.isEmpty()) {
                transcript.append("user".equals(turn.role) ? "User: " : "Assistant: ")
                        .append(text).append('\n');
            }
            if ("assistant".equals(turn.role) && !turn.toolNames.isEmpty()) {
                List<String> safeNames = new ArrayList<>();
                for (String name : turn.toolNames) {
                    if (name != null && name.matches("[a-z][a-z0-9_]{0,39}")) {
                        safeNames.add(name);
                    }
                }
                if (!safeNames.isEmpty()) {
                    transcript.append("Assistant considered tools: ")
                            .append(String.join(", ", safeNames)).append('\n');
                }
            }
        }
        if (transcript.length() <= maximumCharacters) {
            return transcript.toString().trim();
        }
        String marker = "[Earlier conversation omitted]\n";
        if (maximumCharacters <= marker.length()) {
            return transcript.substring(transcript.length() - maximumCharacters);
        }
        int start = transcript.length() - (maximumCharacters - marker.length());
        if (start < transcript.length() && Character.isLowSurrogate(transcript.charAt(start))) {
            start++;
        }
        return marker + transcript.substring(start).trim();
    }

    static String appendNotes(String existing, String heading, List<String> additions) {
        String current = existing == null ? "" : existing.trim();
        String safeHeading = singleLine(heading);
        if (safeHeading.isEmpty()) {
            safeHeading = "Knowledge";
        }
        StringBuilder updated = new StringBuilder(current);
        Set<String> seen = new HashSet<>();
        for (String line : current.split("\\R")) {
            String normalized = normalize(line.replaceFirst("^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+)", ""));
            if (!normalized.isEmpty()) {
                seen.add(normalized);
            }
        }
        List<String> accepted = new ArrayList<>();
        if (additions != null) {
            for (String addition : additions) {
                String note = singleLine(addition);
                String normalized = normalize(note);
                if (normalized.isEmpty() || containsLikelySecret(note) || !seen.add(normalized)) {
                    continue;
                }
                accepted.add(note);
            }
        }
        if (accepted.isEmpty()) {
            return existing == null ? "" : existing;
        }
        if (updated.length() == 0) {
            updated.append("# ").append(safeHeading);
        }
        updated.append("\n\n");
        for (int i = 0; i < accepted.size(); i++) {
            if (i > 0) {
                updated.append('\n');
            }
            updated.append("- ").append(accepted.get(i));
        }
        updated.append('\n');
        ensureFileSize(updated.toString());
        return updated.toString();
    }

    static String appendSkill(String existing, String slug, String title,
                              String description, String content) {
        if (!isValidSkillSlug(slug)) {
            throw new IllegalArgumentException("Skill name must be a lowercase safe slug.");
        }
        String safeTitle = singleLine(title);
        String safeDescription = singleLine(description);
        String safeContent = content == null ? "" : content.trim().replace("\r\n", "\n");
        if (safeTitle.isEmpty() || safeDescription.isEmpty() || safeContent.isEmpty()) {
            throw new IllegalArgumentException("Skill title, description, and content are required.");
        }
        if (containsLikelySecret(safeTitle + "\n" + safeDescription + "\n" + safeContent)) {
            throw new IllegalArgumentException("Skill content appears to contain a credential.");
        }
        String current = existing == null ? "" : existing.trim();
        String normalizedContent = normalize(safeContent);
        if (!current.isEmpty() && normalize(current).contains(normalizedContent)) {
            return existing;
        }
        StringBuilder updated = new StringBuilder(current);
        if (updated.length() == 0) {
            updated.append("---\nname: ").append(slug)
                    .append("\ndescription: \"").append(safeDescription.replace("\"", "\\\""))
                    .append("\"\n---\n\n# ").append(safeTitle)
                    .append("\n\n").append(safeContent).append('\n');
        } else {
            updated.append("\n\n## Additional learning\n\n")
                    .append(safeContent).append('\n');
        }
        ensureFileSize(updated.toString());
        return updated.toString();
    }

    static boolean isValidSkillSlug(String slug) {
        if (slug == null || slug.isEmpty() || slug.length() > 48) {
            return false;
        }
        boolean previousWasSeparator = true;
        for (int i = 0; i < slug.length(); i++) {
            char value = slug.charAt(i);
            if (value == '-') {
                if (previousWasSeparator) {
                    return false;
                }
                previousWasSeparator = true;
            } else if (value >= 'a' && value <= 'z' || value >= '0' && value <= '9') {
                previousWasSeparator = false;
            } else {
                return false;
            }
        }
        return !previousWasSeparator;
    }

    static boolean containsLikelySecret(String value) {
        if (value == null) {
            return false;
        }
        return CREDENTIAL_VALUE.matcher(value).find()
                || BEARER_VALUE.matcher(value).find()
                || API_TOKEN.matcher(value).find();
    }

    static String redactLikelySecrets(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return API_TOKEN.matcher(
                BEARER_VALUE.matcher(
                        CREDENTIAL_VALUE.matcher(value).replaceAll("[REDACTED credential]"))
                        .replaceAll("[REDACTED credential]"))
                .replaceAll("[REDACTED credential]");
    }

    private static String singleLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static void ensureFileSize(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length > AgentTools.MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Knowledge file would exceed the 16 KiB limit.");
        }
    }
}
