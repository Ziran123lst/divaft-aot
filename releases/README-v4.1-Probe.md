# DIVA-Launcher v4.1-Probe — 二分定位版 B（备用对照）

**先测 v4.1-Safe。** 只有 Safe 的结果需要进一步确认时，才测这一版。

| 版本 | so | dex |
|---|---|---|
| v4.1-Safe | 无补丁 | 新（d7 16 项） |
| **v4.1-Probe（本版）** | **有补丁（三补丁）** | **老（v3.7，无 d7 项）** |

判读：

- Safe 能玩 + Probe 黑屏 → 根因在 **so 补丁**
- Safe 黑屏 + Probe 能玩 → 根因在 **dex 环境变量**
- 两版都黑屏 → 两者叠加才触发，需更细的对照

签名一致，可覆盖。sha256: 515ce0ac79b6004063443bcfd4a5a369addb7fbb7db1c55ccbaeb607f38c6bfe
