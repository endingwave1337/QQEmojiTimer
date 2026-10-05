# QQEmojiTimer（QQ 9.3.70 定时表情模块）

一个 LSPosed 模块：**作用域锁定 com.tencent.mobileqq（QQ 9.3.70）**，在设定时间向"置顶会话 / 指定 QQ 号"自动发送自定义表情包。

> 说明：本仓库里所有 QQ 内部类名、方法签名，都是直接解析本机 APK
> （D:/project/Downloadsbase(1).apk = QQ 9.3.70，包名 com.tencent.mobileqq）得到的，不是猜的。
> 配套解析脚本：D:/project/tools/dexdump.py

---

## 1. 已验证的 QQ 9.3.70 内部接口

| 用途 | 类 / 方法（9.3.70 实测存在） |
| --- | --- |
| 内核静态入口 | com.tencent.qqnt.msg.f ：f()→MsgService，i()→IKernelService，k()/m()→UixConvertService |
| 发消息 | com.tencent.qqnt.kernel.api.af#sendMsg(long msgId, Contact, ArrayList<MsgElement>, HashMap, IOperateCallback) |
| 生成消息 id | com.tencent.qqnt.kernel.api.af#generateMsgUniqueId(int): Long |
| QQ号 转 uid | com.tencent.qqnt.kernel.api.as#B(long)（= IUixConvertAdapterApi.getUidFromUin，走关系链数据库，**正确**）；会话列表里的 peerUid 也可直接用。createUidFromTinyId 只是本地拼串，**别用** |
| 会话对象 | com.tencent.qqnt.kernelpublic.nativeinterface.Contact(int chatType, String peerUid, String guildId)，chatType 1=好友 |
| 消息元素 | com.tencent.qqnt.kernel.nativeinterface.MsgElement（elementType + textElement / picElement / faceElement / marketFaceElement） |
| 文字 | com.tencent.qqnt.kernel.nativeinterface.TextElement#setContent(String) |
| 图片 | com.tencent.qqnt.kernel.nativeinterface.PicElement（sourcePath / fileName / fileSize / md5HexStr / picWidth / picHeight / picSubType） |
| 收藏/商城表情 | com.tencent.qqnt.kernel.nativeinterface.MarketFaceElement（emojiPackageId / emojiId / faceName） |
| 结果回调 | com.tencent.qqnt.kernel.nativeinterface.IOperateCallback#onResult(int, String) |
| 置顶会话 | IKernelService#getRecentContactService() 返回 com.tencent.qqnt.kernel.api.ak，其 getRecentContactInfos(IKernelRecentGetContactCallback) 回调里 RecentContactInfo 的 topFlag(byte) / peerUin(long) / chatType(int) |
| 新版聊天界面 | com.tencent.aio.main.activity.ChatActivity（旧界面 com.tencent.mobileqq.activity.ChatActivity 仍在） |
| 发送按钮 id | fun_btn（资源表里仍在）；输入框 id 名含 input / aio_input |

元素类型（NT 约定值，代码里是常量，版本变化时改一处即可）：
1=文字，2=图片，6=表情(FaceElement)，10=商城/收藏表情(MarketFaceElement)。

---

## 2. 工作原理

    LSPosed 注入 QQ 主进程
            |
    AlarmManager 精确闹钟
            |
    模块 App：TriggerActivity
            |-- 1) 写入 pending_trigger_at
            |-- 2) 广播 com.endingwave.qqemoji.action.FIRE （QQ 进程活着时立即执行）
            |-- 3) 冷启动 QQ
            |
    QQ 进程内 Engine
            |-- 读配置（XSharedPreferences，失败回退 ContentProvider）
            |-- 解析目标（指定 QQ 号，可校验是否置顶；或"全部置顶会话"）
            |-- 内核直发 MsgService.sendMsg(...)   ← 默认，不用切前台
            |-- 兜底：打开聊天界面 -> 填内容 -> 点发送

