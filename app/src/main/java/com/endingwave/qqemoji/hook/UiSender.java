package com.endingwave.qqemoji.hook;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import com.endingwave.qqemoji.config.Config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 兜底方案：打开聊天界面 -> 填入内容 -> 点击发送。
 * QQ 9.3.70 的新版会话界面是 com.tencent.aio.main.activity.ChatActivity。
 */
public class UiSender implements Application.ActivityLifecycleCallbacks {

    /**
     * 注意：新版 com.tencent.aio.main.activity.ChatActivity 需要 AIO 自己的参数，
     * 用 uin/uintype 硬塞进去会让 QQ 崩溃，所以这里只用旧入口。
     */
    private static final String[] CHAT_ACTIVITY_CANDIDATES = new String[]{
            "com.tencent.mobileqq.activity.SplashActivity",
            "com.tencent.mobileqq.activity.ChatActivity",
            "com.tencent.mobileqq.activity.AIOActivity"
    };

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> queue = new ArrayList<String>();

    private Config cfg;
    private String currentUin;
    private boolean busy;
    private boolean finishing;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            MainHook.log("等待聊天界面超时: " + currentUin);
            finishStep(null);
        }
    };

    public UiSender(Context ctx) {
        this.ctx = ctx;
        if (ctx instanceof Application) {
            ((Application) ctx).registerActivityLifecycleCallbacks(this);
        }
    }

    public void sendSequentially(List<String> uins, Config cfg) {
        this.cfg = cfg;
        queue.clear();
        queue.addAll(uins);
        MainHook.log("界面模式队列: " + uins);
        if (!busy) {
            next();
        }
    }

    private void next() {
        if (queue.isEmpty()) {
            busy = false;
            currentUin = null;
            MainHook.log("界面模式发送结束");
            return;
        }
        busy = true;
        finishing = false;
        currentUin = queue.remove(0);
        openChat(currentUin);
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, 25000L);
    }

    private void openChat(String uin) {
        for (String cls : CHAT_ACTIVITY_CANDIDATES) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(MainHook.QQ_PACKAGE, cls));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                intent.putExtra("uin", uin);
                intent.putExtra("uintype", 0);
                intent.putExtra("uinname", uin);
                intent.putExtra("chatType", 1);
                intent.putExtra("open_chat_aio", true);
                ctx.startActivity(intent);
                MainHook.log("已打开会话界面 " + cls + " -> " + uin);
                return;
            } catch (Throwable t) {
                MainHook.log("打开 " + cls + " 失败: " + t);
            }
        }
        MainHook.log("所有候选会话界面都打不开: " + uin);
        finishStep(null);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        if (!busy || currentUin == null || finishing) {
            return;
        }
        String name = activity.getClass().getName();
        boolean isChat = false;
        for (String cls : CHAT_ACTIVITY_CANDIDATES) {
            if (cls.equals(name)) {
                isChat = true;
                break;
            }
        }
        if (!isChat && !name.contains("ChatActivity") && !name.contains("AIOActivity")) {
            return;
        }
        String intentUin = null;
        try {
            Intent it = activity.getIntent();
            if (it != null) {
                intentUin = it.getStringExtra("uin");
            }
        } catch (Throwable ignored) {
        }
        if (intentUin != null && !intentUin.equals(currentUin)) {
            return;
        }
        finishing = true;
        long delay = cfg == null ? 1500L : Math.max(300L, cfg.sendDelayMs);
        MainHook.log("聊天界面就绪， " + delay + "ms 后发送给 " + currentUin);
        final Activity target = activity;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                performSend(target);
            }
        }, delay);
    }

    private void performSend(final Activity activity) {
        try {
            View root = activity.getWindow().getDecorView();
            EditText input = findInput(root);
            if (input == null) {
                MainHook.log("找不到输入框，放弃本次发送（可增大等待毫秒数）");
                finishStep(activity);
                return;
            }
            String text = null;
            if (cfg != null) {
                if (cfg.isText()) {
                    text = cfg.payload;
                } else if (cfg.fallbackText != null && !cfg.fallbackText.isEmpty()) {
                    MainHook.log("界面模式只支持文字/表情代码，本次使用兜底文字");
                    text = cfg.fallbackText;
                }
            }
            if (text == null) {
                MainHook.log("没有可发送的文本，取消");
                finishStep(activity);
                return;
            }
            input.requestFocus();
            input.setText(text);
            if (input.getText() != null) {
                input.setSelection(input.getText().length());
            }
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    View btn = findSendButton(activity.getWindow().getDecorView(), input);
                    if (btn == null) {
                        MainHook.log("找不到发送按钮，放弃本次发送");
                    } else {
                        btn.performClick();
                        MainHook.log("已点击发送 -> " + currentUin);
                    }
                    finishStep(activity);
                }
            }, 800L);
        } catch (Throwable t) {
            MainHook.log(t);
            finishStep(activity);
        }
    }

    private void finishStep(final Activity activity) {
        main.removeCallbacks(watchdog);
        if (activity != null && cfg != null && cfg.closeChatAfterSend) {
            try {
                activity.finish();
            } catch (Throwable ignored) {
            }
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                next();
            }
        }, 2000L);
    }

    private static EditText findInput(View root) {
        ArrayDeque<View> queue = new ArrayDeque<View>();
        queue.add(root);
        EditText fallback = null;
        while (!queue.isEmpty()) {
            View v = queue.poll();
            if (v instanceof EditText && v.isShown() && v.isEnabled()) {
                if (idNameContains(v, "input") || idNameContains(v, "edit")) {
                    return (EditText) v;
                }
                if (fallback == null) {
                    fallback = (EditText) v;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    queue.add(g.getChildAt(i));
                }
            }
        }
        return fallback;
    }

    private static View findSendButton(View root, EditText input) {
        ArrayDeque<View> queue = new ArrayDeque<View>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View v = queue.poll();
            if (v != input && v.isShown() && v.isEnabled() && v.isClickable()) {
                if (idNameContains(v, "fun_btn")
                        || idNameContains(v, "send")
                        || idNameContains(v, "chat_send")) {
                    return v;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    queue.add(g.getChildAt(i));
                }
            }
        }
        return null;
    }

    private static boolean idNameContains(View v, String key) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) {
                return false;
            }
            String name = v.getResources().getResourceEntryName(id);
            return name != null && name.toLowerCase().contains(key);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }

    /** 供 Engine 判断是否需要界面模式 */
    public boolean holding() {
        return busy;
    }

}
