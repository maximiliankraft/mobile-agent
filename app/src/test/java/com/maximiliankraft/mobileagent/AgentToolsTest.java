package com.maximiliankraft.mobileagent;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AgentToolsTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File filesDirectory;
    private File workspace;
    private File cacheDirectory;

    @Before
    public void setUp() throws Exception {
        filesDirectory = temporaryFolder.newFolder("app-files");
        cacheDirectory = temporaryFolder.newFolder("cache");
        workspace = AgentTools.workspaceDirectory(filesDirectory);
    }

    @Test
    public void createsUtf8FilesInNestedWorkspaceDirectories() throws Exception {
        String result = AgentTools.createFile(workspace, "notes/todo.txt", "café");

        assertTrue(result.startsWith("Created notes/todo.txt"));
        assertEquals("café", new String(Files.readAllBytes(
                new File(workspace, "notes/todo.txt").toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void rejectsPathsOutsideThePrivateWorkspace() throws Exception {
        File outside = new File(filesDirectory, "outside.txt");
        try {
            AgentTools.createFile(workspace, "../outside.txt", "not allowed");
            fail("Expected path traversal to be rejected.");
        } catch (Exception expected) {
            assertFalse(outside.exists());
        }

        try {
            AgentTools.workspaceFile(workspace, outside.getAbsolutePath());
            fail("Expected absolute paths to be rejected.");
        } catch (Exception expected) {
            assertFalse(outside.exists());
        }
    }

    @Test
    public void rejectsFilesOverTheSizeLimitWithoutWritingThem() throws Exception {
        String result = AgentTools.createFile(workspace, "large.txt",
                repeat('a', AgentTools.MAX_FILE_BYTES + 1));

        assertTrue(result.contains("exceeds 16 KiB"));
        assertFalse(new File(workspace, "large.txt").exists());
    }

    @Test
    public void permitsOnlyReadOnlyAllowlistedCommands() throws Exception {
        String content = "hello agent\n";
        AgentTools.createFile(workspace, "hello.txt", content);

        String catResult = AgentTools.runCommand(workspace, cacheDirectory,
                "cat", Collections.singletonList("hello.txt"));
        String wcResult = AgentTools.runCommand(workspace, cacheDirectory,
                "wc", Collections.singletonList("hello.txt"));
        String lsResult = AgentTools.runCommand(workspace, cacheDirectory,
                "ls", Collections.emptyList());
        String pwdResult = AgentTools.runCommand(workspace, cacheDirectory, "pwd",
                Collections.emptyList());
        String rejectedResult = AgentTools.runCommand(workspace, cacheDirectory, "sh",
                Arrays.asList("-c", "touch should-not-exist"));

        assertTrue(catResult.contains(content.trim()));
        assertTrue(wcResult.startsWith("Exit code 0:"));
        assertTrue(lsResult.contains("hello.txt"));
        assertTrue(pwdResult.contains(workspace.getCanonicalPath()));
        assertTrue(rejectedResult.contains("only pwd, ls, cat, and wc are allowed"));
        assertFalse(new File(workspace, "should-not-exist").exists());
    }

    @Test
    public void rejectsCommandPathsThatEscapeWorkspace() throws Exception {
        try {
            AgentTools.runCommand(workspace, cacheDirectory,
                    "cat", Collections.singletonList("../outside.txt"));
            fail("Expected the command path to be rejected.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Path must stay inside the private workspace"));
        }
    }

    @Test
    public void rejectsCommandOutputForFilesOverTheLimit() throws Exception {
        Files.write(new File(workspace, "large.txt").toPath(),
                repeat('a', AgentTools.MAX_COMMAND_OUTPUT_BYTES + 1).getBytes(StandardCharsets.UTF_8));

        String result = AgentTools.runCommand(workspace, cacheDirectory,
                "cat", Collections.singletonList("large.txt"));

        assertTrue(result.contains("file must exist and be at most 8 KiB"));
    }

    @Test
    public void actionsDoNotRunWithoutApproval() {
        AtomicBoolean actionRan = new AtomicBoolean(false);

        boolean started = AgentTools.runApprovedAction(false, () -> actionRan.set(true));

        assertFalse(started);
        assertFalse(actionRan.get());
    }

    @Test
    public void approvedActionsRun() {
        AtomicBoolean actionRan = new AtomicBoolean(false);

        boolean started = AgentTools.runApprovedAction(true, () -> actionRan.set(true));

        assertTrue(started);
        assertTrue(actionRan.get());
    }

    @Test
    public void acceptsValidCalendarEventAndNormalizesItsTitle() {
        AgentTools.CalendarEvent event = AgentTools.validateCalendarEvent("  Planning  ",
                "2026-11-13T09:00:00Z", "2026-11-13T10:00:00Z");

        assertEquals("Planning", event.title);
        assertEquals(1794560400000L, event.startMillis);
        assertEquals(1794564000000L, event.endMillis);
    }

    @Test
    public void rejectsInvalidCalendarEventTimesAndTitles() {
        assertInvalidEvent("", "2026-11-13T09:00:00Z", "2026-11-13T10:00:00Z");
        assertInvalidEvent("Meeting", "2026-11-13T10:00:00Z", "2026-11-13T09:00:00Z");
        assertInvalidEvent("Meeting", "not-a-date", "2026-11-13T10:00:00Z");
    }

    private void assertInvalidEvent(String title, String start, String end) {
        try {
            AgentTools.validateCalendarEvent(title, start, end);
            fail("Expected invalid event to be rejected.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }

    private String repeat(char value, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, value);
        return new String(characters);
    }
}