发送内容支持三种：
1. **文字表情**：QQ 表情代码 [微笑] [doge] 或直接 emoji —— 最稳；
2. **本地图片表情包**：填图片绝对路径，按图片消息（PicElement）发出；
3. **收藏/商城表情**（实验）：填 packageId:emojiId[:名称]，用 MarketFaceElement 发。

---

## 3. 目录结构

    QQEmojiTimer/
      app/src/main/assets/xposed_init                 # 入口类声明
      app/src/main/java/com/endingwave/qqemoji/
        hook/MainHook.java      # IXposedHookLoadPackage，只进 com.tencent.mobileqq 主进程
        hook/Engine.java        # 触发调度：广播 / 冷启动待执行任务
        hook/NtBridge.java      # ★ NT 内核直发（类名来自 9.3.70 实测）
        hook/PinnedReader.java  # 置顶会话读取（RecentContactInfo.topFlag）
        hook/Targets.java       # 目标解析 + 置顶过滤
        hook/UiSender.java      # 界面模拟兜底
        hook/Probe.java         # 探测模式：打印真实类名/方法
        config/                 # 配置模型、存储、跨进程读取
        alarm/                  # 精确闹钟、触发 Activity/Receiver、开机重排
        ui/MainActivity.java    # 设置界面
      app/src/main/AndroidManifest.xml   # xposedmodule / xposedscope / xposedsharedprefs

---

## 4. 编译

本机没有 Android SDK，请用 Android Studio 打开 D:/project/QQEmojiTimer
（AGP 8.5.2 / Gradle 8.7+ / JDK 17），或命令行：

    cd QQEmojiTimer
    gradle wrapper --gradle-version 8.7
    gradlew assembleDebug          # Linux/macOS 用 ./gradlew

产物：app/build/outputs/apk/debug/app-debug.apk。
Xposed API 用 compileOnly，不会打进 APK。

## 5. 安装与作用域

1. 安装 APK，在 LSPosed 管理器里启用模块；
2. **作用域**：勾选 com.tencent.mobileqq。
   清单里已用 xposedscope 数组预设了该包名（安装时默认推荐），
   但实际作用域以管理器里勾选为准，请务必确认；
3. 强制停止 QQ 再打开，LSPosed 日志里应出现：QQEmojiTimer: 模块已注入 com.tencent.mobileqq

## 6. 使用

1. 打开"QQ表情定时器"，填：目标 QQ 号（逗号分隔）或勾选"全部置顶会话"、时间 HH:mm、
   内容（如 [微笑]）、是否每天重复；
2. 建议先点"立即触发一次（测试）"验证链路，观察 LSPosed 日志；
3. 点"保存并设置定时"；Android 12+ 还要点"申请精确闹钟权限"。

日志筛选关键字：QQEmojiTimer

## 7. 排错与版本适配

| 现象 | 原因 / 处理 |
| --- | --- |
| 日志没有"模块已注入" | 作用域没勾 QQ，或 QQ 没重启 |
| 内核未就绪(com.tencent.qqnt.msg.f 返回 null) | QQ 刚冷启动内核还没 init；代码会重试，仍失败就等几秒再触发 |
| 无法把 QQ 号转换成 uid | QQ 未登录，或 createUidFromTinyId 行为变化 |
| 读不到置顶列表 | 该版本 RecentContactInfo 字段变了，开探测模式核对 |
| 发送回调 code != 0 | 内核拒绝（风控 / 非好友 / 被限制） |
| 换 QQ 版本后失效 | 打开设置里的"探测模式"，重启 QQ，日志会打印候选类的全部字段与方法签名，把 NtBridge / PinnedReader 顶部的常量换掉即可 |

换版本后可以用脚本核对：

    python dexdump.py 'MsgService'                        # 按类名正则搜索
    python dexdump.py --methods com.tencent.qqnt.msg.f    # 打印某类的字段与方法签名
    python dexdump.py --scan '^Lcom/tencent/qqnt/.*;$' --ret 'Lcom/tencent/qqnt/kernel/api/IKernelService;'

