package com.endingwave.qqemoji.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * 模块 App 侧的设置存储。为防止注入进程读到半截配置，写入使用 commit()。
 */
public class Prefs {

    public static final String NAME = "settings";
    public static final String KEY_JSON = "config_json";
    public static final String KEY_PENDING = "pending_trigger_at";

    public static final String AUTHORITY = "com.endingwave.qqemoji.config";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/config");

    public static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static Config load(Context ctx) {
        return Config.fromJson(sp(ctx).getString(KEY_JSON, null));
    }

    public static void save(Context ctx, Config cfg) {
        // XSharedPreferences 读取的是磁盘文件，必须同步落盘
        sp(ctx).edit().putString(KEY_JSON, cfg.toJson()).commit();
    }

    public static long getPendingTrigger(Context ctx) {
        return sp(ctx).getLong(KEY_PENDING, 0L);
    }

    public static void setPendingTrigger(Context ctx, long value) {
        sp(ctx).edit().putLong(KEY_PENDING, value).commit();
    }
}
