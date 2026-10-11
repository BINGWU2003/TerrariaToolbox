package com.example.terrariatoolbox;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Pure Java because this test-APK process does not inherit the target APK's Kotlin runtime. */
public class TestControlProvider extends ContentProvider {
    @Override public boolean onCreate() {
        getContext().grantUriPermission("com.example.terrariatoolbox",
            Uri.parse("content://com.example.terrariatoolbox.tests.documents"),
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return true;
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"test.reset".equals(method)) throw new IllegalArgumentException("test method");
        Uri root = Uri.parse("content://com.example.terrariatoolbox.tests.documents");
        getContext().revokeUriPermission(root, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        if (extras == null || !extras.getBoolean("readGrantOnly")) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        getContext().grantUriPermission("com.example.terrariatoolbox", root, flags);
        return getContext().getContentResolver().call("com.example.terrariatoolbox.tests.documents", method, arg, extras);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
