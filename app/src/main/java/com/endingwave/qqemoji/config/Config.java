package com.endingwave.qqemoji.config;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 模块配置。模块 App 进程写入，QQ 进程（被注入的代码）读取，因此只依赖 org.json。
 */
public class Config {

    /** 发送方式：内核直发 */
    public static final String MODE_KERNEL = "kernel";
    /** 发送方式：界面模拟 */
    public static final String MODE_UI = "ui";

    /** 内容类型：文字/表情代码 */
    public static final String TYPE_TEXT = "text";
    /** 内容类型：本地图片 */
    public static final String TYPE_IMAGE = "image";
    /** 内容类型：收藏/商城表情 */
    public static final String TYPE_MARKET = "market";
    /** 内容类型：随机回放已录制的表情 */
    public static final String TYPE_STICKER = "sticker";

    public boolean enabled = true;

    /** 目标 QQ 号 */
    public final List<String> targets = new ArrayList<String>();

    /** 发送给所有置顶会话 */
    public boolean allPinned = false;

    /** 只对处于置顶状态的会话发送 */
    public boolean pinnedOnly = true;

    public int hour = 21;
    public int minute = 30;
    public boolean repeatDaily = true;

    /** 多个时间点，英文/中文逗号分隔，如 "09:30,12:00 21:30"；为空时用 hour:minute */
    public String timesText = "";

    /** text / image / market */
    public String payloadType = TYPE_TEXT;
    /** 文字内容（表情代码、emoji 等） */
    public String payload = "[微笑]";
    /** 本地图片路径 */
    public String imagePath = "";
    /** file = 单张图片；folder = 文件夹随机 */
    public String imageSource = "file";
    public String folderPath = "";
    public boolean folderRecursive = false;
    /** 作为表情图发送：picSubType=1，QQ 外显会是 [动画表情] 而不是 [图片] */
    public boolean picAsEmoji = false;
    /** 收藏表情：packageId:emojiId:名称 */
    public String marketFace = "";

    /** 录制并回放表情（FunBox 等模块的表情包不用解密也能发） */
    public boolean stickerCapture = false;
    public String stickerDir = "";
    /** 抓取解码出来的图片（打开表情面板划一遍就能抓） */
    public boolean captureDecoded = true;
    /** 抓图关键字：解码调用栈/文件路径里含它就抓，默认抓 FunBox */
    public String captureKeyword = "fun";
    /** 失败时的文字兜底 */
    public String fallbackText = "";

    /** kernel / ui */
    public String sendMode = MODE_KERNEL;
    /** 界面模式：打开会话后等待毫秒 */
    public int sendDelayMs = 1500;
    /** 界面模式：发送后关闭会话界面 */
    public boolean closeChatAfterSend = true;
    /** 目标之间发送间隔毫秒 */
    public int gapMs = 1500;

    /** C2C 消息的 picSubType，普通图片 0，表情图可尝试 1 */
    public int picSubType = 0;

    public boolean probe = false;

    /** 内核失败时是否自动改用界面模式重试 */
    public boolean uiFallback = false;

    /** 抓包模式：hook 内核 sendMsg，打印 QQ 自己发消息时的真实参数 */
    public boolean sniff = false;

    /** 本地留痕：发送前先 addSendMsg，自己聊天窗口能看到这条消息 */
    public boolean localEcho = true;

    /** 闹钟触发时间戳（5 分钟内视为需要立即执行） */
    public long pendingTriggerAt = 0L;

    public boolean isText() {
        return TYPE_TEXT.equalsIgnoreCase(payloadType);
    }

    public boolean isImage() {
        return TYPE_IMAGE.equalsIgnoreCase(payloadType);
    }

    public boolean isMarket() {
        return TYPE_MARKET.equalsIgnoreCase(payloadType);
    }

    public boolean isSticker() {
        return TYPE_STICKER.equalsIgnoreCase(payloadType);
    }

    public boolean isKernelMode() {
        return MODE_KERNEL.equalsIgnoreCase(sendMode);
    }

    public String timeText() {
        return String.format(Locale.US, "%02d:%02d", hour, minute);
    }

    public boolean isFolderSource() {
        return "folder".equalsIgnoreCase(imageSource);
    }

    /** 实际生效的图片子类型：勾了"作为表情图发送"就是 1（QQ 外显 [动画表情]），否则用 picSubType */
    public int effectivePicSubType() {
        return picAsEmoji ? 1 : picSubType;
    }

    /**
     * 解析时间点列表。支持 "09:30,12:00,21:30"、"0930 1200"、中文逗号、换行。
     * 解析不出任何时间点时回退到 hour:minute。
     */
    public List<int[]> timeList() {
        List<int[]> out = new ArrayList<int[]>();
        String src = timesText == null ? "" : timesText.trim();
        if (!src.isEmpty()) {
            for (String part : src.split("[,，;；\s]+")) {
                String t = part.trim();
                if (t.isEmpty()) {
                    continue;
                }
                int[] hm = parseOne(t);
                if (hm != null) {
                    out.add(hm);
                }
            }
        }
        if (out.isEmpty()) {
            out.add(new int[]{hour, minute});
        }
        return out;
    }

