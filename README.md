# QQEmojiTimer

> 一个 LSPosed 模块：**定时给指定好友 / 置顶会话自动发送自定义表情**（QQ 9.3.70）。
>
> 作用域：`com.tencent.mobileqq`

---

## 功能

### 定时发送
- 支持**多个时间点**，用逗号分隔：`09:30,12:00,21:30`（最多 24 个，也认 `0930` 这种写法）
- 可选**每天重复**，或只发一次
- 使用精确闹钟（AlarmManager），开机 / 模块更新后自动重新排定
- 到点由 QQ 进程内的钩子执行，**QQ 在后台运行时完全静默**，不切前台、不弹聊天界面
  （只有 QQ 进程被系统杀掉时才会把它拉起来，冷启动后自动补发）

### 发送对象
- **指定 QQ 号**：支持多个，逗号分隔
- **全部置顶会话**：自动读取你的置顶聊天列表
- **置顶校验**：可以要求"只有该会话处于置顶状态时才发"，取消置顶就自动跳过

### 发送内容
| 类型 | 说明 |
| --- | --- |
| 文字表情 | QQ 表情代码（`[微笑]`、`[doge]`…）或直接 emoji，最稳 |
| 本地图片 | 指定单张图片路径 |
| 文件夹随机 | 指定文件夹，每次触发随机挑一张；支持子目录，**按文件头识别图片**，不需要扩展名 |
| FunBox 表情包 | 设置里点"一键填入 FunBox 表情目录"即可直接用 FunBox 下载的表情包（它们是去掉后缀的 WebP，模块会自动补扩展名再发） |

- **表情外显**：勾选"作为表情图发送（picSubType=1）"后，会话列表外显为 `[动画表情]` 而不是 `[图片]`
- **发送方式**：默认**内核直发**（直接调用 QQ NT 内核，无需切前台）；也可切换为"界面模拟"（打开聊天窗口填入并点发送）
- 内核失败时可以自动回退到界面模式重试

---

## 使用

### 环境要求
- Android 8.0 及以上（minSdk 24）
- 已安装 **LSPosed**（Xposed API 93+）
- **QQ 9.3.70**（模块内部的类名/方法签名是按这个版本适配的）

### 安装
1. 到 Releases 页面下载 APK 安装；
2. 在 **LSPosed 管理器** 里启用本模块；
3. **作用域勾选 `com.tencent.mobileqq`**（必须，否则不生效）；
4. 强制停止 QQ 再打开，LSPosed 日志里出现下面这行就说明注入成功：

       QQEmojiTimer: 模块已注入 com.tencent.mobileqq / com.tencent.mobileqq

### 配置
打开「QQ表情定时器」：

| 设置项 | 说明 |
| --- | --- |
| 启用定时发送 | 总开关 |
| 目标 QQ 号 | 多个用逗号分隔，例如 `10001,10002` |
| 发送给所有置顶会话 | 勾上后忽略上面的 QQ 号，直接发给全部置顶好友 |
| 仅当目标会话置顶时才发送 | 取消置顶的会话会被自动跳过 |
| 发送时间 | 支持多个：`09:30,12:00,21:30` |
| 每天重复 | 不勾则只发一次 |
| 发送内容类型 | 文字表情 / 本地图片 |
| 单张图片 / 文件夹随机 | 图片模式下二选一 |
| 一键填入 FunBox 表情目录 | 自动填 `/storage/emulated/0/Android/media/com.tencent.mobileqq/.fun/Sticker/Storage/` 并勾上"包含子目录" |
| 作为表情图发送 | 外显 `[动画表情]`（picSubType=1） |
| 发送方式 | 内核直发（推荐）/ 界面模拟 |
| 本地留痕 | 发送前先在自己聊天窗口建一条"发送中"的消息 |

设置完成后点 **「保存并设置定时」**；Android 12+ 建议再点一下 **「申请精确闹钟权限」**。
想先验证链路，点 **「立即发送一次」**，然后看 LSPosed 日志。

### 查看日志 / 排错
LSPosed 日志里筛选关键字 **`QQEmojiTimer`**，常见几行：

| 日志 | 含义 |
| --- | --- |
| `模块已注入 com.tencent.mobileqq` | 作用域正确、注入成功 |
| `uid 解析[会话列表]: uin=... uid=u_...` | 已正确拿到发送对象的 uid |
| `目录 ... 共找到 N 张图片` | 文件夹模式扫到的图片数量，N=0 说明路径不对 |
| `随机选中: ... -> 另存为 ...` | 选中的图片，以及自动补扩展名后的中转文件 |
| `内核回调 uin=... code=0` | **code=0 才是真正发出去了**，非 0 会带原因 |

### 从源码构建
用 Android Studio 打开本仓库，或命令行：

    ./gradlew assembleRelease

- AGP 8.5.2 / Gradle 8.7+ / JDK 17
- Xposed API 为 `compileOnly`，不会打进 APK
- 开发过程中的逆向分析与排错记录见 `docs/DEVELOPMENT.md`

---

## 免责声明

- 本项目仅供**学习与技术交流**，请勿用于骚扰、刷屏等用途；使用产生的一切后果由使用者自负。
- 自动发送消息属于**非官方用法**，可能触发 QQ 的风控策略，请**低频**使用，建议只在自己的账号上测试。
- 本项目与腾讯公司**没有任何关系**，未使用任何腾讯官方代码或资源。
- 请遵守你所在地区的法律法规以及 QQ 用户协议。

## 开源协议

本项目采用 **GNU General Public License v3.0** 发布，详见 [LICENSE](LICENSE)。

    Copyright (C) 2026 QQEmojiTimer Contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
