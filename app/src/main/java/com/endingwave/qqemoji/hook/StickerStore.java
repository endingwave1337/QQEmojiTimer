package com.endingwave.qqemoji.hook;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 表情录制 / 回放。
 *
 * 思路：不去破解 FunBox 的加密文件，而是在它把表情交给 QQ 发送的那一刻
 * （内核 MsgService.sendMsg 的 MsgElement）把元素整个记下来，
 * 以后要发的时候照原样再发一次 —— 对面收到的和你手动点发送完全一样。
 *
 * 每个表情存成一个 json：元素类型 + 子元素（FaceElement / MarketFaceElement / PicElement）的全部字段。
 */
public class StickerStore {

    private static final String[] SUBS = new String[]{
            "faceElement", "marketFaceElement", "picElement", "textElement", "giphyElement"
    };

    private StickerStore() {
    }

    /** 安装录制钩子：QQ 每发一条消息都会走到这里，不是本模块发的就记录下来 */
    public static void installCapture(final ClassLoader cl, final Context ctx, final String dir) {
        try {
            Class<?> contactCls = cl.loadClass("com.tencent.qqnt.kernelpublic.nativeinterface.Contact");
            Class<?> cbCls = cl.loadClass("com.tencent.qqnt.kernel.nativeinterface.IOperateCallback");
            XposedHelpers.findAndHookMethod("com.tencent.qqnt.kernel.api.impl.MsgService", cl,
                    "sendMsg", long.class, contactCls, ArrayList.class, java.util.HashMap.class, cbCls,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (NtBridge.isSelfSend()) {
                                    return;
                                }
                                if (!(param.args[2] instanceof List)) {
                                    return;
                                }
                                List<?> elements = (List<?>) param.args[2];
                                for (Object e : elements) {
                                    String sub = subName(e);
                                    if (sub == null) {
                                        continue;
                                    }
                                    String json = toJson(e, sub);
                                    if (json == null) {
                                        continue;
                                    }
                                    File f = save(dir, json);
                                    MainHook.log("已录制表情(" + sub + ") -> " + (f == null ? "保存失败" : f.getAbsolutePath()));
                                }
                            } catch (Throwable t) {
                                MainHook.log(t);
                            }
                        }
                    });
            MainHook.log("已安装表情录制钩子，目录: " + dir);
        } catch (Throwable t) {
            MainHook.log("安装表情录制钩子失败: " + t);
        }
    }

    /**
     * 抓取解码出来的图片：只要解码调用栈里有 FunBox 的痕迹（或者路径里带关键字），
     * 就把 Bitmap 存成 png。这样连"手动发一遍"都不用，打开面板划一遍就抓全了。
     */
    public static void installBitmapCapture(final ClassLoader cl, final String dir, final String keyword) {
        final XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object r = param.getResult();
                    if (!(r instanceof android.graphics.Bitmap)) {
                        return;
                    }
                    android.graphics.Bitmap bmp = (android.graphics.Bitmap) r;
                    int w = bmp.getWidth();
                    int h = bmp.getHeight();
                    if (w < 8 || h < 8 || w > 2048 || h > 2048) {
                        return;
                    }
                    if (!matched(param, keyword)) {
                        return;
                    }
                    File f = saveBitmap(dir, bmp);
                    if (f != null) {
                        MainHook.log("已抓取图片 -> " + f.getName() + " (" + w + "x" + h + ")");
                    }
                } catch (Throwable t) {
                    MainHook.log(t);
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(android.graphics.BitmapFactory.class, "decodeFile",
                    String.class, android.graphics.BitmapFactory.Options.class, hook);
            XposedHelpers.findAndHookMethod(android.graphics.BitmapFactory.class, "decodeStream",
                    java.io.InputStream.class, android.graphics.Rect.class,
                    android.graphics.BitmapFactory.Options.class, hook);
            XposedHelpers.findAndHookMethod(android.graphics.BitmapFactory.class, "decodeByteArray",
                    byte[].class, int.class, int.class, android.graphics.BitmapFactory.Options.class, hook);
            MainHook.log("已安装图片抓取钩子，关键字: " + keyword);
        } catch (Throwable t) {
            MainHook.log("安装图片抓取钩子失败: " + t);
        }
    }

    /** 默认关键字：FunBox 自己可能叫 fun.box001.* / have.fun，注意别用泛化的 "fun"（QQ 里有 VipFunCall 之类） */
    private static final String[] DEFAULT_KEYWORDS = new String[]{"funbox", "have.fun", "box001", "fun.box", "fun_loader"};

    private static boolean matched(XC_MethodHook.MethodHookParam param, String keyword) {
        String custom = keyword == null ? "" : keyword.trim().toLowerCase();
        if (!custom.isEmpty() && !"fun".equals(custom)) {
            for (Object a : param.args) {
                if (a instanceof String && ((String) a).toLowerCase().contains(custom)) {
                    return true;
                }
            }
        }
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            String s = (e.getClassName() + "." + e.getMethodName()).toLowerCase();
            String flat = s.replace(".", "").replace("_", "").replace("$", "");
            for (String kw : DEFAULT_KEYWORDS) {
                String k = kw.replace(".", "").replace("_", "");
                if (s.contains(kw) || flat.contains(k)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static File saveBitmap(String dir, android.graphics.Bitmap bmp) {
        try {
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int p1 = bmp.getPixel(Math.max(0, w / 2), Math.max(0, h / 2));
            int p2 = bmp.getPixel(Math.max(0, w / 4), Math.max(0, h / 4));
            int p3 = bmp.getPixel(Math.max(0, w - 2), Math.max(0, h - 2));
            String key = md5(w + "x" + h + ":" + p1 + ":" + p2 + ":" + p3);
            File d = new File(dir);
            if (!d.exists() && !d.mkdirs()) {
                return null;
            }
            File f = new File(d, "img_" + key + ".png");
            if (f.exists()) {
                return null;
            }
            FileOutputStream out = new FileOutputStream(f);
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            out.close();
            return f;
        } catch (Throwable t) {
            MainHook.log("保存图片失败: " + t);
            return null;
        }
    }

    private static String subName(Object element) {
        for (String s : SUBS) {
            Object v = readField(element, s);
            if (v != null) {
                String cn = v.getClass().getName();
                if (s.equals("textElement") || s.equals("picElement")) {
                    // 图片/文字不当作表情录制，避免把聊天里所有图片都存下来
                    return null;
                }
                return s;
            }
        }
        return null;
    }

    private static String toJson(Object element, String sub) {
        try {
            JSONObject root = new JSONObject();
            root.put("elementType", readLong(element, "elementType", -1L));
            root.put("sub", sub);
            Object subObj = readField(element, sub);
            JSONObject fields = new JSONObject();
            for (Field f : subObj.getClass().getFields()) {
                try {
                    Object v = f.get(subObj);
                    if (v == null) {
                        continue;
                    }
                    if (v instanceof String || v instanceof Number || v instanceof Boolean) {
                        fields.put(f.getName(), String.valueOf(v));
                    }
                } catch (Throwable ignored) {
                }
            }
            root.put("fields", fields);
            return root.toString();
        } catch (Throwable t) {
            MainHook.log("序列化表情失败: " + t);
            return null;
        }
    }

    private static File save(String dir, String json) {
        try {
            File d = new File(dir);
            if (!d.exists() && !d.mkdirs()) {
                return null;
            }
            String name = md5(json) + ".json";
            File f = new File(d, name);
            if (f.exists()) {
                return f;
            }
            FileOutputStream out = new FileOutputStream(f);
            out.write(json.getBytes(Charset.forName("UTF-8")));
            out.close();
            return f;
        } catch (Throwable t) {
            MainHook.log("保存表情失败: " + t);
            return null;
        }
    }

    /** 随机取一个已录制的表情文件 */
    public static File random(String dir) {
        File[] files = list(dir);
        if (files == null || files.length == 0) {
            return null;
        }
        return files[(int) (Math.random() * files.length)];
    }

    public static File[] list(String dir) {
        File d = new File(dir);
        File[] files = d.listFiles();
        if (files == null) {
            return null;
        }
        List<File> out = new ArrayList<File>();
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".json")) {
                out.add(f);
            }
        }
        File[] arr = out.toArray(new File[0]);
        Arrays.sort(arr, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        return arr;
    }

    /** 把录制的 json 还原成 MsgElement */
    public static Object toElement(ClassLoader cl, String json) throws Throwable {
        JSONObject root = new JSONObject(json);
        int elementType = root.optInt("elementType", -1);
        String sub = root.optString("sub", "");
        JSONObject fields = root.optJSONObject("fields");
        String subClsName;
        if ("faceElement".equals(sub)) {
            subClsName = "com.tencent.qqnt.kernel.nativeinterface.FaceElement";
        } else if ("marketFaceElement".equals(sub)) {
            subClsName = "com.tencent.qqnt.kernel.nativeinterface.MarketFaceElement";
        } else if ("giphyElement".equals(sub)) {
            subClsName = "com.tencent.qqnt.kernel.nativeinterface.GiphyElement";
        } else {
            throw new IllegalStateException("不支持的表情类型: " + sub);
        }
        Class<?> subCls = cl.loadClass(subClsName);
        Object subObj = subCls.newInstance();
        if (fields != null) {
            java.util.Iterator<String> it = fields.keys();
            while (it.hasNext()) {
                String name = it.next();
                String value = fields.optString(name);
                try {
                    Field f = subCls.getField(name);
                    f.setAccessible(true);
                    f.set(subObj, convert(value, f.getType()));
                } catch (Throwable t) {
                    MainHook.log("还原字段失败 " + name + ": " + t);
                }
            }
        }
        Class<?> elCls = cl.loadClass("com.tencent.qqnt.kernel.nativeinterface.MsgElement");
        Object el = elCls.newInstance();
        Field et = elCls.getField("elementType");
        et.setAccessible(true);
        et.set(el, Integer.valueOf(elementType));
        Field sf = elCls.getField(sub);
        sf.setAccessible(true);
        sf.set(el, subObj);
        return el;
    }

    private static Object convert(String v, Class<?> type) {
        if (type == String.class) {
            return v;
        }
        if (type == int.class || type == Integer.class) {
            return Integer.valueOf((int) Double.parseDouble(v));
        }
        if (type == long.class || type == Long.class) {
            return Long.valueOf((long) Double.parseDouble(v));
        }
        if (type == boolean.class || type == Boolean.class) {
            return Boolean.valueOf(v);
        }
        if (type == byte.class || type == Byte.class) {
            return Byte.valueOf((byte) Integer.parseInt(v));
        }
        if (type == float.class || type == Float.class) {
            return Float.valueOf(v);
        }
        if (type == double.class || type == Double.class) {
            return Double.valueOf(v);
        }
        return null;
    }

    // ---------- 工具 ----------

    private static Object readField(Object target, String name) {
        try {
            Field f = target.getClass().getField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long readLong(Object target, String name, long def) {
        Object v = readField(target, name);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(Charset.forName("UTF-8")));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf(s.hashCode());
        }
    }
}
