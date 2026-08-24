package com.locatube.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class ApkFileProvider extends ContentProvider {

    private File baseDir;

    @Override
    public boolean onCreate() {
        baseDir = getContext() == null ? null : getContext().getExternalFilesDir(null);
        return baseDir != null;
    }

    private File fichier(Uri uri) throws FileNotFoundException {
        if (baseDir == null) {
            throw new FileNotFoundException();
        }
        String nom = uri.getLastPathSegment();
        if (nom == null || nom.contains("/") || nom.contains("\\")) {
            throw new FileNotFoundException(nom);
        }
        File f = new File(baseDir, nom);
        try {
            if (!f.getCanonicalPath().startsWith(baseDir.getCanonicalPath())) {
                throw new FileNotFoundException(nom);
            }
        } catch (IOException e) {
            throw new FileNotFoundException(nom);
        }
        if (!f.isFile()) {
            throw new FileNotFoundException(nom);
        }
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fichier(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = fichier(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        MatrixCursor mc = new MatrixCursor(projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection);
        Object[] row = new Object[mc.getColumnCount()];
        for (int i = 0; i < row.length; i++) {
            String col = mc.getColumnName(i);
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                row[i] = f.getName();
            } else if (OpenableColumns.SIZE.equals(col)) {
                row[i] = f.length();
            } else {
                row[i] = null;
            }
        }
        mc.addRow(row);
        return mc;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
