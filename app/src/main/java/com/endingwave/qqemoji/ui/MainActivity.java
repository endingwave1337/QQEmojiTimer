package com.endingwave.qqemoji.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;

import com.endingwave.qqemoji.R;
import com.endingwave.qqemoji.alarm.Scheduler;
import com.endingwave.qqemoji.alarm.TriggerActivity;
import com.endingwave.qqemoji.config.Config;
import com.endingwave.qqemoji.config.Prefs;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * release 版设置界面：没有调试开关、没有 Toast 弹窗，
 * 所有反馈都显示在底部的状态文字里。
 */
public class MainActivity extends Activity {

    private CheckBox cbEnabled;
    private EditText etTargets;
    private CheckBox cbAllPinned;
    private CheckBox cbPinnedOnly;
    private EditText etTime;
    private CheckBox cbRepeat;
    private RadioButton rbText;
    private RadioButton rbImage;
    private EditText etPayload;
    private RadioButton rbSrcFile;
    private RadioButton rbSrcFolder;
    private EditText etImage;
    private EditText etFolder;
    private CheckBox cbRecursive;
    private CheckBox cbAsEmoji;
    private EditText etFallback;
    private RadioButton rbKernel;
    private RadioButton rbUi;
    private EditText etDelay;
    private CheckBox cbClose;
    private CheckBox cbLocalEcho;
    private CheckBox cbUiFallback;
    private TextView tvStatus;
    private TextView tvFeedback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cbEnabled = findViewById(R.id.cb_enabled);
        etTargets = findViewById(R.id.et_targets);
        cbAllPinned = findViewById(R.id.cb_all_pinned);
        cbPinnedOnly = findViewById(R.id.cb_pinned_only);
        etTime = findViewById(R.id.et_time);
        cbRepeat = findViewById(R.id.cb_repeat);
        rbText = findViewById(R.id.rb_text);
        rbImage = findViewById(R.id.rb_image);
        etPayload = findViewById(R.id.et_payload);
        rbSrcFile = findViewById(R.id.rb_src_file);
        rbSrcFolder = findViewById(R.id.rb_src_folder);
        etImage = findViewById(R.id.et_image);
        etFolder = findViewById(R.id.et_folder);
        cbRecursive = findViewById(R.id.cb_recursive);
        cbAsEmoji = findViewById(R.id.cb_as_emoji);
        etFallback = findViewById(R.id.et_fallback);
        rbKernel = findViewById(R.id.rb_kernel);
        rbUi = findViewById(R.id.rb_ui);
        etDelay = findViewById(R.id.et_delay);
        cbClose = findViewById(R.id.cb_close);
        cbLocalEcho = findViewById(R.id.cb_local_echo);
        cbUiFallback = findViewById(R.id.cb_ui_fallback);
        tvStatus = findViewById(R.id.tv_status);
        tvFeedback = findViewById(R.id.tv_feedback);

        // release 版不带调试开关：把老配置里可能残留的调试项一并关掉
        Config saved = Prefs.load(this);
        saved.probe = false;
        saved.sniff = false;
        saved.stickerCapture = false;
        if (saved.isSticker() || saved.isMarket()) {
            saved.payloadType = Config.TYPE_TEXT;
        }
        Prefs.save(this, saved);
        bind(saved);
        // 清掉上次遗留的待触发标记，避免一打开就发
        Prefs.setPendingTrigger(this, 0L);
        feedback("已就绪");

        ((Button) findViewById(R.id.btn_funbox_dir)).setOnClickListener(v -> {
            etFolder.setText("/storage/emulated/0/Android/media/com.tencent.mobileqq/.fun/Sticker/Storage/");
            cbRecursive.setChecked(true);
            rbImage.setChecked(true);
            rbSrcFolder.setChecked(true);
            feedback("已填入 FunBox 表情目录（保存后生效）");
        });

        ((Button) findViewById(R.id.btn_save)).setOnClickListener(v -> {
            Config cfg = collect();
            Prefs.save(this, cfg);
            Scheduler.schedule(this);
            refreshStatus();
            StringBuilder sb = new StringBuilder();
            sb.append("已保存 ").append(cfg.timeList().size()).append(" 个时间点，最近一次 ")
                    .append(new SimpleDateFormat("MM-dd HH:mm", Locale.US)
                            .format(new Date(Scheduler.nextTriggerAt(cfg))));
            if (cfg.isImage() && cfg.isFolderSource()) {
                int n = countImages(new File(cfg.folderPath), 6);
                sb.append(n > 0 ? ("，文件夹里 " + n + " 张图片") : "，本 App 读不到该目录（不影响发送，以 LSPosed 日志为准）");
            }
            feedback(sb.toString());
        });

