# DIVA-Launcher v7.0 — 修「没声音」+ 建立音频可观测性

## 先说结论：Based on 证据，不猜

从你上传的日志（v4.1-Safe 那份）能确定的：

| 环节 | 证据 | 判断 |
|---|---|---|
| 游戏产出 PCM | `Audio stats ... 469 buffers/5s (期望468), starved 0` | ✅ 正常 |
| native 写进 FIFO | `Runtime: audio backend fifo (s16 stereo 48 kHz)` | ✅ 正常 |
| **FIFO → Java AudioTrack → 喇叭** | **native 日志里完全没有这段的统计** | ❓ 这是盲区，一直看不到 |

也就是说：**问题几乎肯定在 Java 播放端**，而它的报错只写进安卓 logcat、从来没进 diva-boot.log，所以过去每一版我们都是在瞎猜。这一版先把这个盲点补上。

## 这一版改了什么（只动了 AudioBridge，其余逐字节不变）

改动范围：重新编译 dex 里的 `AudioBridge` 一个类（`AudioBridge.smali` + lambda 类），**其他所有类、libbbgpu.so、payload 全部逐字节不变**。签名与 v6.0 完全一致，可直接覆盖安装。

修的 3 个真实的无声隐患：

1. **`AudioTrack.write()` 返回负值不再杀死线程**
   旧代码遇到负值直接 `throw IllegalStateException` → 音频线程永久退出 → 从此再没声音。这在 Android 12+ 上偶发（ERROR_BAD_VALUE/WOULD_BLOCK）。改成记录并重试，不再自杀。
2. **显式 `setVolume(1.0, 1.0)`**
   部分 ROM 上 AudioTrack 初始音量不是满值。
3. **建立可观测性：诊断文件**
   每 5 秒把音频状态写到手机存储的 `diva-audio-diag.txt`（可随时用文件管理器查看），包含：
   - `state` / `head`：AudioTrack 是否真的在播放、播放头是否推进
   - `read` / `written`：Java 是否真的从 FIFO 读到了数据、写进了多少字节
   - `writeErrors`：写入失败次数

## 关于「卡」：诚实说明

日志里的 Frame stats 显示：

```
54.6 FPS; GPU thread idle 45.6%; 0 shader compiles; host copies blocked 32.1% (15/frame)
```

关键：**GPU 有 45% 在闲着，shader 编译是 0** —— 说明瓶颈不在 GPU 算力，而在 **CPU↔GPU 的数据拷贝（host copies 占 32%）**。所以 v6.0 的 fast_shader_math 对这种场景帮助有限。真正降卡的有效手段按优先级：

1. **降内部渲染分辨率**：设置页 → 分辨率 → 选「超级性能 640×360」+ 升采样选「GSR 2」。这是最直接、收益最大的（当前日志显示是 1920×1080 全分辨率）。
2. 纹理质量调到最低档，关掉 RCAS 锐化。
3. 若还卡，下一步才考虑 native 层优化 host copy 路径（要重编 so）。

这一版不含卡顿改动——不想把两件事混在一起，避免又要你重装来回试。

## 请你做的（一次搞定，不用反复试）

1. 覆盖安装 v7.0，进游戏玩一会儿（哪怕没声音）。
2. 用文件管理器打开手机内部存储的 **`diva-audio-diag.txt`**，把内容发我。
3. 顺手回传 `diva-boot.log`。

根据这个诊断文件，一次就能定位到底卡在哪一段，然后我做确定的修复——不再让你当测试员。

## 校验信息

- 签名：与 v6.0 一致 `04908028...e5e18`，可覆盖安装（v3 签名）
- libbbgpu.so SHA256：`41368300...8abe2`（与 v6.0 逐字节一致）
- APK SHA256：`566e51310ea4a88e96a056609f664cba4c46619e1f0f0c8ad6cc9772c0ea5c4e`
- 差异条目：仅 `classes.dex`（AudioBridge 两个类）+ 签名文件 + 资源重编码；manifest 语义、so、payload 全部不变
