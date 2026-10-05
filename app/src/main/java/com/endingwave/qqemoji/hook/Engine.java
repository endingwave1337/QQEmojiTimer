package com.endingwave.qqemoji.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.endingwave.qqemoji.config.Config;
import com.endingwave.qqemoji.config.RemoteConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** 注入到 QQ 进程后的调度核心。 */
public class Engine {

    public static final String ACTION_FIRE = "com.endingwave.qqemoji.action.FIRE";

    private static final long PENDING_WINDOW_MS = 10 * 60 * 1000L;
    private static final long COLD_START_DELAY_MS = 8000L;

    private static Engine instance;

    private final Context ctx;
    private final ClassLoader cl;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final NtBridge nt;
    private final UiSender ui;

    private volatile boolean executing;
    private volatile boolean rerunRequested;

    public Engine(Context ctx, ClassLoader cl) {
        this.ctx = ctx;
        this.cl = cl;
        this.nt = new NtBridge(cl);
        this.ui = new UiSender(ctx);
    }

    public void start() {
        // Application.attach 可能被调用多次，避免重复注册导致一次触发执行多次
        synchronized (Engine.class) {
            if (instance != null) {
                MainHook.log("Engine 已启动过，跳过重复初始化");
                return;
            }
            instance = this;
        }

        Config cfg = RemoteConfig.load(ctx);
        MainHook.log("配置加载: enabled=" + cfg.enabled
                + " mode=" + cfg.sendMode
                + " type=" + cfg.payloadType
                + " time=" + cfg.timeText()
                + " targets=" + cfg.targets
                + " allPinned=" + cfg.allPinned
                + " pinnedOnly=" + cfg.pinnedOnly
                + " probe=" + cfg.probe
                + " sniff=" + cfg.sniff);

        if (cfg.probe) {
            try {
                new Probe(cl).start();
            } catch (Throwable t) {
                MainHook.log(t);
            }
        }
        if (cfg.sniff) {
            NtBridge.installSendSniffer(cl);
        }
        if (cfg.stickerCapture) {
            StickerStore.installCapture(cl, ctx, stickerDirWithCtx(cfg));
            if (cfg.captureDecoded) {
                StickerStore.installBitmapCapture(cl, stickerDirWithCtx(cfg), cfg.captureKeyword);
            }
            MainHook.log("表情录制目录: " + stickerDirWithCtx(cfg));
        }

        registerFireReceiver();

        long pending = cfg.pendingTriggerAt;
        long now = System.currentTimeMillis();
        long handled = Store.handled(ctx);
        if (pending > 0L && pending != handled && now - pending >= 0L && now - pending < PENDING_WINDOW_MS) {
            Store.setHandled(ctx, pending);
            MainHook.log("检测到待执行的定时任务， " + COLD_START_DELAY_MS + "ms 后执行");
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    execute();
                }
            }, COLD_START_DELAY_MS);
        }
    }

    /** 录制表情的存放目录：默认在 QQ 自己的外部目录里（QQ 进程可读可写，发送时也在 QQ 进程里） */
    private String stickerDirWithCtx(Config cfg) {
        if (cfg.stickerDir != null && !cfg.stickerDir.trim().isEmpty()) {
            return cfg.stickerDir.trim();
        }
        File base = ctx.getExternalFilesDir(null);
        if (base == null) {
            base = ctx.getFilesDir();
        }
        return new File(base, "qqemoji_stickers").getAbsolutePath();
    }

    private static final String[] IMAGE_EXT = new String[]{
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".apng", ".heic"
    };

    private static boolean hasImageExt(File f) {
        String n = f.getName().toLowerCase();
        for (String e : IMAGE_EXT) {
            if (n.endsWith(e)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按文件头判断真实图片类型。
     * FunBox 的表情就是**去掉了扩展名的 WebP**（RIFF....WEBP），所以不能只看后缀。
     */
    private static String sniffExt(File f) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] h = new byte[16];
            int n = in.read(h);
            if (n < 4) {
                return null;
            }
            if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && n >= 12
                    && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
                return "webp";
            }
            if ((h[0] & 0xff) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
                return "png";
            }
            if ((h[0] & 0xff) == 0xff && (h[1] & 0xff) == 0xd8 && (h[2] & 0xff) == 0xff) {
                return "jpg";
            }
            if (h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8') {
                return "gif";
            }
            if (h[0] == 'B' && h[1] == 'M') {
                return "bmp";
            }
        } catch (Throwable ignored) {
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static boolean isImage(File f) {
        return hasImageExt(f) || sniffExt(f) != null;
    }

    /** 从文件夹里随机挑一张 QQ 能读到的图片 */
    private static File pickRandomImage(String folder, boolean recursive) {
        if (folder == null || folder.trim().isEmpty()) {
            return null;
        }
        File dir = new File(folder.trim());
        if (!dir.isDirectory()) {
            MainHook.log("不是有效的文件夹: " + folder);
            return null;
        }
        List<File> files = new ArrayList<File>();
        collectImages(dir, recursive ? 6 : 0, files);
        if (files.isEmpty() && !recursive) {
            // FunBox 的文件在 .fun/Sticker/Storage/<表情包>/ 这种多层目录里，
            // 勾没勾"包含子目录"都兜底递归找一次，免得一个都扫不到
            MainHook.log("当前目录没有图片，自动递归子目录再找一次");
            collectImages(dir, 6, files);
        }
        MainHook.log("目录 " + dir.getAbsolutePath() + " 共找到 " + files.size() + " 张图片");
        if (files.isEmpty()) {
            return null;
        }
        return files.get(new Random().nextInt(files.size()));
    }

    private static void collectImages(File dir, int depth, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            MainHook.log("读不了文件夹（可能是安卓分区存储限制）: " + dir.getAbsolutePath());
            return;
        }
        for (File f : children) {
            if (f.isDirectory()) {
                if (depth > 0) {
                    collectImages(f, depth - 1, out);
                }
            } else if (isImage(f) && f.canRead() && f.length() > 0) {
                out.add(f);
            }
        }
    }

    /**
     * 中转目录：优先用 QQ 自己的媒体目录（/storage/emulated/0/Android/media/<pkg>/.qqet_send），
     * QQ 发图时对这种"媒体目录里的正经图片文件"最不挑；退而求其次用外部缓存/内部缓存。
     */
    private File sendWorkDir() {
        try {
            File[] medias = ctx.getExternalMediaDirs();
            if (medias != null && medias.length > 0 && medias[0] != null) {
                File d = new File(medias[0], ".qqet_send");
                if (d.exists() || d.mkdirs()) {
                    return d;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            File base = ctx.getExternalCacheDir();
            if (base != null) {
                File d = new File(base, "qqemoji_send");
                if (d.exists() || d.mkdirs()) {
                    return d;
                }
            }
        } catch (Throwable ignored) {
        }
        File d = new File(ctx.getCacheDir(), "qqemoji_send");
        return (d.exists() || d.mkdirs()) ? d : null;
    }

    /** 兜底：把源文件解码后重新编码成 PNG（QQ 一定认），动图会丢动效 */
    private File transcodePng(File src) {
        try {
            if (!src.exists()) {
                return null;
            }
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(src.getAbsolutePath());
            if (bmp == null) {
                MainHook.log("解码失败，无法转码: " + src.getAbsolutePath());
                return null;
            }
            File dir = sendWorkDir();
            if (dir == null) {
                return null;
            }
            File dst = new File(dir, md5(src.getAbsolutePath()) + ".png");
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            out.close();
            bmp.recycle();
            return dst;
        } catch (Throwable t) {
            MainHook.log("转码 PNG 失败: " + t);
            return null;
        }
    }

    private static String md5(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf(s.hashCode());
        }
    }

    /**
     * QQ 生成图片元素时会看扩展名算 picType，没扩展名的文件会导致
     * "rich media transfer failed"，所以按文件头补一个正确后缀再发。
     */
    private File ensureSendable(File src) {
        String ext = sniffExt(src);
        if (ext == null) {
            return src;
        }
        String lower = src.getName().toLowerCase();
        if (lower.endsWith("." + ext) || ("jpg".equals(ext) && lower.endsWith(".jpeg"))) {
            return src;
        }
        try {
            File dir = sendWorkDir();
            if (dir == null) {
                return src;
            }
            String key = md5(src.getAbsolutePath() + ":" + src.length() + ":" + src.lastModified());
            File dst = new File(dir, key + "." + ext);
            if (dst.exists() && dst.length() == src.length()) {
                return dst;
            }
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            out.close();
            return dst;
        } catch (Throwable t) {
            MainHook.log("补扩展名失败（直接发原文件）: " + t);
            return src;
        }
    }

    /** 随机回放已录制的表情 */
    private void sendStickers(Config cfg, List<String> targets, List<RecentContacts.Item> recents) {
        String dir = stickerDirWithCtx(cfg);
        File[] files = StickerStore.list(dir);
        if (files == null || files.length == 0) {
            MainHook.log("还没有录制到表情，先打开 FunBox 表情面板手动发几个，目录: " + dir);
            return;
        }
        MainHook.log("已录制表情 " + files.length + " 个，开始随机发送");
        for (String uin : targets) {
            File f = files[(int) (Math.random() * files.length)];
            try {
                String json = readText(f);
                String err = nt.sendSticker(uin, json, cfg, recents);
                if (err == null) {
                    MainHook.log("表情发送成功 -> " + uin + " (" + f.getName() + ")");
                } else {
                    MainHook.log("表情发送失败 -> " + uin + " : " + err);
                }
            } catch (Throwable t) {
                MainHook.log(t);
            }
            try {
                Thread.sleep(Math.max(300, cfg.gapMs));
            } catch (InterruptedException ignored) {
            }
        }
    }

    private static String readText(File f) throws Throwable {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private void registerFireReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long fireAt = intent == null ? 0L : intent.getLongExtra("fireAt", 0L);
                // 回暗号：告诉模块 App "QQ 进程活着，我自己发就行，别把我拉到前台"
                try {
                    setResultCode(0x51E7);
                    setResultData("alive");
                } catch (Throwable ignored) {
                }
                MainHook.log("收到触发广播 fireAt=" + fireAt);
                if (fireAt > 0L) {
                    Store.setHandled(ctx, fireAt);
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        execute();
                    }
                });
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_FIRE);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, filter);
            }
        } catch (Throwable t) {
            MainHook.log(t);
        }
    }

    private void execute() {
        if (executing) {
            MainHook.log("上一次任务还在执行，排队等它结束后再跑一次");
            rerunRequested = true;
            return;
        }
        executing = true;
        rerunRequested = false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Config cfg = RemoteConfig.load(ctx);
                    if (!cfg.enabled) {
                        MainHook.log("模块未启用，跳过");
                        return;
                    }

                    List<RecentContacts.Item> recents = RecentContacts.load(cl);
                    MainHook.log("会话列表(" + recents.size() + "): " + recents);

                    List<String> targets = Targets.resolve(cfg, recents);
                    if (targets.isEmpty()) {
                        MainHook.log("没有可发送的目标（检查 QQ 号 / 置顶状态）");
                        return;
                    }
                    MainHook.log("本次目标: " + targets);

                    if (cfg.isSticker()) {
                        sendStickers(cfg, targets, recents);
                        return;
                    }
                    int elementType = cfg.isImage() ? NtBridge.ELEMENT_PIC
                            : cfg.isMarket() ? NtBridge.ELEMENT_MARKET_FACE : NtBridge.ELEMENT_TEXT;
                    String payload;
                    if (cfg.isImage() && cfg.isFolderSource()) {
                        File picked = pickRandomImage(cfg.folderPath, cfg.folderRecursive);
                        if (picked == null) {
                            MainHook.log("文件夹里没有可用图片: " + cfg.folderPath);
                            return;
                        }
                        File sendable = ensureSendable(picked);
                        payload = sendable.getAbsolutePath();
                        MainHook.log("随机选中: " + picked.getAbsolutePath()
                                + (sendable.equals(picked) ? "" : " -> 另存为 " + payload));
                    } else if (cfg.isImage()) {
                        payload = ensureSendable(new File(cfg.imagePath)).getAbsolutePath();
                    } else if (cfg.isMarket()) {
                        payload = cfg.marketFace;
                    } else {
                        payload = cfg.payload;
                    }

                    if (cfg.isKernelMode()) {
                        if (!nt.init()) {
                            MainHook.log("内核未就绪，3 秒后重试");
                            Thread.sleep(3000L);
                            nt.init();
                        }
                        if (nt.isReady()) {
                            List<String> failed = new ArrayList<String>();
                            for (String uin : targets) {
                                String err = nt.send(uin, elementType, payload, cfg, recents);
                                if (err != null && elementType == NtBridge.ELEMENT_PIC) {
                                    File png = transcodePng(new File(payload));
                                    if (png != null) {
                                        MainHook.log("原图发送失败，改用转码后的 PNG 重试: " + png.getAbsolutePath());
                                        err = nt.send(uin, elementType, png.getAbsolutePath(), cfg, recents);
                                    }
                                }
                                if (err == null) {
                                    MainHook.log("内核发送成功 -> " + uin);
                                } else {
                                    MainHook.log("内核发送失败 -> " + uin + " : " + err);
                                    failed.add(uin);
                                }
                                Thread.sleep(Math.max(300, cfg.gapMs));
                            }
                            if (failed.isEmpty()) {
                                return;
                            }
                            if (!cfg.uiFallback) {
                                return;
                            }
                            MainHook.log("改用界面模式重试: " + failed);
                            targets = failed;
                        } else {
                            MainHook.log("内核不可用，改用界面模式");
                        }
                    }
                    ui.sendSequentially(targets, cfg);
                } catch (Throwable t) {
                    MainHook.log(t);
                } finally {
                    executing = false;
                    if (rerunRequested) {
                        rerunRequested = false;
                        MainHook.log("执行排队中的下一次发送");
                        main.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                execute();
                            }
                        }, 1500L);
                    }
                }
            }
        }, "qq-emoji-timer").start();
    }
}
