# DIVA-Launcher v8.0-PerfTrim —— 性能定制版

基于 v7.0，**只替换了 `libbbgpu.so`**（图形引擎），dex / manifest / 资源 / 游戏数据全部字节级不变。签名与 v6/v7 一致，可直接覆盖安装。

## 这次优化的依据

来自实机日志 `diva-boot.log` 的铁证（v7 运行，重场景）：

- `Texture quality: quarter` —— 纹理质量已降到 1/4，游戏最大的几级 mipmap 被抛弃（`skip_levels > 0`）
- `Stall: ... 41.8 MB image uploads` —— 但每帧仍上传约 41.8 MB 图像数据
- `ms in: image refresh 2.4~4.3` —— 图像刷新是 CPU 侧最重的一项（其余 protect/resident/staging/host-copy 均 <0.5ms）

**矛盾点**：既然纹理质量已经抛弃了大 mip，detiling 阶段也跳过了它们，但 CPU 在给 GPU 做 staging 快照时，仍把这批"已经用不到的字节"整个拷贝了一遍。这就是可省的净开销。

## 改了什么

1. **跳过无用 mip 的 staging 拷贝**（`BB_MIP_UPLOAD_TRIM`，默认开）
   - 当 CPU 需要快照 guest 内存做纹理上传时，直接从第一级有效 mip 开始拷贝，跳过大 mip 对应的前缀字节。
   - detiling shader 的输入偏移与 descriptor range 同步调整，保证采样的仍是正确数据。
   - 只对"CPU 快照"路径生效；GPU 直接读 guest 内存的原路径（BB_GUEST_IN_PLACE）行为完全不变。
   - 想关闭：`BB_MIP_UPLOAD_TRIM=0`。

2. **收敛慢帧诊断输出**（`BB_STALL_VERBOSE`，默认关）
   - 旧逻辑：只要某帧 >40ms 就往 stdout 打约 10 行诊断。在 21fps 的重场景里几乎**每帧**都触发，Java 层逐行读取写文件——这本身就是持续 I/O 开销。
   - 新逻辑：同样的诊断**总共只采样前 3 次慢帧**；需要全部则设 `BB_STALL_VERBOSE=1`。
   - 这不影响游戏逻辑，只减少无谓的日志 I/O。

## 怎么验证收益

日志里新增两行，跑一会儿在 `diva-boot.log` 搜索：

- `Texture staging: skipped XX.X MB of unused mip uploads (YY.YY MB/frame)` —— 确认跳过了多少字节。若这行**不出现**，说明当前场景没有可跳的 mip（不是降档纹理），优化对该场景自然无效。
- `Stall diagnostics: A of B slow frames sampled` —— 确认慢帧日志已收敛。

## 关于"卡"的诚实说明

这次省的是**确定存在的冗余拷贝**（不用到的 mip 字节 + 高频日志 I/O），属于低风险改进，但**不承诺帧率数字**：

- 该场景主瓶颈是每帧 41.8 MB 纹理上传 + GPU 命令等待，不是单一开关能解决的。
- 若你的场景纹理质量是 `full`（未降档），则第 1 项优化不生效——可在设置里把纹理质量调到 `quarter`/`half` 再试。
- 更直接的提帧手段仍是降低内部分辨率。

## 关于"没声音"

v8 是在 v7 基础上只换图形 so，**v7 的 AudioBridge 音频修复仍然保留**。声音问题的最终定位仍需要那份 `diva-audio-diag.txt`（Java 端 AudioTrack 状态），它不在标准抓日志的 3 个文件里。装 v8 玩一会儿后，请单独把手机内部存储的 `diva-audio-diag.txt` 发来，一段就能定位断点。

---

- APK：`DIVA-Launcher-v8.0-PerfTrim.apk`
- SHA256：`598b9f3e5b011c3bdbe808ff00ff514c7404c4e0ec6769c8b5e0d0f2d9b9ba3e`
- 基底：v7.0-AudioFix → 仅替换 `libbbgpu.so`
