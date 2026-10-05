package com.endingwave.qqemoji.hook;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口。作用域：com.tencent.mobileqq（QQ 9.3.70）。
 */
public class MainHook implements IXposedHookLoadPackage {

    public static final String TAG = "QQEmojiTimer";
    public static final String QQ_PACKAGE = "com.tencent.mobileqq";

    public static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    public static void log(Throwable t) {
        XposedBridge.log(TAG + ": " + Log.getStackTraceString(t));
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!QQ_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        // 只在主进程工作（QQ 还有 :MSF、:qzone 等子进程）
        if (lpparam.processName != null && !QQ_PACKAGE.equals(lpparam.processName)) {
            log("跳过子进程 " + lpparam.processName);
            return;
        }

        log("模块已注入 " + lpparam.packageName + " / " + lpparam.processName);

        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Context ctx = (Context) param.args[0];
                        if (ctx == null) {
                            return;
                        }
                        new Engine(ctx, lpparam.classLoader).start();
                    } catch (Throwable t) {
                        log(t);
                    }
                }
            });
        } catch (Throwable t) {
            log(t);
        }
    }
}
