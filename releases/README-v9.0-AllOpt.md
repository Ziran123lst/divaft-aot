# DIVA-Launcher v9.0-AllOpt —— 全优化合并版（当前最新）

基于 v7.0，**只替换 `libbbgpu.so`**（图形引擎），dex / manifest / 资源 / 游戏数据全部字节级不变。签名与 v6/v7/v8 一致，可直接覆盖安装。

这一版把**两个来源的性能优化合并编进了同一个 so**，是当前性能优化的集大成版本。

## 合并了哪些优化

### 1. draw-prep 嵌套 IB 重放（大头，来自 11-22 会话）
- 日志铁证：`Draw preparation: 6218 prepared pipelines used, 9142 not`——60% 的 draw 没命中预准备。
- 根因：DIVA 会提交**嵌套间接缓冲区（nested IndirectBuffer）**，而旧的 command walker 是平铺扫描的。GPU 线程实际会为嵌套 IB 递归执行 `ProcessGraphics`，其寄存器写入会改变后续校验和；平铺扫描没跟随，导致**第一个 IB 之后的所有 checksum 全部发散**，draw 无法命中预准备。
- 修复：按流顺序递归遍历嵌套 IB（深度上限 4、每顶层 buffer 有预算上限防跑飞）；扫描器从 SCHED_IDLE 改回正常优先级（它是所有 worker 的串行供料者，在低优先级下被游戏线程饿死）；默认 worker 从 4 提到 6。
- 开关：`BB_PREP_IB=0` 恢复平铺、`BB_SCANNER_IDLE=1` 恢复低优先级、`BB_PREP_WORKERS` 调 worker 数。
- miss 统计细分为 not-ready / unavailable / checksum / stale，并统计 IB 提交数。

### 2. 跳过无用 mip 的 staging 拷贝（v8.0 PerfTrim）
- 日志铁证：`Texture quality: quarter`（大 mip 已抛弃）却仍每帧上传 41.8 MB 图像；`image refresh 2.4~4.3ms` 是 CPU 侧最重项。
- 修复：CPU 快照从第一级有效 mip 开始拷贝，detiling 输入偏移同步调整。只对 CPU 快照路径生效，GPU 原地读路径不变。
- 开关：`BB_MIP_UPLOAD_TRIM=0` 关闭。

### 3. 收敛慢帧诊断输出（v8.0 PerfTrim）
- 旧逻辑每帧 >40ms 打约 10 行到 stdout，21fps 重场景几乎每帧触发，Java 逐行读造成持续 I/O。
- 改为同场景只采样前 3 次慢帧。开关：`BB_STALL_VERBOSE=1` 恢复全部。

### 4. fast shader math（v6.0 起，默认开）
- 放松 IEEE 浮点规则，让 Adreno 把 mul+add 融合成 FMA，减少 SPIR-V 指令数。
- 日志 `Shader float mode: fast` 即为开启态。开关：`BB_FAST_MATH=0` 回到严格模式。

## 验证钩子

日志中搜索：

- `Draw preparation: ... used, ... not` —— 观察命中率是否较之前（6218/9142）改善
- `Texture staging: skipped ... MB of unused mip uploads` —— 确认跳过的字节
- `Stall diagnostics: A of B slow frames sampled` —— 确认慢帧日志已收敛
- `Shader float mode: fast` —— 确认 fast math 生效

## 诚实的预期

- draw-prep 修复针对的是**已确认的根因**（嵌套 IB 导致校验和发散），是最有希望带来实际提升的一项；但**不承诺具体帧率数字**。
- 该场景整体仍受每帧纹理上传 + GPU 命令等待制约，属架构性开销。
- 若实测命中率/帧率无改善，请用上述开关逐项 A/B 定位，而不是盲目叠加。
- 沙箱无真机连接，**未能实测**，需实机验证。

## 声音

v9 是在 v7 基础上只换图形 so，**v7 的 AudioBridge 音频修复仍保留**。声音的彻底定位仍需 `diva-audio-diag.txt`（Java 端 AudioTrack 状态），它不在标准抓日志的 3 个文件里——请装后单独把手机内部存储的这个文件发来。

---

- APK：`DIVA-Launcher-v9.0-AllOpt.apk`
- SHA256：`de487c1f60f68269f600878cf18fca025ea696bfe350178d9d34a722c5dc293f`
- 基底：v7.0-AudioFix → 仅替换 `libbbgpu.so`（合并 nested-IB + mip-trim + stall + fast-math）
