package com.endingwave.qqemoji.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.endingwave.qqemoji.config.Config;
import com.endingwave.qqemoji.config.Prefs;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** 用精确闹钟把模块 App 自己拉起，再由它唤醒 QQ。支持多个时间点。 */
public class Scheduler {

    private static final int BASE_REQUEST_CODE = 1100;
    /** 最多支持多少个时间点 */
    public static final int MAX_SLOTS = 24;

    public static PendingIntent pendingIntent(Context ctx, int slot) {
        Intent intent = new Intent(ctx, TriggerActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("slot", slot);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(ctx, BASE_REQUEST_CODE + slot, intent, flags);
    }

    public static void cancelAll(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        for (int i = 0; i < MAX_SLOTS; i++) {
            am.cancel(pendingIntent(ctx, i));
        }
    }

    /** 某个时间点下一次触发的时间戳 */
    public static long nextTriggerAt(int hour, int minute) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 2000L) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return cal.getTimeInMillis();
    }

    /** 兼容旧调用：返回最近的一次触发时间 */
    public static long nextTriggerAt(Config cfg) {
        long best = Long.MAX_VALUE;
        for (int[] hm : cfg.timeList()) {
            long t = nextTriggerAt(hm[0], hm[1]);
            if (t < best) {
                best = t;
            }
        }
        return best == Long.MAX_VALUE ? nextTriggerAt(cfg.hour, cfg.minute) : best;
    }

    public static void schedule(Context ctx) {
        cancelAll(ctx);
        Config cfg = Prefs.load(ctx);
        if (!cfg.enabled) {
            return;
        }
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        List<int[]> slots = cfg.timeList();
        for (int i = 0; i < slots.size() && i < MAX_SLOTS; i++) {
            int[] hm = slots.get(i);
            long at = nextTriggerAt(hm[0], hm[1]);
            setAlarm(am, at, pendingIntent(ctx, i));
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < slots.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.US, "%02d:%02d", slots.get(i)[0], slots.get(i)[1]));
        }
        android.util.Log.i("QQEmojiTimer", "已排定 " + slots.size() + " 个时间点: " + sb);
    }

    private static void setAlarm(AlarmManager am, long at, PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }
}