## 8. 已知限制与风险

- **未做真机编译验证**：类名/签名来自 APK 实测，但本环境没有 Android SDK；
  编译报错请把错误贴回来，我按提示修。
- **PicElement 本地图片直发是"尽力而为"**：内核通常要自己走富媒体上传；失败请改用文字表情，
  或先切界面模式验证。
- **界面模拟依赖控件 id**：QQ 换 UI 后可能找不到输入框/按钮，按日志调整 UiSender 里的关键字。
- **必须保持 QQ 登录**；部分国产 ROM 需要允许自启动 + 后台运行，否则闹钟唤不起来。
- **风控**：定时自动发消息属于非官方用法，可能触发 QQ 限制，请只在自己账号/小号上低频使用，
  遵守 QQ 用户协议。

---

## 9. 第二轮修复（内核模式不发消息）

### 现象
LSPosed 日志里只有 `内核发送成功`，没有回调，QQ 里既没有本地消息也没有发出去。

### 排查方法
把 QQ 9.3.70 的 APK 用 jadx 反编译，直接读它的发送实现：

| 反编译到的位置 | 内容 |
| --- | --- |
| `com.tencent.qqnt.msg.MsgService#i1` | `sendMsg(0L, peer, msgElements, msgAttrs, cb)`（对外 API 形态） |
| `com.tencent.qqnt.aio.api.impl.MsgSendHelperImpl#sendMsg$lambda$18` | 同上，`0L` |
| `com.tencent.qqnt.aio.api.impl.AIOEmoApiImpl#sendEmoMsgFromOther` | **无界面给某个 Contact 发表情**：先 `generateMsgUniqueId(contact.chatType)` 取 msgId，再 `sendMsg(msgId, ...)` |
| `com.tencent.qqnt.msg.MsgUtil#s(String)` | 文本元素就是 `elementType=1` + `TextElement(content)`（和本模块一致） |
| `com.tencent.qqnt.kernel.api.impl.UixConvertService#B(long)` | = `IUixConvertAdapterApi.getUidFromUin(QQ号)`，走关系链数据库 |
| `com.tencent.qqnt.kernel.api.impl.MsgService#S` | `sendMsg(msgId, peer, elements, new HashMap<>(), cb)`，空属性表没问题 |

### 结论（两处错误）
1. **uid 取错了**：旧代码优先用 `createUidFromTinyId(uin, 0)`，那只是本地拼出来的 uid，
   真正的 uid 要用 `as#B(uin)`（= getUidFromUin，查关系链），或者直接用会话列表里 QQ 自己用的 `peerUid`。
2. **msgId 取错了**：旧代码用 `generateMsgUniqueId(0)`，把参数当成 0；
   QQ 传的是 `generateMsgUniqueId(contact.chatType)`。

### 现在的流程
1. 读会话列表 `RecentContactInfo`（peerUin / peerUid / chatType / topFlag）；
2. 目标 QQ 号 -> 优先用会话列表里的 `peerUid`，其次 `as#B(long)`，最后 `createUidFromTinyId`，并把来源打进日志；
3. `msgId = generateMsgUniqueId(chatType)`；
4. `sendMsg(msgId, Contact(chatType, uid, ""), [MsgElement], {}, IOperateCallback)`；
5. 等回调：`code == 0` 才算成功；超时/非 0 会在日志里写明；
6. 失败且开着"内核失败自动切界面模式"时，自动改用界面模拟兜底。

### 排错开关
- **抓包模式**：勾上后重启 QQ，**手动**发一条消息，日志里会出现
  `【抓包】sendMsg msgId=... chatType=... peerUid=... elements=... | type=... text=...`，
  这条就是 QQ 自己发消息的真实参数，可以和本模块的日志直接对比。
- **探测模式**：打印候选类的全部字段与方法签名，用于换版本后重新适配。

