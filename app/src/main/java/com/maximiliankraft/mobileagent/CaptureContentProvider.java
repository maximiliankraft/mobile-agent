package com.maximiliankraft.mobileagent;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public final class CaptureContentProvider extends ContentProvider {
    private static final String CAPTURE_PATH = "capture";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (uri == null || uri.getPathSegments().size() != 2
                || !CAPTURE_PATH.equals(uri.getPathSegments().get(0))) {
            throw new FileNotFoundException("Invalid capture URI.");
        }
        String filename = uri.getPathSegments().get(1);
        if (!filename.matches("capture-[A-Za-z0-9._-]+\\.jpg")) {
            throw new FileNotFoundException("Invalid capture filename.");
        }
        File pictures = getContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (pictures == null) {
            throw new FileNotFoundException("App-specific picture storage is unavailable.");
        }
        try {
            File root = pictures.getCanonicalFile();
            File target = new File(root, filename).getCanonicalFile();
            if (!target.toPath().startsWith(root.toPath())) {
                throw new FileNotFoundException("Capture path is outside the permitted directory.");
            }
            int flags;
            if ("w".equals(mode) || "wt".equals(mode) || "rwt".equals(mode)) {
                flags = ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
                        | ("rwt".equals(mode) ? ParcelFileDescriptor.MODE_READ_WRITE
                        : ParcelFileDescriptor.MODE_WRITE_ONLY);
            } else if ("rw".equals(mode)) {
                flags = ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_READ_WRITE;
            } else if ("r".equals(mode)) {
                flags = ParcelFileDescriptor.MODE_READ_ONLY;
            } else {
                throw new FileNotFoundException("Unsupported capture file mode.");
            }
            return ParcelFileDescriptor.open(target, flags);
        } catch (IOException exception) {
            throw new FileNotFoundException(exception.getMessage());
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException("Capture provider does not support queries.");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Capture provider does not support inserts.");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Capture provider does not support deletes.");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Capture provider does not support updates.");
    }
}
