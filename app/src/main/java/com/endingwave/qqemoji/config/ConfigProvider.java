package com.endingwave.qqemoji.config;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * 兜底通道：若 XSharedPreferences 不可用，注入到 QQ 进程的代码可以通过
 * content://com.endingwave.qqemoji.config/config 读取 JSON 配置。
 */
public class ConfigProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"json"});
        Config cfg = Prefs.load(getContext());
        cfg.pendingTriggerAt = Prefs.getPendingTrigger(getContext());
        cursor.addRow(new Object[]{cfg.toJson()});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.qqemojitimer.config";
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
