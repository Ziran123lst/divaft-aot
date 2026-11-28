# DIVA-Launcher v10-Diag —— 分辨率诊断版

## 为什么出这一版

连续 v8/v9/v9.1 都没能让性能改善，而我手上**唯一的日志是 11-22 上传的**，那时跑的还是旧 dex。我不能继续在无数据状态下盲改 so。

我在旧日志里看到一个疑点，但**无法确认它是否还存在于当前版本**：

- `Output size 1920x1080`、全程 1080p 渲染
- 日志里没有任何 `Upscaler:` / `FSR` / `GSR` / `Scene resolution` 字样
- 帧率稳定在 20-30 FPS

如果当前版本真的没有启用内部降分辨率，那么渲染的像素量是 960×540 的 **4 倍**，这才是卡顿的主因——我在 so 里抠 mip 拷贝、draw 预准备，都是在给满分辨率渲染省边角，收效自然小。

但你告诉我设置页显示的是 **GSR2/FSR3 + 960×540**。这说明设置页显示的值和游戏进程实际拿到的值**可能对不上**。这一版就是为了查清这件事。

## 这一版做了什么

在 `libbbgpu.so` 里加了一行诊断（每 5 秒输出到 `diva-boot.log`）：

```
Render scale: upscaler=gsr2 preset=3 scene=960x540 output=1920x1080 (...)
```

- `upscaler=` 实际生效的升采样器
- `preset=` 实际缩放档
- `scene=` **游戏真正渲染的内部分辨率**（这是关键）

其它什么都不改：dex、manifest、资源、游戏数据与 v9.1 逐字节一致；v9 的全部性能优化和 v9.1 的符号补丁都在。签名一致可覆盖安装。

## 需要你做一次（最后一次）

1. 卸载旧版后安装本 APK（避免旧 so 残留）：
   ```
   adb uninstall com.divaft.launcher
   adb install -r DIVA-Launcher-v10-Diag.apk
   ```
2. 进游戏玩 1-2 分钟，让日志跑出 `Frame stats`
3. 把你抓的日志包发我（含 `diva-boot.log`）

我看到 `Render scale:` 那行就能一锤定音：

- 若 `scene=1920x1080` 或 `upscaler=none` → 降分辨率没生效，我针对性修复（这是能大幅提帧的方向）
- 若 `scene=960x540` → 缩放正常，卡顿另有原因，我按真实数据继续

**这行诊断能避免我们再互相猜。** 拜托这一次。

---

- APK：`DIVA-Launcher-v10-Diag.apk`
- SHA256：`4cd1754881c97d235de17711ab438d74b8bab90ac61b35a64502e8ff247fbaf2`