    public String timesDisplay() {
        StringBuilder sb = new StringBuilder();
        for (int[] hm : timeList()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.US, "%02d:%02d", hm[0], hm[1]));
        }
        return sb.toString();
    }

    private static int[] parseOne(String t) {
        try {
            String s = t.replace("：", ":");
            int h;
            int m;
            if (s.contains(":")) {
                String[] hm = s.split(":");
                h = Integer.parseInt(hm[0].trim());
                m = hm.length > 1 ? Integer.parseInt(hm[1].trim()) : 0;
            } else if (s.length() == 4) {
                h = Integer.parseInt(s.substring(0, 2));
                m = Integer.parseInt(s.substring(2, 4));
            } else if (s.length() == 3) {
                h = Integer.parseInt(s.substring(0, 1));
                m = Integer.parseInt(s.substring(1, 3));
            } else {
                return null;
            }
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return null;
            }
            return new int[]{h, m};
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Config fromJson(String json) {
        Config c = new Config();
        if (json == null || json.trim().isEmpty()) {
            return c;
        }
        try {
            JSONObject o = new JSONObject(json);
            c.enabled = o.optBoolean("enabled", true);
            c.targets.clear();
            JSONArray arr = o.optJSONArray("targets");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, "").trim();
                    if (!s.isEmpty()) {
                        c.targets.add(s);
                    }
                }
            }
            c.allPinned = o.optBoolean("allPinned", false);
            c.pinnedOnly = o.optBoolean("pinnedOnly", true);
            c.hour = o.optInt("hour", 21);
            c.minute = o.optInt("minute", 30);
            c.repeatDaily = o.optBoolean("repeatDaily", true);
            c.payloadType = o.optString("payloadType", TYPE_TEXT);
            c.payload = o.optString("payload", "[微笑]");
            c.imagePath = o.optString("imagePath", "");
            c.timesText = o.optString("timesText", "");
            c.imageSource = o.optString("imageSource", "file");
            c.folderPath = o.optString("folderPath", "");
            c.folderRecursive = o.optBoolean("folderRecursive", false);
            c.picAsEmoji = o.optBoolean("picAsEmoji", false);
            c.stickerCapture = o.optBoolean("stickerCapture", false);
            c.stickerDir = o.optString("stickerDir", "");
            c.captureDecoded = o.optBoolean("captureDecoded", true);
            c.captureKeyword = o.optString("captureKeyword", "fun");
            c.marketFace = o.optString("marketFace", "");
            c.fallbackText = o.optString("fallbackText", "");
            c.sendMode = o.optString("sendMode", MODE_KERNEL);
            c.sendDelayMs = o.optInt("sendDelayMs", 1500);
            c.closeChatAfterSend = o.optBoolean("closeChatAfterSend", true);
            c.gapMs = o.optInt("gapMs", 1500);
            c.picSubType = o.optInt("picSubType", 0);
            c.probe = o.optBoolean("probe", false);
            c.uiFallback = o.optBoolean("uiFallback", false);
            c.sniff = o.optBoolean("sniff", false);
            c.localEcho = o.optBoolean("localEcho", true);
            c.pendingTriggerAt = o.optLong("pendingTriggerAt", 0L);
        } catch (JSONException ignored) {
            // 配置损坏时回退到默认值
        }
        return c;
    }

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", enabled);
            JSONArray arr = new JSONArray();
            for (String s : targets) {
                arr.put(s);
            }
            o.put("targets", arr);
            o.put("allPinned", allPinned);
            o.put("pinnedOnly", pinnedOnly);
            o.put("hour", hour);
            o.put("minute", minute);
            o.put("repeatDaily", repeatDaily);
            o.put("payloadType", payloadType);
            o.put("payload", payload);
            o.put("imagePath", imagePath);
            o.put("timesText", timesText);
            o.put("imageSource", imageSource);
            o.put("folderPath", folderPath);
            o.put("folderRecursive", folderRecursive);
            o.put("picAsEmoji", picAsEmoji);
            o.put("stickerCapture", stickerCapture);
            o.put("stickerDir", stickerDir);
            o.put("captureDecoded", captureDecoded);
            o.put("captureKeyword", captureKeyword);
            o.put("marketFace", marketFace);
            o.put("fallbackText", fallbackText);
            o.put("sendMode", sendMode);
            o.put("sendDelayMs", sendDelayMs);
            o.put("closeChatAfterSend", closeChatAfterSend);
            o.put("gapMs", gapMs);
            o.put("picSubType", picSubType);
            o.put("probe", probe);
            o.put("uiFallback", uiFallback);
            o.put("sniff", sniff);
            o.put("localEcho", localEcho);
            o.put("pendingTriggerAt", pendingTriggerAt);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }
}
