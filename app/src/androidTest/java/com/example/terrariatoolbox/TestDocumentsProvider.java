package com.example.terrariatoolbox;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Controlled ordinary documents in the test APK, with no game-directory access. */
public class TestDocumentsProvider extends DocumentsProvider {
    private boolean readOnly;
    private boolean denyRead;
    private boolean denyExportRead;
    private String importName = "config.json";
    private final String[] columns = { Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE };
    @Override public boolean onCreate() { return true; }
    @Override public boolean isChildDocument(String parent, String id) {
        return "root".equals(parent) && ("config".equals(id) || "export".equals(id));
    }
    @Override public Cursor queryRoots(String[] projection) {
        String[] names = projection != null ? projection : new String[] { Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES };
        MatrixCursor cursor = new MatrixCursor(names);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String name : names) {
            Object value = null;
            if (Root.COLUMN_ROOT_ID.equals(name) || Root.COLUMN_DOCUMENT_ID.equals(name)) value = "root";
            else if (Root.COLUMN_TITLE.equals(name)) value = "Toolbox test documents";
            else if (Root.COLUMN_FLAGS.equals(name)) value = Root.FLAG_SUPPORTS_CREATE;
            else if (Root.COLUMN_MIME_TYPES.equals(name)) value = "*/*";
            row.add(value);
        }
        return cursor;
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        String[] names = projection != null ? projection : columns;
        MatrixCursor cursor = new MatrixCursor(names);
        append(cursor, id, names);
        return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        String[] names = projection != null ? projection : columns;
        MatrixCursor cursor = new MatrixCursor(names);
        if ("root".equals(parent)) append(cursor, "config", names);
        return cursor;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (denyRead && !mode.contains("w")) throw new SecurityException("test revoked");
        if (denyExportRead && "export".equals(id) && !mode.contains("w")) throw new SecurityException("test export readback failed");
        if (readOnly && "config".equals(id) && mode.contains("w")) throw new FileNotFoundException("test read only");
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        write(file("export"), new byte[0]);
        return "export";
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("test.reset".equals(method)) {
            readOnly = extras != null && extras.getBoolean("readOnly");
            denyRead = extras != null && extras.getBoolean("denyRead");
            denyExportRead = extras != null && extras.getBoolean("denyExportRead");
            importName = extras != null ? extras.getString("name", "config.json") : "config.json";
            byte[] bytes = extras != null ? extras.getByteArray("bytes") : null;
            if (bytes == null) bytes = "{\"Language\":1}".getBytes(StandardCharsets.UTF_8);
            try {
                write(file("config"), bytes);
                write(file("export"), "old output with more bytes".getBytes(StandardCharsets.UTF_8));
            } catch (FileNotFoundException error) { throw new IllegalStateException(error); }
            return new Bundle();
        }
        return super.call(method, arg, extras);
    }
    private File file(String id) throws FileNotFoundException {
        if (!"config".equals(id) && !"export".equals(id)) throw new FileNotFoundException("invalid test document");
        File folder = new File(getContext().getFilesDir(), "test-documents");
        folder.mkdirs();
        return new File(folder, id + ".json");
    }
    private void write(File file, byte[] bytes) throws FileNotFoundException {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
        catch (IOException error) { throw new FileNotFoundException("test write failed"); }
    }
    private void append(MatrixCursor cursor, String id, String[] names) throws FileNotFoundException {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String name : names) {
            Object value = null;
            if (Document.COLUMN_DOCUMENT_ID.equals(name)) value = id;
            else if (Document.COLUMN_DISPLAY_NAME.equals(name)) value = "config".equals(id) ? importName : "root".equals(id) ? "Test folder" : "export.json";
            else if (Document.COLUMN_MIME_TYPE.equals(name)) value = "root".equals(id) ? Document.MIME_TYPE_DIR : "application/json";
            else if (Document.COLUMN_FLAGS.equals(name)) value = "root".equals(id) ? Document.FLAG_DIR_SUPPORTS_CREATE : readOnly && "config".equals(id) ? 0 : Document.FLAG_SUPPORTS_WRITE;
            else if (Document.COLUMN_SIZE.equals(name)) value = "root".equals(id) ? 0 : file(id).length();
            row.add(value);
        }
    }
}
