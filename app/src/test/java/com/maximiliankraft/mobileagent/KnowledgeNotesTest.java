package com.maximiliankraft.mobileagent;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KnowledgeNotesTest {
    @Test
    public void addsNotesWithoutReplacingExistingContent() {
        String existing = "# User knowledge\n\n- Prefers concise answers.";

        String updated = KnowledgeNotes.appendNotes(existing, "User knowledge",
                Collections.singletonList("Uses Austrian terminology."));

        assertTrue(updated.startsWith(existing));
        assertTrue(updated.contains("- Uses Austrian terminology."));
    }

    @Test
    public void skipsDuplicateNotesIgnoringCaseAndWhitespace() {
        String existing = "# User knowledge\n- Prefers concise answers.";

        String updated = KnowledgeNotes.appendNotes(existing, "User knowledge",
                Arrays.asList("prefers   CONCISE answers", "Prefers concise answers."));

        assertEquals(1, count(updated, "Prefers concise answers"));
    }

    @Test
    public void rejectsUnsafeSkillSlugs() {
        assertTrue(KnowledgeNotes.isValidSkillSlug("android-email-drafts"));
        assertFalse(KnowledgeNotes.isValidSkillSlug("../outside"));
        assertFalse(KnowledgeNotes.isValidSkillSlug("Email Drafts"));
    }

    @Test
    public void appendsSkillLearningInsteadOfOverwritingExistingSkill() {
        String existing = "# Existing skill\n\nKeep this procedure.";

        String updated = KnowledgeNotes.appendSkill(existing, "intent-workflows",
                "Intent workflows", "Use when composing email drafts.",
                "Prefer ACTION_SENDTO with a mailto URI when the direct email tool is unavailable.");

        assertTrue(updated.startsWith(existing));
        assertTrue(updated.contains("Prefer ACTION_SENDTO with a mailto URI"));
    }

    @Test
    public void flagsCredentialValuesForExclusion() {
        assertTrue(KnowledgeNotes.containsLikelySecret("DeepSeek API key: abcdefghijklmnop"));
        assertFalse(KnowledgeNotes.containsLikelySecret("Prefers short answers."));
    }

    @Test
    public void transcriptIncludesOnlyTextMessagesAndToolNames() {
        String transcript = KnowledgeNotes.buildTranscript(Arrays.asList(
                new KnowledgeNotes.Turn("system", "Do not include system prompt.",
                        Collections.<String>emptyList()),
                new KnowledgeNotes.Turn("user", "Please draft an email. password: topsecretvalue"
                        + "\n\nSelected context:\nprivate attachment",
                        Collections.<String>emptyList()),
                new KnowledgeNotes.Turn("tool", "private tool output",
                        Collections.<String>emptyList()),
                new KnowledgeNotes.Turn("assistant", "I can open an email draft.",
                        Collections.singletonList("launch_intent"))), 4000);

        assertTrue(transcript.contains("Please draft an email."));
        assertTrue(transcript.contains("launch_intent"));
        assertTrue(transcript.contains("[REDACTED credential]"));
        assertFalse(transcript.contains("topsecretvalue"));
        assertFalse(transcript.contains("Do not include system prompt."));
        assertFalse(transcript.contains("private attachment"));
        assertFalse(transcript.contains("private tool output"));
    }

    private int count(String text, String needle) {
        int matches = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            matches++;
            offset += needle.length();
        }
        return matches;
    }
}
