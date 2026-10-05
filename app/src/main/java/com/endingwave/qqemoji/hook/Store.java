package com.endingwave.qqemoji.hook;

import android.content.Context;
import android.content.SharedPreferences;

/** 注入进程内的少量状态（写在 QQ 自己的数据目录里）。 */
final class Store {

    private static final String NAME = "qq_emoji_timer_state";
    private static final String KEY_HANDLED = "last_handled_trigger";

    private Store() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static long handled(Context ctx) {
        return sp(ctx).getLong(KEY_HANDLED, 0L);
    }

    static void setHandled(Context ctx, long value) {
        sp(ctx).edit().putLong(KEY_HANDLED, value).apply();
    }
}
