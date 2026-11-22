# DIVA-Launcher v3.8 — 复现 d7 的 58.7fps，去掉死锁补丁，加音频诊断

## 来了什么

你提供的 d7 逐字节对比非常关键——它证明了 **58.7fps 是可行的**，我之前"游戏逻辑锁 30fps"的判断是错的。v3.8 基于 d7 的实测成果，做两件事：**保留性能、去掉死锁、并查声音**。

## 性能：复现 d7 的性能核心，去掉卡死元凶

我逐行追源码确认了 d7 三个 native 补丁的作用：

| d7 补丁 | 作用 | v3.8 |
|---|---|---|
| `HonestLabels()→false` @0xa62274 | label 立即写，消除 guest 每帧等 GPU 的 17-50ms（这就是 v3.7 只有 20 多帧的真正瓶颈） | **保留** |
| 跳过 GpuDirty 刷新 @0xb204dc | 减少纹理重传 | **保留** |
| `SubmitLockOnDecode()→true` @0xc19c94 | 强制走 `WaitPreviousFrame()` 等 frames_retired，与 HonestLabels=false 时序冲突 → **开头死锁** | **去掉** |

关键：第三个补丁是 d7"帧数变高但卡死在开头"的元凶。它强制 guest 等 GPU 完成信号，而前两个补丁恰恰让信号提前写，时序对不上就互等。v3.8 不打这个补丁，走 native 默认的安全路径（v3.7 日志证实该路径 `wait 0.00ms` 且不死锁）。

同时写入 d7 的全部 16 个环境变量（UMA=1、单 recording 线程、VBlank=0 等），精确复现 d7 运行环境。

## 声音：加诊断，定位无声原因

日志显示音频后端正常启动、端口 0/1/17 都打开、SoundCue 线程都在，没有明显报错。所以问题不在"没启动"，而在数据链路上。v3.8 加了 `BB_AUDIO_STATS=1`，下一版日志会每 5 秒打印每个音频端口的 buffer 数、最大间隔、队列、是否 starved。这能一次判定：

- 若 guest 有输出（buffers 正常）→ 问题在 Android AudioTrack 播放端
- 若 starved 或 0 buffer → 问题在 guest 音频输出

## 覆盖安装

- 签名与 v1.1~v3.7 完全一致（debug key，SHA-256 `04908028...e5e18`）。
- 逐条目哈希验证：除 classes.dex / libbbgpu.so / 签名外，AOT / boot-linked / 驱动 / 其他库全部逐字节一致。
- so 动态符号表未变（8254），ABI 完全兼容。直接覆盖即可。

## 请回传

跑一下，把新的 `diva-boot.log` 发来，我重点看：
1. 是否还卡在开头（预期不卡了）
2. `Frame stats:` 的 FPS（预期往 50-60 走）
3. `Audio stats port N:` 行（判定无声原因）

如果还有问题，现在有硬数据，我能继续精确定位。
