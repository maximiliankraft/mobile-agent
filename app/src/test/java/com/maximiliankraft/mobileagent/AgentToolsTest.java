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
    private File storageRoot;
    private File workspace;
    private File cacheDirectory;

    @Before
    public void setUp() throws Exception {
        filesDirectory = temporaryFolder.newFolder("app-files");
        storageRoot = temporaryFolder.newFolder("shared-storage");
        cacheDirectory = temporaryFolder.newFolder("cache");
        workspace = AgentTools.workspaceDirectory(storageRoot);
    }

    @Test
    public void createsUtf8FilesInNestedWorkspaceDirectories() throws Exception {
        String result = AgentTools.createFile(workspace, "notes/todo.txt", "café");

        assertTrue(result.startsWith("Created notes/todo.txt"));
        assertEquals("café", new String(Files.readAllBytes(
                new File(workspace, "notes/todo.txt").toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void defaultWorkspaceLivesInVisibleSharedStorage() throws Exception {
        assertEquals(new File(storageRoot, "mobileagent").getCanonicalFile(), workspace);
    }

    @Test
    public void rejectsWorkspaceSymlinksThatEscapeSharedStorage() throws Exception {
        File linkRoot = temporaryFolder.newFolder("linked-storage");
        File externalTarget = temporaryFolder.newFolder("external-target");
        Files.createSymbolicLink(new File(linkRoot, "mobileagent").toPath(),
                externalTarget.toPath());

        try {
            AgentTools.workspaceDirectory(linkRoot);
            fail("Expected a workspace symlink outside shared storage to be rejected.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("workspace"));
        }
    }

    @Test
    public void resolvesOmittedAndAbsolutePathsWithinSharedStorage() throws Exception {
        assertEquals(workspace.getCanonicalFile(),
                AgentTools.resolvePath(storageRoot, workspace, "").getCanonicalFile());
        File requested = new File(storageRoot, "Documents/notes.txt");
        assertEquals(requested.getCanonicalFile(),
                AgentTools.resolvePath(storageRoot, workspace, requested.getPath()).getCanonicalFile());
    }

    @Test
    public void readsWritesListsAndCreatesDirectoriesWithinSharedStorage() throws Exception {
        String path = "notes/todo.txt";
        assertTrue(AgentTools.writeTextFile(storageRoot, workspace, path, "café")
                .contains("Written"));
        assertEquals("café", AgentTools.readTextFile(storageRoot, workspace, path));
        assertTrue(AgentTools.listDirectory(storageRoot, workspace, "notes").contains("todo.txt"));
        assertTrue(AgentTools.createDirectory(storageRoot, workspace, "skills/example")
                .contains("Created directory"));
        assertTrue(new File(workspace, "skills/example").isDirectory());
    }

    @Test
    public void rejectsRelativePathsOutsideWorkspace() throws Exception {
        try {
            AgentTools.resolvePath(storageRoot, workspace, "../escape.txt");
            fail("Expected a relative path outside the workspace to be rejected.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("workspace"));
        }
    }

    @Test
    public void allowsExplicitAbsolutePathsOutsideSharedStorage() throws Exception {
        File requested = temporaryFolder.newFile("approved-device-file.txt");

        File resolved = AgentTools.resolvePath(storageRoot, workspace, requested.getPath());

        assertEquals(requested.getCanonicalFile(), resolved);
    }

    @Test
    public void resolvesOnlyReadableRegularFilesForOpening() throws Exception {
        File requested = new File(workspace, "contact.vcf");
        Files.write(requested.toPath(), "BEGIN:VCARD".getBytes(StandardCharsets.UTF_8));

        assertEquals(requested.getCanonicalFile(),
                AgentTools.resolveReadableFile(workspace, "contact.vcf"));
        try {
            AgentTools.resolveReadableFile(workspace, "missing.vcf");
            fail("Expected a nonexistent file to be rejected.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("readable file"));
        }
        try {
            AgentTools.resolveReadableFile(workspace, "");
            fail("Expected a directory to be rejected.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("readable file"));
        }
        try {
            AgentTools.resolveReadableFile(workspace, requested.getAbsolutePath());
            fail("Expected an absolute path to be rejected.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("relative workspace path"));
        }
    }

    @Test
    public void editableFilesAcceptRelativeAndAbsolutePathsOnlyInsideWorkspace()
            throws Exception {
        File requested = new File(workspace, "notes.md");
        Files.write(requested.toPath(), "notes".getBytes(StandardCharsets.UTF_8));

        assertEquals(requested.getCanonicalFile(),
                AgentTools.resolveEditableFile(workspace, "notes.md"));
        assertEquals(requested.getCanonicalFile(),
                AgentTools.resolveEditableFile(workspace, requested.getAbsolutePath()));

        File outside = temporaryFolder.newFile("outside.md");
        try {
            AgentTools.resolveEditableFile(workspace, outside.getAbsolutePath());
            fail("Expected an absolute path outside the workspace to be rejected.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("inside the agent workspace"));
        }

        File outsideLink = new File(workspace, "outside-link.md");
        Files.createSymbolicLink(outsideLink.toPath(), outside.toPath());
        try {
            AgentTools.resolveEditableFile(workspace, "outside-link.md");
            fail("Expected a workspace symlink to an outside file to be rejected.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("inside the agent workspace"));
        }
    }

    @Test
    public void derivesMimeTypesForCommonWorkspaceOutputFiles() {
        assertEquals("text/x-vcard", AgentTools.mimeTypeForFileName("contact.VCF"));
        assertEquals("text/calendar", AgentTools.mimeTypeForFileName("event.ics"));
        assertEquals("application/pdf", AgentTools.mimeTypeForFileName("report.pdf"));
        assertEquals("text/plain", AgentTools.mimeTypeForFileName("notes.txt"));
        assertEquals("text/markdown", AgentTools.mimeTypeForFileName("README.md"));
        assertEquals("application/octet-stream", AgentTools.mimeTypeForFileName("unknown"));
    }

    @Test
    public void absoluteDevicePathsDoNotRequireCreatingTheDefaultWorkspace() throws Exception {
        File requested = temporaryFolder.newFile("device-file-without-workspace.txt");
        File rootWithoutWorkspace = temporaryFolder.newFolder("storage-without-workspace");

        File selectedWorkspace = AgentTools.workspaceForPath(rootWithoutWorkspace,
                requested.getPath());

        assertFalse(selectedWorkspace.exists());
        assertEquals(requested.getCanonicalFile(), AgentTools.resolvePath(rootWithoutWorkspace,
                selectedWorkspace, requested.getPath()));
    }

    @Test
    public void absoluteDevicePathsDoNotDependOnAWorkspaceSymlink() throws Exception {
        File linkedStorageRoot = temporaryFolder.newFolder("storage-with-linked-workspace");
        File redirectedWorkspace = temporaryFolder.newFolder("redirected-workspace");
        File requested = temporaryFolder.newFile("device-file-outside-linked-workspace.txt");
        Files.createSymbolicLink(new File(linkedStorageRoot, "mobileagent").toPath(),
                redirectedWorkspace.toPath());

        File selectedWorkspace = AgentTools.workspaceForPath(linkedStorageRoot,
                requested.getPath());

        assertEquals(requested.getCanonicalFile(), AgentTools.resolvePath(linkedStorageRoot,
                selectedWorkspace, requested.getPath()));
    }

    @Test
    public void systemPromptGuidesTheAgentToReadNotesAndSkillsOnDemand() {
        String instruction = AgentTools.agentFilesPromptInstruction();

        assertTrue(instruction.contains("user.md"));
        assertTrue(instruction.contains("soul.md"));
        assertTrue(instruction.contains("skills/"));
        assertTrue(instruction.contains("read_file"));
        assertTrue(instruction.contains("open_file"));
        assertTrue(instruction.contains("not loaded automatically"));
        assertTrue(instruction.contains("before stating an action is unsupported"));
    }

    @Test
    public void contactSearchToolOutputDoesNotExposeLocallyDisplayedDetails() {
        String localResults = "Contact matches (2):\nName: Alice Example\nPhone: 555-0101"
                + "\nName: Bob Example\nPhone: 555-0102";

        String toolOutput = AgentTools.contactSearchToolResult(localResults);

        assertTrue(toolOutput.contains("2"));
        assertTrue(toolOutput.contains("not sent to DeepSeek"));
        assertFalse(toolOutput.contains("Alice Example"));
        assertFalse(toolOutput.contains("555-0101"));
        assertFalse(toolOutput.contains("Bob Example"));
        assertFalse(toolOutput.contains("555-0102"));
    }

    @Test
    public void failedOversizeReplacementLeavesExistingFileUnchanged() throws Exception {
        AgentTools.writeTextFile(storageRoot, workspace, "keep.txt", "original");

        String result = AgentTools.writeTextFile(storageRoot, workspace, "keep.txt",
                repeat('x', AgentTools.MAX_FILE_BYTES + 1));

        assertTrue(result.contains("exceeds 16 KiB"));
        assertEquals("original", AgentTools.readTextFile(storageRoot, workspace, "keep.txt"));
    }

    @Test
    public void rejectsOversizeTextReads() throws Exception {
        Files.write(new File(workspace, "large.txt").toPath(),
                repeat('x', AgentTools.MAX_READ_FILE_BYTES + 1).getBytes(StandardCharsets.UTF_8));

        try {
            AgentTools.readTextFile(storageRoot, workspace, "large.txt");
            fail("Expected a file over the read limit to be rejected.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("64 KiB"));
        }
    }

    @Test
    public void boundsDirectoryListingAndMarksTruncation() throws Exception {
        for (int i = 0; i <= AgentTools.MAX_DIRECTORY_ENTRIES; i++) {
            Files.createFile(new File(workspace, String.format("entry-%03d.txt", i)).toPath());
        }

        String listing = AgentTools.listDirectory(storageRoot, workspace, "");

        assertTrue(listing.contains("listing truncated"));
        assertTrue(listing.split("\\n").length <= AgentTools.MAX_DIRECTORY_ENTRIES + 1);
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
        assertTrue(rejectedResult.contains("not in the safe command set"));
        assertFalse(new File(workspace, "should-not-exist").exists());
    }

    @Test
    public void warnsWhenCommandOrArgumentCountIsUnsupported() {
        String unknownCommand = AgentTools.commandPolicyWarning("sh",
                Arrays.asList("-c", "echo unsafe"));
        String tooManyArguments = AgentTools.commandPolicyWarning("ls",
                Arrays.asList(".", "extra"));
        String validCommand = AgentTools.commandPolicyWarning("ls",
                Collections.singletonList("."));

        assertTrue(unknownCommand.contains("not in the safe command set"));
        assertTrue(tooManyArguments.contains("too many arguments"));
        assertEquals(null, validCommand);
    }

    @Test
    public void listsLargeCommandDirectoriesWithBoundedResults() throws Exception {
        for (int i = 0; i <= AgentTools.MAX_DIRECTORY_ENTRIES; i++) {
            Files.createFile(new File(workspace, String.format("f%04d", i)).toPath());
        }

        String result = AgentTools.runCommand(workspace, cacheDirectory, "ls",
                Collections.emptyList());

        assertTrue(result.contains("listing truncated"));
        assertTrue(result.length() < AgentTools.MAX_COMMAND_OUTPUT_BYTES + 100);
    }

    @Test
    public void rejectsCommandPathsThatEscapeWorkspace() throws Exception {
        try {
            AgentTools.runCommand(workspace, cacheDirectory,
                    "cat", Collections.singletonList("../outside.txt"));
            fail("Expected the command path to be rejected.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Path must stay inside the agent workspace"));
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
