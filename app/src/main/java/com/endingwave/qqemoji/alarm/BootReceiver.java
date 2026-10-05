package com.endingwave.qqemoji.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机 / 模块更新后重新排定闹钟。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Scheduler.schedule(context.getApplicationContext());
    }
}
