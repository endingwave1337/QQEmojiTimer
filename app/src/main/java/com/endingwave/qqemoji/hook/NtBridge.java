package com.endingwave.qqemoji.hook;

import android.net.Uri;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * QQ 9.3.70（NT 内核）直发实现。所有类名/签名都来自对官方 APK 的反编译核对。
 *
 * 元素不再手工 new，而是调用 QQ 自己的公开构造器：
 *     QRoute.api(IMsgUtilApi.class).createTextElement(String)
 *     QRoute.api(IMsgUtilApi.class).createPicElement(String path, boolean quality, int subType)
 *   （AIOObjectTransform 里 QQ 就是这么造的，见 L730 / L678 的 smali）
 *
 * 发送调用（与 MsgService#i1、MsgSendHelperImpl#sendMsg 完全一致）：
 *     f.f().sendMsg(0L, contact, elements, msgAttrs, callback)     ← msgId 传 0
 */
public class NtBridge {

    /** NT 元素类型（已从 MsgUtil 反编译核对） */
    public static final int ELEMENT_TEXT = 1;
    public static final int ELEMENT_PIC = 2;
    public static final int ELEMENT_FACE = 6;
    public static final int ELEMENT_MARKET_FACE = 10;

    /** 好友会话 */
    public static final int CHAT_C2C = 1;

    private static final long CALLBACK_TIMEOUT_MS = 8000L;

    /** 标记"这条是本模块自己发的"，避免被表情录制钩子录下来 */
    private static final ThreadLocal<Boolean> SELF_SEND = new ThreadLocal<Boolean>();

    public static boolean isSelfSend() {
        return Boolean.TRUE.equals(SELF_SEND.get());
    }

    private final ClassLoader cl;
    private Object msgService;
    private Object kernelService;
    private Object uixService;
    private Object msgUtilApi;

    public NtBridge(ClassLoader cl) {
        this.cl = cl;
    }

    public boolean init() {
        try {
            Class<?> entry = cl.loadClass("com.tencent.qqnt.msg.f");
            if (msgService == null) {
                msgService = invokeStatic(entry, "f");
            }
            if (kernelService == null) {
                kernelService = invokeStatic(entry, "i");
            }
            if (uixService == null) {
                uixService = invokeStatic(entry, "k");
            }
        } catch (Throwable t) {
            MainHook.log("NtBridge.init 失败: " + t);
        }
        return msgService != null;
    }

    public boolean isReady() {
        return msgService != null;
    }

    /** QQ 自己的元素构造器 QRoute.api(IMsgUtilApi.class) */
    private Object msgUtilApi() {
        if (msgUtilApi != null) {
            return msgUtilApi;
        }
        try {
            Class<?> qroute = cl.loadClass("com.tencent.mobileqq.qroute.QRoute");
            Class<?> apiCls = cl.loadClass("com.tencent.qqnt.msg.api.IMsgUtilApi");
            Object api = qroute.getMethod("api", Class.class).invoke(null, apiCls);
            if (api != null) {
                msgUtilApi = api;
            }
        } catch (Throwable t) {
            MainHook.log("拿 IMsgUtilApi 失败: " + t);
        }
        return msgUtilApi;
    }

    // ==================== 会话信息（uid 解析） ====================

    public static final class Target {
        public final String uid;
        public final int chatType;
        public final String source;

        Target(String uid, int chatType, String source) {
            this.uid = uid;
            this.chatType = chatType;
            this.source = source;
        }
    }

    /**
     * QQ 号 -> (uid, chatType)。
     * 顺序：会话列表(最可靠) -> UixConvertService.B(long) -> createUidFromTinyId(兜底)
     */
    public Target resolveTarget(String uin, List<RecentContacts.Item> recents) {
        if (recents != null) {
            for (RecentContacts.Item it : recents) {
                if (uin.equals(it.uin) && it.uid != null && !it.uid.isEmpty()) {
                    int chatType = it.chatType > 0 ? it.chatType : CHAT_C2C;
                    MainHook.log("uid 解析[会话列表]: uin=" + uin + " uid=" + it.uid + " chatType=" + chatType);
                    return new Target(it.uid, chatType, "recent");
                }
            }
        }
        String uid = uidFromService(uin);
        if (uid != null && !uid.isEmpty()) {
            MainHook.log("uid 解析[UixConvertService]: uin=" + uin + " uid=" + uid);
            return new Target(uid, CHAT_C2C, "uix");
        }
        String uid2 = uidFromTinyId(uin);
        if (uid2 != null && !uid2.isEmpty()) {
            MainHook.log("uid 解析[createUidFromTinyId]: uin=" + uin + " uid=" + uid2);
            return new Target(uid2, CHAT_C2C, "tinyId");
        }
        MainHook.log("uid 解析失败: uin=" + uin + "（好友可能不在会话/好友列表里）");
        return null;
    }

