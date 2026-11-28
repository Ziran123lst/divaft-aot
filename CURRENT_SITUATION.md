# 当前处境（Current Situation）

> 记录时间：2026-11-22。本文如实记录 DIVA/FTDX Android 移植工程的**真实现状**：能玩，但卡顿和无声仍未解决。不谎报，不把未验证的猜测当根因。

---

## 一句话现状

**能正常进入并游玩游戏，但帧率偏低、且没有声音。** 黑屏/卡死问题已解决；性能与音频是当前两大未解问题。

用户（项目所有者）的最新反馈原文：**"现在能正常游戏，但是还是很卡和没有声音"**。

---

## 项目是什么

把 PS4 游戏 **《初音未来 Project DIVA Future Tone DX》(CUSA08053)** 移植到 Android（ARM64，主要面向骁龙/Adreno 设备）。**这是原生移植，不是模拟器**：游戏代码经 AOT 预先编译成 ARM64 native code（`libbbaot.so`），GPU 命令和着色器由 shadPS4 渲染后端翻译成 Vulkan。需要用户自备正版游戏文件。

本仓库分两层：

| 层 | 路径 | 作用 |
|---|---|---|
| 引擎层 | 根目录 `src/ gpu/ cpu/ launcher/` | fork 自 Bloodborne-Android 的运行时引擎（shadPS4 渲染 + Bionic 运行时） |
| DIVA 移植层 | `divaft-port/` | DIVA 专属的构建脚本、补丁、AOT 流程、移交文档 |

各版本 APK 在 `releases/`，最新：**v7.0-AudioFix**。

---

## 版本演化与关键结论

### 阶段一：黑屏 / 卡死（已解决）

| 版本 | 尝试 | 结论 |
|---|---|---|
| v3.7 | native 强制 `flip_rate=0`（`BB_FORCE_FLIP0`），绕过失效的 AOT hook | ✅ **稳定基准**：跑到 2700+ 帧不卡死。约 28fps 起，随 3D 场景变重下降（用户确认是渲染负载，非 bug） |
| v3.8/v3.9/v4.0 | 复现 d7 的 58.7fps 配置（HonestLabels=false + d7 环境变量 + so byte patch） | ❌ 全部卡在 frame 5 黑屏。二分实验证明 **so byte patch 是黑屏主因** |
| v4.1-Safe | v3.7 无补丁 so + d7 环境变量 | ⚠️ 能到 54fps，但玩一会儿会中途卡死（frame 360 后 presenter 停帧） |
| v4.2 | 把 v4.1-Safe 转正 | ❌ 实测会中途卡 |
| v4.3 / v5.0 | 回到 HonestLabels=1、移除 GuestInPlace，逐字节回到 v3.7 | ✅ **v5.0 = v3.7 稳定基准**，慢但不卡 |

**定论**：黑屏由 so byte patch 引起；d7 环境变量虽然能提速但不稳定。**放弃在 dex 环境变量层逐项试探**。

### 阶段二：引擎级性能优化（进行中）

| 版本 | 尝试 | 结论 |
|---|---|---|
| v6.0 | 首次真正改 C++ 引擎源码：移植 shadPS4 官方 `fast_shader_math`，重编 `libbbgpu.so`（46 个目标文件 + 链接成功）。dex 与 v3.7 逐字节一致，可用 `BB_FAST_MATH=0` 回退 | ⚠️ 编译与验证通过，但对"卡"帮助有限——见下方瓶颈分析 |
| v7.0 | 修 Java `AudioBridge`：write 负值不再直接抛异常退出线程、显式 setVolume、加诊断文件 `diva-audio-diag.txt`。只重编 dex 里 `AudioBridge` 一个类，so 逐字节不变 | ⚠️ 用户反馈：**仍旧没声音，性能仍旧差** |

---

## 当前两大未解问题

### 1. 卡顿（性能）

最新日志（v6/v7 的 so，`Shader float mode: fast`）实测约 **21–28 FPS**，跑到约 1592 帧正常退出，未卡死。

Frame stats 铁证：

