package com.endingwave.qqemoji.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 备用触发入口（adb 广播、其它自动化 App、Tasker 等）。 */
public class TriggerReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Fire.trigger(context.getApplicationContext(), false);
    }
}