日志关键字都只有一个：`QQEmojiTimer`。

---

## 10. 第三轮修复（图片报 rich media transfer failed / QQ 崩溃）

### 日志给出的关键信息
- `uid 解析[会话列表]: uin=2855823634 uid=u_jXuYF717VPN7wGjFdEIKlQ chatType=1` —— uid 已经对了；
- 文字：`msgId=7693001252321723342`，`内核回调 code=0` —— 内核收下了；
- 图片：`内核回调 code=-1 msg=rich media transfer failed` —— 内核处理不了我们造的图片元素；
- 抓包：`【抓包】sendMsg msgId=0 chatType=1 peerUid=u_jXuYF717VPN7wGjFdEIKlQ elements=1 | type=2 pic=/sdcard/...png attrs={}`。

### 反编译得到的三个结论

1. **图片元素不能自己拼字段。** QQ 的图片元素是
   `IMsgUtilApi.createPicElement(path, quality, subType)` 造的，内部会算 md5/fileSize/宽高/picType
   （见 MsgUtil.l 的 smali：sourcePath / picSubType / fileName / fileSize / picWidth / picHeight /
   original / md5HexStr / picType / isInApplicationDataPath）。
   之前手工填 `picType=1000` 之类的值 → 内核直接 `rich media transfer failed`。

2. **QQ 的完整发送流程是三步**（AIOMsgService.a + sendMsgWithMsgId）：
   ```
   msgId = generateMsgUniqueId(contact.chatType)
   addSendMsg(msgId, contact, elements, attrs)   // 本地先建一条"发送中"的记录
   sendMsg(msgId, contact, elements, attrs, cb)  // 真正发出去
   ```
   少了 `addSendMsg`，就算内核回了 code=0，**自己聊天窗口里也看不到这条消息**，
   很容易误判成"没发出去"。

3. **新版会话界面不能硬闯。** `com.tencent.aio.main.activity.ChatActivity` 需要 AIO 自己的一整套参数，
   只用 `uin/uintype` 去 startActivity 会让 QQ 崩溃（上一轮日志里正好有这一步）。

### 现在的做法

| 项目 | 现在 |
| --- | --- |
| 元素 | `QRoute.api(IMsgUtilApi.class)`：文字用 `createTextElement(String)`，图片用 `createPicElement(path, false, picSubType)`；失败才手工兜底 |
| 发送 | `generateMsgUniqueId → addSendMsg → sendMsg`，与 QQ 完全一致 |
| 界面兜底 | **默认关闭**；候选界面只保留 `SplashActivity / ChatActivity / AIOActivity`（旧入口），不再碰新版 AIO |
| 本地留痕 | 新增开关（默认开），关掉就只发不建本地记录 |

### 测试建议
1. 先用**文字表情**发一次：自己窗口应该立刻出现这条消息（本地留痕），对方也能收到；
2. 再用**本地图片表情包**发一次：路径要能被 QQ 读到（例如 /sdcard/DCIM/... 或 /sdcard/Android/data/com.tencent.mobileqq/...）；
3. 若图片仍失败，把日志里那行 `【抓包】` 和模块自己的 `元素[...]` 一起发我 —— 这两行现在会把元素的**全部字段**打出来，可以一比一对齐。

---

## 11. 第三批功能（v1.2）

### 1) 文件夹随机发图
- 设置里把"发送内容类型"选**本地图片**，再选**文件夹随机**，填文件夹路径（如 `/sdcard/Pictures/stickers`），可选"包含子目录"；
- 支持的扩展名：jpg / jpeg / png / gif / webp / bmp / apng / heic；
- 每次触发时在 **QQ 进程内**扫描文件夹并随机挑一张（代码见 `Engine.pickRandomImage`），日志里会打 `随机选中: /sdcard/...`；
- 保存时会立刻统计一次文件夹里的图片数量并弹提示，读不到会明确告诉你；
- 存储权限说明：QQ 只有 `READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE`（没有 MANAGE_EXTERNAL_STORAGE），
  所以表情包放 `/sdcard/Pictures/...`、`/sdcard/DCIM/...` 这类相册目录最稳；
  其它自建目录若读不到，日志会打"读不了文件夹（可能是安卓分区存储限制）"。