| 指标 | 数值 | 含义 |
|---|---|---|
| GPU idle | 81–86% | **GPU 大部分时间在闲着**，不是图形算力瓶颈 |
| host copies | 0%（本次场景）| 之前的场景是 32.1% blocked，瓶颈会随场景变化 |
| draw calls | 147/frame | 场景较复杂（不同于之前 54fps 的简单场景 30/frame） |
| Draw preparation | 6218 prepared used / 9142 not | 约 60% 的 draw 没命中预准备，每帧重复准备 |
| Draw pipe | 4817 draws/s | two-stage draw pipe 已开启 |
| protect/write faults | 111/s（66 revoking）、21/s | CPU 侧内存保护/页表操作开销 |

**结论**：瓶颈在 **CPU 侧的 draw 提交与内存管理**，不是 shader 算力。所以 v6.0 的 fast math（`0 shader compiles`）解决不了这个瓶颈。

**仍未验证的方向**（不做未经验证的承诺）：
- 提高 draw preparation 命中率（`BB_PREP_WORKERS` 当前 4，最大 8）
- 降低内部渲染分辨率（设置页有 960×540 / 640×360 预设，但日志显示 `reduced-size draws 0/frame`，实际缩放路径是否生效存疑，需进一步确认）
- 官方成熟手段：pipeline cache、submit scheduling、PGO/LTO（见 `releases/PS4AOT性能优化研究报告.md`）

> 注意：21–28fps 与 v3.7 基准（28fps 起随场景下降）基本一致。这个复杂 3D 场景下的帧率可能接近当前架构的真实水平，不能简单当作 bug。

### 2. 没有声音

已确认的事实：

| 环节 | 状态 | 依据 |
|---|---|---|
| guest 音频产出 | ✅ 正常 | 旧日志 `Audio stats ... 469 buffers/5s（期望 468）, starved 0` |
| native 写 FIFO | ✅ 正常 | `Runtime: audio backend fifo (s16 stereo 48 kHz)`，3 个 audio port 打开 |
| **FIFO → Java AudioTrack** | ❓ **盲区** | native 日志的 `starved/queued` 统计的是 guest→mixer 的 ring，**不反映 mixer→FIFO→Java 这一段** |

**关键盲点**：Java AudioTrack 的错误只写进 Android logcat，**从来不进 diva-boot.log**。所以现有日志无法证明 Java 到底有没有在读 FIFO、有没有真正输出声音。v7.0 加的诊断写到了独立文件 `diva-audio-diag.txt`，但用户的日志抓取流程只抓固定 3 个文件（`diva-boot.log` / `bbpresent-diag.log` / `bbport.ini`），**诊断文件没被抓到**，因此 v7.0 的效果仍未知。

**下一版计划**：把音频诊断做进 native 日志（直接进 `diva-boot.log`，标准抓取流程能覆盖），让 `state/head/read/written/writeErrors` 一锤定音定位断点，而不是继续让用户当测试员反复试探。

---

## 用户反馈与沟通教训

- 用户明确批评过"乱推测、一直改 dex 环境变量让他当测试员"。**后续不得把未经验证的猜测包装成根因**。
- 用户明确指出：3D 场景掉帧是渲染负载导致，**不应误说成 bug**。
- 用户最新语气明显不耐烦（"gghhhhh.zip"、"沒有聲音，性能還是很差"）。应避免反复要求抓日志当测试员。

技术路线已定型：**以 v5.0 稳定 dex 为基底，只改引擎 C++ 并重编 so**；不再回到 dex 环境变量逐项试探的老路。

---

## bbrecomp 清理记录

本次按要求清理了仓库里对 DIVA 无用的 bbrecomp（Bloodborne 代码重建工具）内容：

- 删除 `tools/android/fetch_recomp_android.sh`（下载脚本）
- 删除 `tools/android/recomp-manifest/`（CUSA03173 重建清单）
- 删除 `docs/RECOMP_ABI.md`（重建 ABI 设计文档）
- 移除 `.gitmodules` 中的 `tools/recomp` submodule 条目
- 移除 `tools/android/build_frontend.sh` 中的 recomp 构建段落