    /** com.tencent.qqnt.kernel.api.as#B(long) == IUixConvertAdapterApi.getUidFromUin(QQ号) */
    private String uidFromService(String uin) {
        long tiny;
        try {
            tiny = Long.parseLong(uin.trim());
        } catch (Throwable t) {
            return null;
        }
        try {
            if (uixService == null) {
                init();
            }
            Class<?> as = cl.loadClass("com.tencent.qqnt.kernel.api.as");
            Method m = as.getMethod("B", long.class);
            Object r = m.invoke(uixService, Long.valueOf(tiny));
            if (r instanceof String && !((String) r).isEmpty()) {
                return (String) r;
            }
        } catch (Throwable t) {
            MainHook.log("UixConvertService.B 调用失败: " + t);
        }
        return null;
    }

    private String uidFromTinyId(String uin) {
        long tiny;
        try {
            tiny = Long.parseLong(uin.trim());
        } catch (Throwable t) {
            return null;
        }
        try {
            if (msgService == null) {
                init();
            }
            Class<?> af = cl.loadClass("com.tencent.qqnt.kernel.api.af");
            Method m = af.getMethod("createUidFromTinyId", long.class, long.class);
            Object r = m.invoke(msgService, Long.valueOf(tiny), Long.valueOf(0L));
            if (r instanceof String && !((String) r).isEmpty()) {
                return (String) r;
            }
        } catch (Throwable t) {
            MainHook.log("createUidFromTinyId 调用失败: " + t);
        }
        return null;
    }

    // ==================== 发送 ====================

    public String send(String uin, int elementType, String payload, com.endingwave.qqemoji.config.Config cfg,
                       List<RecentContacts.Item> recents) {
        // picAsEmoji 勾选时 = 1（QQ 把 picSubType=1 的图当作表情图，外显 [动画表情]）
        int picSubType = cfg == null ? 0 : cfg.effectivePicSubType();
        if (!isReady() && !init()) {
            return "内核未就绪";
        }
        Target target = resolveTarget(uin, recents);
        if (target == null) {
            return "无法解析 uid";
        }
        try {
            Object element = buildElement(elementType, payload, picSubType);
            if (element == null) {
                return "元素构造失败";
            }
            return sendElement(target, element, uin, cfg == null || cfg.localEcho);
        } catch (Throwable t) {
            MainHook.log(t);
            return "构造/发送异常: " + t;
        }
    }

    /**
     * 元素构造：优先用 QQ 自己的 IMsgUtilApi，失败再手工兜底。
     */
    private Object buildElement(int elementType, String payload, int picSubType) throws Throwable {
        Object api = msgUtilApi();
        if (api != null) {
            try {
                Class<?> apiCls = cl.loadClass("com.tencent.qqnt.msg.api.IMsgUtilApi");
                if (elementType == ELEMENT_PIC) {
                    Method m = apiCls.getMethod("createPicElement", String.class, boolean.class, int.class);
                    Object el = m.invoke(api, payload, Boolean.FALSE, Integer.valueOf(picSubType));
                    if (el != null) {
                        MainHook.log("元素[IMsgUtilApi.createPicElement] " + payload);
                        return el;
                    }
                } else if (elementType == ELEMENT_TEXT) {
                    Method m = apiCls.getMethod("createTextElement", String.class);
                    Object el = m.invoke(api, payload);
                    if (el != null) {
                        MainHook.log("元素[IMsgUtilApi.createTextElement] " + payload);
                        return el;
                    }
                }
            } catch (Throwable t) {
                MainHook.log("IMsgUtilApi 构造元素失败，改用手工构造: " + t);
            }
        }
        if (elementType == ELEMENT_PIC) {
            return buildPicElementManually(payload);
        }
        if (elementType == ELEMENT_MARKET_FACE) {
            return buildMarketFaceElement(payload);
        }
        return buildTextElement(payload);
    }

    /** 发送一条之前录制下来的表情（json 内容） */
    public String sendSticker(String uin, String json, com.endingwave.qqemoji.config.Config cfg,
                              List<RecentContacts.Item> recents) {
        if (!isReady() && !init()) {
            return "内核未就绪";
        }
        Target target = resolveTarget(uin, recents);
        if (target == null) {
            return "无法解析 uid";
        }
        try {
            Object element = StickerStore.toElement(cl, json);
            MainHook.log("回放表情元素: " + json.substring(0, Math.min(160, json.length())));
            return sendElement(target, element, uin, cfg == null || cfg.localEcho);
        } catch (Throwable t) {
            MainHook.log(t);
            return "回放表情失败: " + t;
        }
    }

