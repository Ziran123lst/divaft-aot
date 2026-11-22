# DIVA-Launcher v3.6 — 解除 30fps 锁 + 分辨率修复 + 死锁修复

v3.5 让你卡死在开头、帧率没提升,根因是 v3.5 改错了地方。v3.6 从真机日志逐行追到源码,找到三个确定的根因,全部在 dex 层修复(native/AOT/签名不变)。

## 先说 v3.5 为什么卡死

v3.5 加了 `BB_SUBMIT_LOCK=gpu`,真机日志最后一行铁证:
`GPU watchdog: waited 10 s for submission ... the port's threads are waiting for each other`
—— 线程互等死锁,所以卡在开头。而且日志证明改了 SubmitDone 之后 `EOP QUEUE waits` 从 37ms 降到 19ms、SubmitDone 前一帧等待归零,**帧率却仍是 27.8fps**——证明提交等待根本不是瓶颈。

## 真正的 30fps 锁(这次是铁证)

日志:`VideoOut: vblank 60 Hz, frame limit 60 FPS`,GPU idle 85%、只花 2-3ms/帧。
源码 `driver.cpp`:
```
if (flip_slot && vblank_status.count % (main_port.flip_rate + 1) == 0) { Flip(request); }
```
- 游戏调用 `sceVideoOutSetFlipRate` 设 `flip_rate=1`(要求每 2 个 vblank 翻一帧)。
- 只有 `BbFrameRate::Unlocked()` 为真时 `flip_rate` 才被强制归零,而它只在 AOT hook 装成功时为真——日志:`patch variants off (0); hook slots off (0 sites)`、`pacer's mode (0x203493d) is not the expected code`,**hook 全部失效**。
- 所以 `flip_rate=1` 生效,在 60Hz vblank 下 `count%2` = 每 33.3ms 才翻一帧 = **锁死 30fps**(日志 `Frame pacing: median 34.84ms` 完全吻合)。

这就是"模拟器稳 60、原生只有 20 多"的答案:不是算力,是呈现被 flip_rate 节流,GPU 白闲 85%。

## v3.6 修复

1. **解除 30fps 锁**:`BB_VBLANK_HZ` 默认 60→**120**。vblank 周期从 16.7ms 降到 8.3ms,`count%2` 变成每 16.7ms 一帧 = **60fps**。纯 dex、零风险,不动同步路径。面板可选 60/120/240/0。
2. **死锁修复**:无条件移除 `BB_SUBMIT_LOCK`,`BB_SUBMIT_AHEAD` 恢复保守默认 2。
3. **分辨率真正生效**:日志 `Output size 1920x1080 (default, BB_SCREEN_W/BB_SCREEN_H unset)`——面板的「输出分辨率」只写了 `BB_OUTPUT_RES`(最终帧/UI),而呈现 swapchain 尺寸由 `BB_SCREEN_W/H` 决定,两者从没联动。v3.6 让选输出分辨率时同时写入 `BB_SCREEN_W/H`,这次能真正看到效果。

## 覆盖安装

- 签名与 v1.1~v3.5 完全一致(debug key, SHA-256 `04908028...e5e18`)。
- native 库 / boot-linked / AOT / Turnip 驱动与 v3.5 逐字节一致,仅替换 classes.dex。
- 直接覆盖即可,无需卸载、无需清数据。

## 验证与回退

装进去跑一下,看新 `diva-boot.log`:
- `VideoOut: vblank` 应为 `120 Hz`;
- `Frame pacing: median` 应从 ~34ms 降到 ~17ms;
- `Frame stats` FPS 应从 ~28 升到 ~50-60。

若出现花屏/异常,面板把 VBlank 调回 60、输出分辨率调回「自动」即可回退。若帧率仍低,把新日志发来——现在有 `Frame stats:` + `Frame pacing:` 硬数据,可继续精确定位。
