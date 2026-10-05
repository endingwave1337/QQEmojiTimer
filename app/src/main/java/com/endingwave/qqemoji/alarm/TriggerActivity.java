package com.endingwave.qqemoji.alarm;

import android.app.Activity;
import android.os.Bundle;

import com.endingwave.qqemoji.config.Config;
import com.endingwave.qqemoji.config.Prefs;

/**
 * 闹钟触发点：交给 Fire 去处理（QQ 进程活着就不切前台）。
 */
public class TriggerActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Config cfg = Prefs.load(this);
            Fire.trigger(this, cfg.repeatDaily);
            if (!cfg.repeatDaily) {
                cfg.enabled = false;
                Prefs.save(this, cfg);
            }
        } catch (Throwable ignored) {
        } finally {
            finish();
        }
    }
}
