package com.endingwave.qqemoji.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.endingwave.qqemoji.config.Prefs;

/**
 * 触发逻辑。
 *
 * 关键点：**不要无脑把 QQ 拉到前台**（内核模式根本不需要 QQ 界面，
 * 无脑 startActivity 会突然弹出聊天界面）。
 * 做法是先发一个有序广播"问一下" QQ 进程在不在：
 *   - QQ 进程活着 -> 它会在 onReceive 里 setResultCode(ALIVE)，我们什么都不做；
 *   - QQ 进程不在 -> 结果码不是 ALIVE，这时才把 QQ 拉起来（冷启动后由注入代码补发）。
 */
public class Fire {

    public static final String ACTION_FIRE = "com.endingwave.qqemoji.action.FIRE";
    public static final String QQ_PACKAGE = "com.tencent.mobileqq";
    /** QQ 进程活着时回的暗号 */
    public static final int ALIVE_CODE = 0x51E7;
    /** 等 QQ 回话的最长时间，超时按"不在"处理 */
    private static final long PROBE_TIMEOUT_MS = 1500L;

    private Fire() {
    }

    public static void trigger(final Context ctx, boolean rescheduleAfter) {
        final Context app = ctx.getApplicationContext();
        long now = System.currentTimeMillis();
        Prefs.setPendingTrigger(app, now);

        Intent fire = new Intent(ACTION_FIRE);
        fire.setPackage(QQ_PACKAGE);
        fire.putExtra("fireAt", now);

        final Handler main = new Handler(Looper.getMainLooper());
        final boolean[] done = new boolean[]{false};

        final Runnable giveUp = new Runnable() {
            @Override
            public void run() {
                if (done[0]) {
                    return;
                }
                done[0] = true;
                launchQQ(app);
            }
        };

        boolean sent = false;
        try {
            app.sendOrderedBroadcast(fire, null, new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (done[0]) {
                        return;
                    }
                    done[0] = true;
                    main.removeCallbacks(giveUp);
                    if (getResultCode() != ALIVE_CODE) {
                        launchQQ(app);
                    }
                }
            }, main, 0, null, null);
            sent = true;
        } catch (Throwable ignored) {
        }
        if (!sent) {
            try {
                app.sendBroadcast(fire);
            } catch (Throwable ignored) {
            }
            giveUp.run();
        } else {
            main.postDelayed(giveUp, PROBE_TIMEOUT_MS);
        }

        if (rescheduleAfter) {
            Scheduler.schedule(app);
        }
    }

    private static void launchQQ(Context ctx) {
        try {
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(QQ_PACKAGE);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(launch);
                android.util.Log.i("QQEmojiTimer", "QQ 没在运行，已拉起（冷启动后会补发）");
            }
        } catch (Throwable t) {
            android.util.Log.i("QQEmojiTimer", "拉起 QQ 失败: " + t);
        }
    }
}