### 2) 多个时间点
- 时间框支持 `09:30,12:00,21:30`（也认中文逗号、分号、空格、`0930` 这种写法），最多 24 个；
- `Scheduler` 给每个时间点排一个独立的精确闹钟（requestCode = 1100 + 序号），每次触发后重新排全量；
- 两个时间点挨得太近时，第二个会**排队**等第一个发完再跑，不会丢。

### 3) 外显 [动画表情] 而不是 [图片]
- 新增开关"**作为表情图发送（picSubType=1）**"，勾上后发送时把 `PicElement.picSubType` 置 1；
- 依据：QQ 内部就是靠这个字段区分表情图和普通图片的（APK 里有
  `isCommercialEmoticon picSubType:`、`isCommercialEmoticonFromFav picSubType:`，
  以及 `EMOJI (pic), picSubType=` / `IMAGE (pic), picSubType=` 两种日志）；
- 如果勾上后外显仍是 [图片]，说明这个版本用的是别的取值：把设置里 JSON 的 `picSubType`
  改成 2 / 3 再试（高级字段，代码里保留），或者把日志给我，我按 QQ 的真实取值改。

---

## 12. 第四批功能（v1.3）：把 FunBox 的加密表情包拿来发

### 先说结论
FunBox v2414 是 **Zygisk 模块**（`zygisk/funloader.so` + `libfun.so`），面板逻辑在 native 层，
`.so` 里的字符串也是混淆过的；`classes.dex` 只有 260 个类，是加载器（还打包了 smali/baksmali 工具链，
运行时自己组装 dex）。**静态逆不出它的解密算法**，所以本模块不碰解密。

### 思路：在"交给 QQ 发送"的那一刻把表情记下来，之后原样回放
FunBox 的表情最终要交给 QQ 的内核发送（`MsgService.sendMsg` 的 `MsgElement`）。
本模块本来就在 QQ 进程里，所以：

1. **录制**（两种手段，勾一个"录制表情"全开）：
   - `StickerStore.installCapture`：hook 内核 `sendMsg`，凡是**不是本模块发的** FaceElement /
     MarketFaceElement，就把元素类型 + 子元素全部字段存成
     `<录制目录>/<md5>.json`；
   - `StickerStore.installBitmapCapture`：hook `BitmapFactory.decodeFile / decodeStream / decodeByteArray`，
     如果**调用栈里有 FunBox 的痕迹**（`fun.box001.*` / `have.fun`，关键字可在配置里改），
     就把解码出来的 Bitmap 存成 `<录制目录>/img_<特征>.png`（按尺寸+采样像素去重，重复的不会重复存）。
2. **发送**（设置里新增"随机已录制表情"）：
   - 从录制目录里随机挑一个 `.json`，把元素字段还原成 `MsgElement` 再发一次 —— 和你手动点发送一模一样，
     对面收到的就是同一个表情，外显也是 `[动画表情]`；
   - 抓到的 `.png` 也可以直接用"文件夹随机"来发（把文件夹指到录制目录即可，它会自动忽略 .json）。

### 录制目录
默认 `<QQ 外部目录>/qqemoji_stickers`，也就是
`/sdcard/Android/data/com.tencent.mobileqq/files/qqemoji_stickers`（QQ 进程可读可写，模块发送时也在 QQ 进程里）。
设置里可以改成别的路径，日志里也会打印实际用的目录。

### 使用步骤
1. 设置里勾"录制表情"，保存，重启 QQ（钩子要重新装）；
2. 打开 QQ 里 FunBox 的表情面板**划一遍**（或手动发几个表情）——
   日志里会连续出现 `已抓取图片 -> img_xxx.png` / `已录制表情(faceElement) -> ...`；
