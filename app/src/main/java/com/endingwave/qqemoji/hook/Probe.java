package com.endingwave.qqemoji.hook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 探测模式：把关键类的方法签名打到 LSPosed 日志，方便版本升级后重新适配。
 * 日志关键字：QQEmojiTimer。
 */
public class Probe {

    private static final String[] CANDIDATES = new String[]{
            "com.tencent.qqnt.msg.f",
            "com.tencent.qqnt.kernel.api.IKernelService",
            "com.tencent.qqnt.kernel.api.af",
            "com.tencent.qqnt.kernel.api.ak",
            "com.tencent.qqnt.kernel.api.as",
            "com.tencent.qqnt.kernel.api.j",
            "com.tencent.qqnt.kernel.nativeinterface.MsgElement",
            "com.tencent.qqnt.kernel.nativeinterface.TextElement",
            "com.tencent.qqnt.kernel.nativeinterface.PicElement",
            "com.tencent.qqnt.kernel.nativeinterface.FaceElement",
            "com.tencent.qqnt.kernel.nativeinterface.MarketFaceElement",
            "com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo",
            "com.tencent.qqnt.kernelpublic.nativeinterface.Contact",
            "com.tencent.aio.main.activity.ChatActivity",
            "com.tencent.mobileqq.activity.ChatActivity",
            "com.tencent.mobileqq.activity.ChatActivityFacade",
            "com.tencent.mobileqq.data.RecentUser"
    };

    private static final String[] KEYWORDS = new String[]{
            "Emoticon", "Emoji", "MarketFace", "AIOMsg", "SendMsg", "RecentContact"
    };

    private final ClassLoader cl;

    public Probe(ClassLoader cl) {
        this.cl = cl;
    }

    public void start() {
        MainHook.log("=========== 探测模式开始 ===========");
        for (String name : CANDIDATES) {
            dumpClass(name);
        }
        scanLoadedClasses();
        MainHook.log("=========== 探测模式结束 ===========");
    }

    private void dumpClass(String name) {
        try {
            Class<?> cls = Class.forName(name, false, cl);
            MainHook.log("### 命中 " + name);
            for (Field f : cls.getDeclaredFields()) {
                MainHook.log("   F " + Modifier.toString(f.getModifiers()) + " " + f.getType().getSimpleName() + " " + f.getName());
            }
            for (Method m : cls.getDeclaredMethods()) {
                StringBuilder sb = new StringBuilder();
                for (Class<?> p : m.getParameterTypes()) {
                    sb.append(p.getSimpleName()).append(',');
                }
                MainHook.log("   M " + Modifier.toString(m.getModifiers()) + " " + m.getReturnType().getSimpleName()
                        + " " + m.getName() + "(" + sb + ")");
            }
        } catch (Throwable t) {
            MainHook.log("### 未找到 " + name);
        }
    }

    /** 顺带记录加载过的、名字里带关键词的类，便于发现新版真实类名。 */
    private void scanLoadedClasses() {
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String name = (String) param.args[0];
                    if (name == null || name.startsWith("[")) {
                        return;
                    }
                    for (String kw : KEYWORDS) {
                        if (name.contains(kw)) {
                            MainHook.log("CLASS " + name);
                            return;
                        }
                    }
                }
            });
        } catch (Throwable t) {
            MainHook.log(t);
        }
    }
}
