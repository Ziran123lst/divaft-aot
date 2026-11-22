# DIVA-Launcher v4.0 — 对齐全 d7 实测环境（vblank 60Hz）

## 先说清楚：前两版我判断错了

- v3.8 去掉第三個補丁 → 黑屏。
- v3.9 把第三補丁補回去 → **還是黑屏**。

所以「補丁3 是死鎖元凶」這個判斷是錯的，我在 v3.9 已把三補丁全部補回。但黑屏依舊，說明根因**不在補丁**。

## 真正的差異：vblank 480Hz vs d7 的 60Hz

逐行對照你这次上传的日志（v3.9）与 d7 实测日志，找到一个**明确的、可证实的差异**：

| 项目 | d7（成功 58.7fps） | v3.9（黑屏） |
|---|---|---|
| `VideoOut: vblank` | **60 Hz** | **480 Hz** |
| 三个 native 补丁 | 全打 | 全打（v3.9 已对齐） |
| Frame stats | 有 | **完全没有** |

根因链条（源码证实）：

```cpp
// emulator_settings.h
u32 GetVblankFrequency() {
    const long hz = Number("BB_VBLANK_HZ", 60);
    return hz > 0 ? u32(hz) : 480u;   // 0 -> 480Hz uncapped
}
```

v3.8/v3.9 的 dex 在覆蓋塊**最後**寫 `BB_VBLANK_HZ=0` → native 解讀成 **480Hz uncapped**。而 DIVA 的 `Frame rate: hooks off`（pacer hook 装不上）在这种 uncapped 模式下无法推进 → guest 提交 5 帧后就不再 Flip → bbpresent 停在 frame 5 → **黑屏**。audio 线程和统计线程独立，所以音频/统计照常，这与日志完全一致。

而 d7 虽然 env 表也写 `BB_VBLANK_HZ=0`，但它的 dex 写入位置不同（加在 `BB_SUBMIT_LOCK remove` 之前），被后面的默认值逻辑覆盖回 **60Hz** —— 这正是 d7 日志显示 60Hz、能跑 58.7fps 的原因。我之前为了「确保最终生效」把覆蓋塊移到最后，反而制造了 480Hz 这个 d7 没有的状态。

## v4.0 修复

只改 dex 一处：`BB_VBLANK_HZ` 从 `"0"` 改为 `"60"`，精确对齐 d7 的 60Hz 实测环境。

- native so 保持 v3.9 的三个补丁（与 d7 完全一致）
- 其余 15 个环境变量、音频诊断全部不变
- 签名一致（`04908028...e5e18`），可直接覆盖

## 请回传

跑一下，发新的 `diva-boot.log` + `bbpresent-diag.log`。**关键就看一行**：

- `VideoOut: vblank 60 Hz` → 确认改对了
- bbpresent 是否持续出帧、是否出现 `Frame stats:` 且 FPS 往 50-60 走

**如果这次还是黑屏**：说明问题既不是补丁也不是 vblank，我需要你把 **d7 的完整 diva-boot.log** 发我（之前只给了数值摘要），我会逐行对比 d7 与当前日志的启动路径，定位到底哪一步分叉——不再靠猜单个设置。