3. 把内容类型改成"随机已录制表情"，设好时间，到点就会随机发一个真表情。

### 注意
- 抓到的是**当前账号能用的表情**：如果是 FunBox 注入的本地表情包（服务器不认的 id），
  回放效果和你手动发完全一致（对面能不能渲染取决于 QQ 服务器，不由本模块决定）；
- 抓图关键字默认是一组 FunBox 特征（`funbox` / `have.fun` / `box001` / `fun.box`），
  故意不用泛化的 "fun"，避免把 QQ 自己的 `VipFunCall` 之类图片也抓进来；
  如果 FunBox 版本换了包名，把配置里的 `captureKeyword` 改掉即可。

---

## 13. FunBox 表情文件真相（v1.4）

### 根本没有加密
用你复制的样本 `00AAE507FE6FCE29495BA1B24BA2955C` 验证：

```
magic       : 52 49 46 46 | 57 45 42 50 | 56 50 38 20   ->  "RIFF" "WEBP" "VP8 "
RIFF 长度   : 0x886c + 8 = 34932，文件实际大小 34932      ->  完全吻合
VP8 帧      : 9d 01 2a 关键帧起始码，尺寸 512 x 396
```

也就是说，**FunBox 的表情就是标准 WebP 图片**，只是把文件名换成了 MD5、去掉了 `.webp` 后缀，
所以在相册/文件管理器里打不开，看着像加密。

### 本模块怎么处理
1. 扫描文件夹时**按文件头识别**（RIFF/WEBP、PNG、JPEG、GIF、BMP），不再只看扩展名 —— 见 `Engine.sniffExt`；
2. 发送前如果文件名没有正确后缀，就先复制成
   `<QQ cache>/qqemoji_send/<md5>.webp` 再发（QQ 生成图片元素时会按扩展名算 picType，
   没后缀会导致之前那种 `rich media transfer failed`）—— 见 `Engine.ensureSendable`；
3. 设置里新增 **"一键填入 FunBox 表情目录（.fun）"** 按钮，
   直接填 `/storage/emulated/0/Android/media/com.tencent.mobileqq/.fun/`，
   配合"文件夹随机"就能定时随机发 FunBox 的表情包了。

### 用法
1. 设置 → 内容类型选"本地图片" → "文件夹随机" → 点"一键填入 FunBox 表情目录" → 保存；
2. 时间填好（支持多个时间点），到点就会随机挑一个表情发出去；
3. 想要外显 `[动画表情]` 就再勾上"作为表情图发送（picSubType=1）"。

> 注：如果 FunBox 后续把文件改成真正的加密（现在这个版本不是），
> 第 12 节那套"录制 + 回放"仍然可用（勾"录制表情"即可）。

---

## 14. 修"FunBox 表情发不出去"（v1.5）

### 原因
FunBox 的目录是三层结构：

    .fun/Sticker/Config
    .fun/Sticker/Storage/<表情包名>/<表情文件，无扩展名>
    .fun/Sticker/UsageCount

而 v1.4 的文件夹扫描**默认不递归**，如果文件夹填的是 `.fun/`，一个图片都扫不到
（日志里会出现 `文件夹里没有可用图片`），自然发不出去。

### 这次改了什么
1. **自动递归兜底**：不勾"包含子目录"时，如果当前目录没扫到图片，会自动递归子目录再找一次，
   并把 `目录 X 共找到 N 张图片` 打进日志；
2. **一键按钮**改成直接填 `.fun/Sticker/Storage/` 并自动勾上"包含子目录"；
3. **中转目录换成 QQ 自己的媒体目录**：`/storage/emulated/0/Android/media/com.tencent.mobileqq/.qqet_send/`
   （QQ 对"媒体目录里的正经图片文件"最不挑；原来放内部 cache 有一定风险），
   依次回退到外部 cache、内部 cache；
