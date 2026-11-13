package com.maximiliankraft.mobileagent;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class WorkspaceFileProvider extends ContentProvider {
    private static final String FILE_PATH = "file";

    static Uri uriForFile(String authority, File workspaceDirectory, File file) throws IOException {
        File workspace = workspaceDirectory.getCanonicalFile();
        File canonicalFile = file.getCanonicalFile();
        if (!canonicalFile.toPath().startsWith(workspace.toPath())
                || canonicalFile.equals(workspace)
                || !canonicalFile.isFile() || !canonicalFile.canRead()) {
            throw new IOException("Path is not a readable file: " + canonicalFile.getPath());
        }
        String encodedPath = Base64.encodeToString(canonicalFile.getPath()
                        .getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return new Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendPath(FILE_PATH)
                .appendPath(encodedPath)
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        try {
            return AgentTools.mimeTypeForFileName(resolveFile(uri).getName());
        } catch (FileNotFoundException exception) {
            return null;
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolveFile(uri);
        if ("r".equals(mode)) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if (!"w".equals(mode) && !"wt".equals(mode) && !"wa".equals(mode)
                && !"rw".equals(mode) && !"rwt".equals(mode)) {
            throw new FileNotFoundException("Unsupported workspace file mode.");
        }
        if (!file.canWrite()) {
            throw new FileNotFoundException("Workspace file is not writable.");
        }
        try {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
        } catch (IllegalArgumentException exception) {
            FileNotFoundException failure = new FileNotFoundException(
                    "Unsupported workspace file mode.");
            failure.initCause(exception);
            throw failure;
        }
    }

    private File resolveFile(Uri uri) throws FileNotFoundException {
        if (uri == null || uri.getPathSegments().size() != 2
                || !FILE_PATH.equals(uri.getPathSegments().get(0))) {
            throw new FileNotFoundException("Invalid workspace file URI.");
        }
        try {
            byte[] decodedPath = Base64.decode(uri.getPathSegments().get(1),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            File requested = new File(new String(decodedPath, StandardCharsets.UTF_8));
            File canonicalFile = requested.getCanonicalFile();
            File workspace = AgentTools.workspaceDirectory(Environment.getExternalStorageDirectory());
            if (!requested.isAbsolute() || !canonicalFile.equals(requested)
                    || !canonicalFile.toPath().startsWith(workspace.toPath())
                    || canonicalFile.equals(workspace)
                    || !canonicalFile.isFile() || !canonicalFile.canRead()) {
                throw new FileNotFoundException("Path is not a readable file.");
            }
            return canonicalFile;
        } catch (IllegalArgumentException | IOException exception) {
            FileNotFoundException failure = new FileNotFoundException("Invalid workspace file path.");
            failure.initCause(exception);
            throw failure;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = resolveFile(uri);
        } catch (FileNotFoundException exception) {
            throw new IllegalArgumentException("Invalid workspace file URI.", exception);
        }
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                values[i] = file.getName();
            } else if (OpenableColumns.SIZE.equals(columns[i])) {
                values[i] = file.length();
            }
        }
        cursor.addRow(values);
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Workspace file provider does not support inserts.");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Workspace file provider does not support deletes.");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Workspace file provider does not support updates.");
    }
}