**保留项及原因**：
- `tools/android/frontend/src/com/ducvd89/bloodborne/` 下的 `AotBuild*.java`、`NativeCpu*.java`、`RebuildDialog.java`、`RecompAssets.java`、`NativeRequiredView.java`：这些是 Bloodborne launcher 的 UI 类，与 `MainActivity` 有几十处深度耦合，强删会破坏 Bloodborne 前端编译链；且 DIVA 使用独立 launcher（`divaft_Android/aot/launcher/`），这些类对 DIVA 无影响。
- `README.engine.md`、`docs/CHANGES_2.1.0.md` 等：引擎历史文档，属历史事实，保留不篡改。

---

## 2026-11-28 追加：v8.0 性能优化（PerfTrim）

基于实机日志的确定性优化，只替换 `libbbgpu.so`（dex/manifest/资源/游戏数据全部字节级不变，签名一致可覆盖安装）：

1. **跳过无用 mip 的 staging 拷贝**（`BB_MIP_UPLOAD_TRIM`，默认开）：日志显示 `Texture quality: quarter` 已抛弃大 mip、detiling 也跳过了它们，但 CPU 做 staging 快照时仍把这些字节整个拷贝（每帧 41.8 MB 图像上传）。改为从第一级有效 mip 开始拷贝，detiling 输入偏移同步调整。只对 CPU 快照路径生效，GPU 原地读路径不变。
2. **收敛慢帧诊断输出**（`BB_STALL_VERBOSE`，默认关）：旧逻辑每帧 >40 ms 就打约 10 行到 stdout，21 fps 重场景几乎每帧触发，Java 逐行读造成持续 I/O。改为同场景只采样前 3 次慢帧。

**诚实的预期**：这是低风险的冗余消除，不承诺帧率数字。该场景主瓶颈是每帧 41.8 MB 纹理上传 + GPU 命令等待，非单一开关可解；若纹理质量为 `full` 则优化 1 不生效。是否真有收益需实机验证（当前沙箱无设备连接，未能实测）。

**未混入**：`runtime_services.c`/`runtime_savedata.c` 的存档对话框 UI 改动（之前会话遗留、未测试）本次不编入 v8 的 so；仅作为源码状态同步提交，保持仓库与工作区构建源一致。

验证钩子：日志新增 `Texture staging: skipped ... MB of unused mip uploads` 与 `Stall diagnostics: A of B slow frames sampled` 两行。

## 交付物索引

| 文件 | 说明 |
|---|---|
| `releases/DIVA-Launcher-v10-Diag.apk` | **最新 APK**（分辨率诊断版：打印实际 upscaler/preset/scene 分辨率；仅加诊断，需卸载重装并回传日志） |
| `releases/README-v10-Diag.md` | v10 诊断目的与操作 |
| `releases/DIVA-Launcher-v9.1-LinkFix.apk` | v9.1 + `__hash_memory` 链接符号补丁 |
| `releases/README-v9.1-LinkFix.md` | v9.1 链接错误修复说明 |
| `releases/DIVA-Launcher-v9.0-AllOpt.apk` | 全优化合并版：nested-IB draw-prep + mip-trim + stall + fast-math |
| `releases/README-v9.0-AllOpt.md` | v9.0 全优化说明 |
| `releases/DIVA-Launcher-v8.0-PerfTrim.apk` | 性能优化尝试（mip-trim + stall），仅替换 libbbgpu.so |
| `releases/README-v8.0-PerfTrim.md` | v8.0 优化依据、内容与验证方式 |
| `releases/DIVA-Launcher-v7.0-AudioFix.apk` | 音频修复尝试，基于 v6.0 |
| `releases/DIVA-Launcher-v6.0-FastMath.apk` | fast shader math 性能版 |
| `releases/DIVA-Launcher-v5.0-StableBase.apk` | 稳定基准（=v3.7） |
| `releases/README-v6.0-FastMath.md` / `README-v7.0-AudioFix.md` | 各版本说明 |
| `releases/PS4AOT性能优化研究报告.md` | PS4 原生移植性能优化研究报告 |
| `PROJECT_HANDOFF.md` / `divaft-port/aot/docs/FTDX-移交文档.md` | 工程接续总入口与完整移交文档 |
| `docs/images/diva-*.jpg` | 实际运行截图（标题、游戏、教学） |