4. **PNG 兜底**：如果发原图失败，自动把文件解码后重新编码成 PNG 再发一次（动图会丢动效）；
5. 保存时的提示改成"本 App 读不到目录不影响发送，以 LSPosed 日志为准"
   （模块 App 和 QQ 的存储权限不同，App 里数不到是正常的）。

### 出问题时看这三行日志
| 日志 | 含义 |
| --- | --- |
| `目录 ... 共找到 N 张图片` | N=0 就是路径/权限问题，N>0 说明扫到了 |
| `随机选中: ... -> 另存为 ...` | 说明按文件头识别成功、并补了扩展名 |
| `内核回调 uin=... code=...` | code=0 才算真发出去，非 0 会带原因 |

---

## 15. 修"内核直发时 QQ 聊天界面自动弹出来"（v1.6）

### 原因
闹钟到点时，模块 App 的 `TriggerActivity` **无条件**执行了
`getLaunchIntentForPackage("com.tencent.mobileqq")` 把 QQ 拉到前台。
内核直发本来完全不需要 QQ 界面，所以这个动作纯属多余 —— 而 QQ 被拉起来会恢复
上一次的界面（之前测试时打开过的聊天），看起来就是"自动打开了聊天界面"。

### 改法：先问一句，再决定要不要打扰
1. 触发时改发**有序广播**（`sendOrderedBroadcast` + 结果接收器）；
2. 注入在 QQ 进程里的钩子收到广播后 `setResultCode(0x51E7)` —— 等于回一句"我在，我自己发"；
3. 模块 App 拿到 `ALIVE` 就**什么都不做**（QQ 保持后台，界面纹丝不动）；
4. 只有结果码不是 `ALIVE`（QQ 进程没在跑）才拉起 QQ —— 这时必然会看到界面，
   属于必要代价，QQ 冷启动 8 秒后由注入代码把消息补发出去；
5. 等待上限 1.5 秒，超时按"没在跑"处理。

逻辑集中在新的 `alarm/Fire.java`，`TriggerActivity` / `TriggerReceiver` 都走它。

### 效果
- QQ 在后台运行时：定时发送**完全静默**，不会切前台、不弹聊天界面；
- QQ 被杀了：会拉起 QQ（唯一会看到界面的情况），起来后自动补发。

---

## 16. release-1.0

- 版本：`versionName = release-1.0`，`versionCode = 10`
- 产物：`app/build/outputs/apk/release/app-release.apk`（同时复制了一份到 `D:/project/release/QQEmojiTimer-release-1.0.apk`）
- 签名：**沿用 debug 签名**（`signingConfig signingConfigs.debug`），
  所以可以直接覆盖安装之前的调试包，**不用卸载**，配置也不会丢
- `lint { checkReleaseBuilds false }`：JDK 25 下 lint 分析器会崩，release 不走 lint

### 删掉的调试项（UI 里已经没有了，老配置残留也会在打开界面时被清掉）
- 探测模式（probe）
- 抓包模式（sniff）
- 录制表情 / 表情录制目录（stickerCapture、stickerDir）
- 内容类型里的"随机已录制表情""收藏/商城表情"两个选项
  （老配置如果是这两个，会自动回退成"文字表情"）

### 删掉的弹窗
所有 Toast 都去掉了，反馈集中显示在界面下方的**状态区**（新增 `tv_feedback`）：
"已保存 N 个时间点…"、"已填入 FunBox 表情目录…"、"已触发…"、"已具备精确闹钟权限"等，
再也不会弹小框。状态区下面那块 `tv_status` 仍然显示当前配置摘要和下次触发时间。

### 保留的功能项
时间点（多个）、目标/置顶校验、文字表情、本地图片（单张/文件夹随机）、
一键填入 FunBox 目录、包含子目录、作为表情图发送（picSubType=1）、
发送方式（内核直发/界面模拟）、界面等待毫秒、发送后关窗口、本地留痕、内核失败回退界面模式。
