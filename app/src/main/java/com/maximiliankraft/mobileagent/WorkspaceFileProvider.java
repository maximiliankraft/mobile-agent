package com.maximiliankraft.mobileagent;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class WorkspaceFileProvider extends ContentProvider {
    private static final String FILE_PATH = "file";

    static Uri uriForFile(String authority, File file) throws IOException {
        File canonicalFile = file.getCanonicalFile();
        if (!canonicalFile.isFile() || !canonicalFile.canRead()) {
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
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Workspace files are read-only.");
        }
        return ParcelFileDescriptor.open(resolveFile(uri), ParcelFileDescriptor.MODE_READ_ONLY);
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
            if (!requested.isAbsolute() || !canonicalFile.equals(requested)
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
        throw new UnsupportedOperationException("Workspace file provider does not support queries.");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Workspace file provider is read-only.");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Workspace file provider is read-only.");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Workspace file provider is read-only.");
    }
}
