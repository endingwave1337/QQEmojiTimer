package com.endingwave.qqemoji.config;

import android.content.Context;
import android.database.Cursor;

import de.robv.android.xposed.XSharedPreferences;

/**
 * 运行在 QQ 进程内的配置读取。
 * 优先 XSharedPreferences（依赖清单里的 xposedsharedprefs），失败则回退 ContentProvider。
 */
public class RemoteConfig {

    public static final String MODULE_PKG = "com.endingwave.qqemoji";

    public static Config load(Context ctx) {
        Config cfg = null;
        long pending = 0L;

        try {
            XSharedPreferences sp = new XSharedPreferences(MODULE_PKG, Prefs.NAME);
            sp.reload();
            String json = sp.getString(Prefs.KEY_JSON, null);
            if (json != null && !json.isEmpty()) {
                cfg = Config.fromJson(json);
                pending = sp.getLong(Prefs.KEY_PENDING, 0L);
            }
        } catch (Throwable ignored) {
            // 回退到 ContentProvider
        }

        if (cfg == null) {
            Cursor cursor = null;
            try {
                cursor = ctx.getContentResolver().query(Prefs.CONTENT_URI, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    cfg = Config.fromJson(cursor.getString(0));
                    pending = cfg.pendingTriggerAt;
                }
            } catch (Throwable ignored) {
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        if (cfg == null) {
            cfg = new Config();
        }
        if (pending > 0L) {
            cfg.pendingTriggerAt = pending;
        }
        return cfg;
    }
}
