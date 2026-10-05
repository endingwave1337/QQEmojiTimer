package com.endingwave.qqemoji.hook;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 读取 QQ 的会话列表（QQ 9.3.70 / NT 内核）。
 *
 *   com.tencent.qqnt.msg.f.i()                       -> IKernelService
 *   IKernelService.getRecentContactService()         -> com.tencent.qqnt.kernel.api.ak
 *   ak.getRecentContactInfos(IKernelRecentGetContactCallback)
 *   callback: onResult(int, String, ArrayList<RecentContactInfo>)
 *   RecentContactInfo: peerUin(long) / peerUid(String) / chatType(int) / topFlag(byte)
 *
 * 这里读到的 peerUid 就是 QQ 自己打开会话、发消息时用的 uid，最可靠。
 */
public class RecentContacts {

    public static final class Item {
        public final String uin;
        public final String uid;
        public final int chatType;
        public final boolean top;

        Item(String uin, String uid, int chatType, boolean top) {
            this.uin = uin;
            this.uid = uid;
            this.chatType = chatType;
            this.top = top;
        }

        @Override
        public String toString() {
            return "{uin=" + uin + ", uid=" + uid + ", chatType=" + chatType + ", top=" + top + "}";
        }
    }

    private RecentContacts() {
    }

    /** 阻塞读取，最多 3 秒。失败返回空列表。 */
    public static List<Item> load(ClassLoader cl) {
        final List<Item> out = new ArrayList<Item>();
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            Class<?> entry = cl.loadClass("com.tencent.qqnt.msg.f");
            Object kernel = entry.getMethod("i").invoke(null);
            if (kernel == null) {
                MainHook.log("RecentContacts: IKernelService 为空（内核未初始化）");
                return out;
            }
            Class<?> iks = cl.loadClass("com.tencent.qqnt.kernel.api.IKernelService");
            Object recent = iks.getMethod("getRecentContactService").invoke(kernel);
            if (recent == null) {
                MainHook.log("RecentContacts: RecentContactService 为空");
                return out;
            }
            Class<?> cbCls = cl.loadClass("com.tencent.qqnt.kernel.nativeinterface.IKernelRecentGetContactCallback");
            Object callback = Proxy.newProxyInstance(cl, new Class[]{cbCls}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("hashCode".equals(method.getName())) {
                        return Integer.valueOf(System.identityHashCode(proxy));
                    }
                    if ("equals".equals(method.getName())) {
                        return Boolean.valueOf(proxy == (args == null || args.length == 0 ? null : args[0]));
                    }
                    return "QQEmojiRecentCallback";
                }
                try {
                    if (args != null && args.length > 0 && args[args.length - 1] instanceof List) {
                        fill((List<?>) args[args.length - 1], out);
                    }
                } catch (Throwable t) {
                    MainHook.log(t);
                } finally {
                    latch.countDown();
                }
                return null;
            });
            Class<?> recentCls = cl.loadClass("com.tencent.qqnt.kernel.api.ak");
            recentCls.getMethod("getRecentContactInfos", cbCls).invoke(recent, callback);
            latch.await(3, TimeUnit.SECONDS);
        } catch (Throwable t) {
            MainHook.log("RecentContacts 失败: " + t);
        }
        return out;
    }

    private static void fill(List<?> list, List<Item> out) {
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            long peerUin = readLong(item, "peerUin", 0L);
            String peerUid = readString(item, "peerUid");
            int chatType = (int) readLong(item, "chatType", 1L);
            boolean top = readLong(item, "topFlag", 0L) != 0L;
            if (peerUin > 0L || (peerUid != null && !peerUid.isEmpty())) {
                out.add(new Item(peerUin > 0L ? String.valueOf(peerUin) : "", peerUid, chatType, top));
            }
        }
    }

    static long readLong(Object target, String name, long def) {
        try {
            Field f = target.getClass().getField(name);
            f.setAccessible(true);
            Object v = f.get(target);
            if (v instanceof Number) {
                return ((Number) v).longValue();
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    static String readString(Object target, String name) {
        try {
            Field f = target.getClass().getField(name);
            f.setAccessible(true);
            Object v = f.get(target);
            return v instanceof String ? (String) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 置顶且是好友(C2C)的 QQ 号 */
    public static List<String> pinnedC2CUins(ClassLoader cl) {
        List<String> out = new ArrayList<String>();
        for (Item it : load(cl)) {
            if (it.top && it.chatType == 1 && !it.uin.isEmpty() && !out.contains(it.uin)) {
                out.add(it.uin);
            }
        }
        return out;
    }
}