        ((Button) findViewById(R.id.btn_exact)).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 31 && !canExact()) {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName())));
                } catch (Throwable t) {
                    feedback("请到系统设置里手动允许精确闹钟");
                }
            } else {
                feedback("已具备精确闹钟权限");
            }
        });

        ((Button) findViewById(R.id.btn_test)).setOnClickListener(v -> {
            Prefs.save(this, collect());
            refreshStatus();
            startActivity(new Intent(this, TriggerActivity.class));
            feedback("已触发（QQ 在后台运行时不会切前台），结果看 LSPosed 日志");
        });
    }

    private boolean canExact() {
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            return am != null && am.canScheduleExactAlarms();
        } catch (Throwable t) {
            return false;
        }
    }

    private void feedback(String s) {
        if (tvFeedback != null) {
            tvFeedback.setText(s);
        }
    }

    private void bind(Config cfg) {
        cbEnabled.setChecked(cfg.enabled);
        etTargets.setText(TextUtils.join(",", cfg.targets));
        cbAllPinned.setChecked(cfg.allPinned);
        cbPinnedOnly.setChecked(cfg.pinnedOnly);
        etTime.setText(cfg.timesText == null || cfg.timesText.isEmpty() ? cfg.timesDisplay() : cfg.timesText);
        cbRepeat.setChecked(cfg.repeatDaily);
        rbImage.setChecked(cfg.isImage());
        rbText.setChecked(!cfg.isImage());
        etPayload.setText(cfg.payload);
        etImage.setText(cfg.imagePath);
        etFolder.setText(cfg.folderPath);
        rbSrcFile.setChecked(!cfg.isFolderSource());
        rbSrcFolder.setChecked(cfg.isFolderSource());
        cbRecursive.setChecked(cfg.folderRecursive);
        cbAsEmoji.setChecked(cfg.picAsEmoji);
        etFallback.setText(cfg.fallbackText);
        rbKernel.setChecked(cfg.isKernelMode());
        rbUi.setChecked(!cfg.isKernelMode());
        etDelay.setText(String.valueOf(cfg.sendDelayMs));
        cbClose.setChecked(cfg.closeChatAfterSend);
        cbLocalEcho.setChecked(cfg.localEcho);
        cbUiFallback.setChecked(cfg.uiFallback);
        refreshStatus();
    }

    private Config collect() {
        Config cfg = new Config();
        cfg.enabled = cbEnabled.isChecked();
        cfg.targets.clear();
        String raw = etTargets.getText() == null ? "" : etTargets.getText().toString();
        for (String part : raw.split("[,，\\s]+")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                cfg.targets.add(s);
            }
        }
        cfg.allPinned = cbAllPinned.isChecked();
        cfg.pinnedOnly = cbPinnedOnly.isChecked();
        cfg.repeatDaily = cbRepeat.isChecked();
        cfg.payloadType = rbImage.isChecked() ? Config.TYPE_IMAGE : Config.TYPE_TEXT;
        cfg.payload = etPayload.getText() == null ? "" : etPayload.getText().toString();
        cfg.imagePath = etImage.getText() == null ? "" : etImage.getText().toString().trim();
        cfg.folderPath = etFolder.getText() == null ? "" : etFolder.getText().toString().trim();
        cfg.imageSource = rbSrcFolder.isChecked() ? "folder" : "file";
        cfg.folderRecursive = cbRecursive.isChecked();
        cfg.picAsEmoji = cbAsEmoji.isChecked();
        cfg.fallbackText = etFallback.getText() == null ? "" : etFallback.getText().toString();
        cfg.sendMode = rbUi.isChecked() ? Config.MODE_UI : Config.MODE_KERNEL;
        try {
            cfg.sendDelayMs = Integer.parseInt(etDelay.getText().toString().trim());
        } catch (Throwable ignored) {
            cfg.sendDelayMs = 1500;
        }
        cfg.closeChatAfterSend = cbClose.isChecked();
        cfg.localEcho = cbLocalEcho.isChecked();
        cfg.uiFallback = cbUiFallback.isChecked();
        // release 版不带调试开关
        cfg.probe = false;
        cfg.sniff = false;
        cfg.stickerCapture = false;

        cfg.timesText = etTime.getText() == null ? "" : etTime.getText().toString().trim();
        List<int[]> slots = cfg.timeList();
        cfg.hour = slots.get(0)[0];
        cfg.minute = slots.get(0)[1];
        return cfg;
    }

    private void refreshStatus() {
        Config cfg = Prefs.load(this);
        StringBuilder sb = new StringBuilder();
        sb.append("状态：").append(cfg.enabled ? "启用" : "停用");
        sb.append("　时间：").append(cfg.timesDisplay());
        sb.append(cfg.repeatDaily ? "（每天）" : "（一次）").append('\n');
        sb.append("目标：").append(cfg.allPinned ? "全部置顶会话" : TextUtils.join(",", cfg.targets)).append('\n');
        if (cfg.isImage()) {
            sb.append("内容：").append(cfg.isFolderSource() ? ("文件夹 " + cfg.folderPath) : cfg.imagePath)
                    .append(cfg.picAsEmoji ? "（表情图外显）" : "").append('\n');
        } else {
            sb.append("内容：").append(cfg.payload).append('\n');
        }
        sb.append("方式：").append(cfg.isKernelMode() ? "内核直发" : "界面模拟");
        sb.append("　下次：").append(new SimpleDateFormat("MM-dd HH:mm", Locale.US)
                .format(new Date(Scheduler.nextTriggerAt(cfg))));
        tvStatus.setText(sb.toString());
    }

    private static int countImages(File dir, int depth) {
        if (dir == null || !dir.isDirectory()) {
            return 0;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        int n = 0;
        for (File f : children) {
            if (f.isDirectory()) {
                if (depth > 0) {
                    n += countImages(f, depth - 1);
                }
            } else {
                String name = f.getName().toLowerCase();
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                        || name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".bmp")
                        || name.endsWith(".apng")) {
                    n++;
                }
            }
        }
        return n;
    }
}