    private Object buildTextElement(String text) throws Throwable {
        Class<?> textCls = cl.loadClass("com.tencent.qqnt.kernel.nativeinterface.TextElement");
        Object te = textCls.newInstance();
        setField(te, "content", text);
        MainHook.log("元素[手工 TextElement] " + text);
        return newMsgElement(ELEMENT_TEXT, "textElement", te);
    }

    /**
     * 手工构造图片元素 —— 严格照着 QQ 的 smali（AIOObjectTransform 的 pic 分支）来：
     * 只设 sourcePath / original / fileName / fileUuid，其余保持默认。
     * 千万不要自作聪明填 md5HexStr / fileSize / picWidth / picType，
     * 否则内核会报 "rich media transfer failed"。
     */
    private Object buildPicElementManually(String path) throws Throwable {
        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            throw new IllegalStateException("图片不存在: " + path);
        }
        Class<?> picCls = cl.loadClass("com.tencent.qqnt.kernel.nativeinterface.PicElement");
        Object el = picCls.newInstance();
        setField(el, "sourcePath", path);
        setField(el, "original", Boolean.FALSE);
        setField(el, "fileName", path);
        setField(el, "fileUuid", Uri.fromFile(file).toString());
        MainHook.log("元素[手工 PicElement] " + path);
        return newMsgElement(ELEMENT_PIC, "picElement", el);
    }

    /** spec = "packageId:emojiId[:名称]" */
    private Object buildMarketFaceElement(String spec) throws Throwable {
        String[] parts = spec == null ? new String[0] : spec.split(":");
        if (parts.length < 2) {
            throw new IllegalStateException("marketFace 需要 packageId:emojiId[:名称]");
        }
        Class<?> mfCls = cl.loadClass("com.tencent.qqnt.kernel.nativeinterface.MarketFaceElement");
        Object el = mfCls.newInstance();
        setField(el, "emojiPackageId", Integer.valueOf(Integer.parseInt(parts[0].trim())));
        setField(el, "emojiId", parts[1].trim());
        if (parts.length > 2) {
            setField(el, "faceName", parts[2].trim());
        }
        setField(el, "faceInfo", Integer.valueOf(1));
        setField(el, "mediaType", Integer.valueOf(1));
        return newMsgElement(ELEMENT_MARKET_FACE, "marketFaceElement", el);
    }

    private Object newMsgElement(int type, String field, Object value) throws Throwable {
        Class<?> elCls = cl.loadClass("com.tencent.qqnt.kernel.nativeinterface.MsgElement");
        Object el = elCls.newInstance();
        setField(el, "elementType", Integer.valueOf(type));
        setField(el, field, value);
        return el;
    }

    private String sendElement(Target target, Object element, String uin, boolean localEcho) throws Throwable {
        Class<?> af = cl.loadClass("com.tencent.qqnt.kernel.api.af");
        Class<?> contactCls = cl.loadClass("com.tencent.qqnt.kernelpublic.nativeinterface.Contact");
        Class<?> cbCls = cl.loadClass("com.tencent.qqnt.kernel.nativeinterface.IOperateCallback");

        Object contact = contactCls.getConstructor(int.class, String.class, String.class)
                .newInstance(Integer.valueOf(target.chatType), target.uid, "");

        ArrayList<Object> elements = new ArrayList<Object>();
        elements.add(element);
        HashMap<Object, Object> msgAttrs = new HashMap<Object, Object>();

        final CountDownLatch latch = new CountDownLatch(1);
        final int[] code = new int[]{Integer.MIN_VALUE};
        final String[] err = new String[]{null};
        Object callback = Proxy.newProxyInstance(cl, new Class[]{cbCls}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if ("hashCode".equals(method.getName())) {
                    return Integer.valueOf(System.identityHashCode(proxy));
                }
                if ("equals".equals(method.getName())) {
                    return Boolean.valueOf(proxy == (args == null || args.length == 0 ? null : args[0]));
                }
                return "QQEmojiCallback";
            }
            if ("onResult".equals(method.getName()) && args != null && args.length >= 2) {
                code[0] = args[0] instanceof Number ? ((Number) args[0]).intValue() : -1;
                err[0] = args[1] == null ? null : String.valueOf(args[1]);
                MainHook.log("内核回调 uin=" + uin + " code=" + code[0] + " msg=" + err[0]);
                latch.countDown();
            }
            return null;
        });

        // ★ 完全照抄 QQ 自己的 AIO 发送流程（AIOMsgService#a + #sendMsgWithMsgId）：
        //   1) msgId = generateMsgUniqueId(chatType)
        //   2) addSendMsg(msgId, ...)   -> 在本地建一条"发送中"的记录（自己这边能看到）
        //   3) sendMsg(msgId, ...)      -> 真正发出去
        long msgId = 0L;
        try {
            Method gen = af.getMethod("generateMsgUniqueId", int.class);
            Object id = gen.invoke(msgService, Integer.valueOf(target.chatType));
            if (id instanceof Long) {
                msgId = ((Long) id).longValue();
            }
        } catch (Throwable t) {
            MainHook.log("generateMsgUniqueId 失败: " + t);
        }
        if (localEcho && msgId != 0L) {
            try {
                Method add = af.getMethod("addSendMsg", long.class, contactCls, ArrayList.class, HashMap.class);
                add.invoke(msgService, Long.valueOf(msgId), contact, elements, msgAttrs);
            } catch (Throwable t) {
                MainHook.log("addSendMsg 失败（消息不会出现在自己聊天窗口）: " + t);
            }
        }

        Method send = af.getMethod("sendMsg", long.class, contactCls, ArrayList.class, HashMap.class, cbCls);
        SELF_SEND.set(Boolean.TRUE);
        try {
            send.invoke(msgService, Long.valueOf(msgId), contact, elements, msgAttrs, callback);
        } finally {
            SELF_SEND.remove();
        }
        MainHook.log("已调用内核 sendMsg: uin=" + uin + " uid=" + target.uid
                + " chatType=" + target.chatType + " msgId=" + msgId + " 来源=" + target.source);

        if (!latch.await(CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return "内核未回调（" + CALLBACK_TIMEOUT_MS + "ms 超时）";
        }
        if (code[0] != 0) {
            return "内核返回 code=" + code[0] + " msg=" + err[0];
        }
        return null;
    }

    // ==================== 诊断：抓 QQ 自己的 sendMsg ====================

    public static void installSendSniffer(ClassLoader cl) {
        try {
            Class<?> contactCls = cl.loadClass("com.tencent.qqnt.kernelpublic.nativeinterface.Contact");
            Class<?> cbCls = cl.loadClass("com.tencent.qqnt.kernel.nativeinterface.IOperateCallback");
            XposedHelpers.findAndHookMethod("com.tencent.qqnt.kernel.api.impl.MsgService", cl,
                    "sendMsg", long.class, contactCls, ArrayList.class, HashMap.class, cbCls,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                StringBuilder sb = new StringBuilder("【抓包】sendMsg msgId=");
                                sb.append(param.args[0]);
                                Object c = param.args[1];
                                sb.append(" chatType=").append(RecentContacts.readLong(c, "chatType", -1));
                                sb.append(" peerUid=").append(RecentContacts.readString(c, "peerUid"));
                                List<?> els = param.args[2] instanceof List ? (List<?>) param.args[2] : null;
                                sb.append(" elements=").append(els == null ? 0 : els.size());
                                if (els != null) {
                                    for (Object e : els) {
                                        sb.append(" | ").append(dumpFields(e));
                                    }
                                }
                                sb.append(" attrs=").append(param.args[3]);
                                MainHook.log(sb.toString());
                            } catch (Throwable t) {
                                MainHook.log(t);
                            }
                        }
                    });
            MainHook.log("已安装 sendMsg 抓包钩子（自己发一条消息即可看到 QQ 的真实参数）");
        } catch (Throwable t) {
            MainHook.log("安装抓包钩子失败: " + t);
        }
    }

    /** 把元素（含子元素）的全部字段打出来，便于一比一复刻 */
    private static String dumpFields(Object element) {
        StringBuilder sb = new StringBuilder();
        appendFields(sb, element, "");
        for (String sub : new String[]{"textElement", "picElement", "faceElement", "marketFaceElement", "pttElement", "videoElement"}) {
            Object v = readField(element, sub);
            if (v != null) {
                sb.append(" ").append(sub).append("{");
                appendFields(sb, v, "");
                sb.append("}");
            }
        }
        return sb.toString();
    }

    private static void appendFields(StringBuilder sb, Object target, String prefix) {
        if (target == null) {
            return;
        }
        sb.append(prefix).append(target.getClass().getSimpleName()).append("(");
        for (Field f : target.getClass().getFields()) {
            try {
                Object v = f.get(target);
                if (v == null) {
                    continue;
                }
                if (v instanceof Number && ((Number) v).longValue() == 0L) {
                    continue;
                }
                if (v instanceof Boolean && !((Boolean) v).booleanValue()) {
                    continue;
                }
                if (v instanceof String && ((String) v).isEmpty()) {
                    continue;
                }
                sb.append(f.getName()).append("=").append(v).append(",");
            } catch (Throwable ignored) {
            }
        }
        sb.append(")");
    }

    // ==================== 工具 ====================

    private static Object readField(Object target, String name) {
        try {
            Field f = target.getClass().getField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invokeStatic(Class<?> cls, String name) {
        try {
            Method m = cls.getMethod(name);
            return m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Throwable t) {
            MainHook.log("设置字段失败 " + name + ": " + t);
        }
    }
}
