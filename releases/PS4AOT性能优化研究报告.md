# PS4 AOT 性能优化研究报告 —— 放弃 dex 调参，换一条真正有效的路

> 调研基准：官方 shadPS4（main @ 264d6b71）、Bloodborne-Android 上游（ducvd89）、本地这份 DIVA fork（CUSA08053 / 骁龙 7+ Gen 3 / Adreno 732）。
> 结论基于 GitHub Releases API、官方源码与 PR 原文、上游 README/docs，以及本地源码逐项核实。

---

## 〇、先说结论：之前的方向从根上就错了

过去 v3.8→v4.3 一共七八个版本，我做的事情本质是两件：

1. **在 dex（Java 启动器）里改 `BB_*` 环境变量开关**；
2. **给一个现成的 `libbbgpu.so` 打几个字节的二进制补丁**（HonestLabels / GpuDirty / SubmitLock）。

这两件事都发生在**渲染器外围**。而真正决定帧率和稳定性的机制——shadPS4 的 shader 编译器、提交调度、管线缓存、readback、纹理缓存架构——**本地这份 fork 里要么没有，要么是阉割版**。在一个缺了核心引擎的壳上反复调开关，等于给没有发动机的车调油门线，所以才会出现"改一个变量好一点、换个场景又黑屏/卡死"的打地鼠困局。

**这不是再调一个变量的问题，是要不要补齐渲染器本身的问题。**

---

## 一、外部研究：成熟项目到底靠什么提性能

### 1.1 官方 shadPS4 把血源从"能跑"优化到 60fps 的技术清单

先纠正几个流传的说法：所谓"某版本一键 30→60fps"并不存在。官方 release notes 里只有 v0.13.0 一句 "the long-awaited **Bloodborne speedup fix**"，实际是 v0.10→v0.21 一长串技术累积的结果。真正起作用的是这些（均已合并进官方主线）：

| 技术 | 版本/PR | 机制 | 实测收益 |
|---|---|---|---|
| **submit_draw_interval**（提前提交） | PR #5572 | 每 N 个 draw 调一次 `scheduler.Flush()`，让 GPU 不再等 CPU 翻译完整条超长命令列表才开工 | 等 GPU 时间 8.2ms→**1.1ms**，25→30fps（TLG） |
| **fast_shader_math** | #5455 / v0.21 | 关掉 `NoContraction`、放弃 Inf/NaN 精确语义，允许 FMA 收缩、更激进的浮点优化 | 减少 shader 指令数，全场景受益 |
| **pipeline cache** | #3816 / v0.13 | 把编译好的管线序列化到磁盘，冷启动预加载 | 消除首次运行的 shader 编译停车 |
| **marker 解析跳过** | #4078 | 关闭时跳过 guest/host marker 的解析调用 | 血源 GFX 命令 70% 是 NOP，解析耗时 7.3µs→5.5µs |
| **compute clear 优化** | #3795 / v0.13 | 全图像清屏走 compute | 血源大量清屏场景受益 |
| **调度器优先级队列** | #3848 / v0.13 | 用优先级队列替代专用 image 回读线程 | 降低回读同步延迟 |
| **Relaxed readback** | #4085 | 只在页写入时 flush、不设读保护 | 修血源"顶点爆炸"，性能远好于 Precise |
| **异步提交 + EOP fence** | 长期 | 提交线程独立，EOP 中断作为"guest 要读 GPU 数据"的锚点触发回读 | 提并行度、修正确性 |

官方随附的**推荐基线配置**（可直接照抄思路）：
```
GPU:  readbacksMode=1(Relaxed), directMemoryAccess=false, fastShaderMath=true,
      submit_draw_interval=50, fsrEnabled=true, 内部分辨率 960×540,
      rcasEnabled=true, vblankFrequency=60, presentMode=Fifo
Vulkan: pipelineCacheEnable=true, pipelineCacheArchive=true
General: logType=async
```

**官方明确不建议**：默认开 Precise readback（页保护 syscall 太贵）；未验证就开 DMA（NVIDIA 上曾 8→2fps 回归）；依赖未合并的 PR。

### 1.2 Bloodborne-Android 上游（本项目正是基于它）怎么在手机上做性能

这个项目的 README 和 docs 里写得很清楚，它跑血源靠的是一整套**移动专有**手段：

- **AOT 静态重编译**（`libbbaot.so`，游戏 x86-64 代码提前编译成 ARM64）——零运行时翻译、延迟标志位编译期消除、导入函数变直接调用。实测进程 CPU 325% vs JIT 376%，GPU-bound 下 FPS 持平但功耗更低。← **本项目已经用了这条路**。
- **Upscaler**：GSR2（高通专用）/ FSR3.1 / FSR4，preset 精确比例 = Quality 67% / Balanced 59% / Performance 50% / UltraPerformance 33%（1080p 输出下 Performance = 960×540）。降内部分辨率再超分，是移动端**投入产出比最高**的提帧手段。← **本项目已有 GSR2/FSR3/FSR4 全套 shader**。
- **Lossless Scaling 帧生成（LSFG 3.1）**：2× + performance 模式 + FP16，实测 4ms GPU + 0.2ms CPU。← 本项目有 `BB_FRAME_GEN`（FSR3 帧生成）开关。
- **统一内存（UMA）**：vertex/index 数据 guest 与 GPU 共享，省约 8% RAM。但要小心：v1 曾因 upfront commit 导致硬复位，v2 改用 8MiB granule + demotion/compact 才稳。
- **防卡死的硬约束**：纹理/内存映射逼近 65530 页上限会触发 `VK_ERROR_MEMORY_MAP_FAILED`，必须**主动**做 demote/compact/repack + 自适应纹理预算，不能等 OOM。← 本项目已有 `BbGcBudget` 自适应预算（见下）。

---

## 二、本地核实：这份 fork 缺什么、有什么

我把外部研究的每一项对着本地源码查了一遍（关键一实地 grep 确认）：

### 2.1 官方那套核心优化，本地**一个都没有**

| 官方机制 | 本地 grep 结果 | 含义 |
|---|---|---|
| fast_shader_math | **0 文件** | shader 编译器始终加 `NoContraction` + Inf/NaN preserve，没有任何放宽开关 |
| readbacks_mode | **0 文件** | 无 readback 模式切换 |
| submit_draw_interval | **0 文件** | 无按 draw 计数的提前提交 |
| pipeline_cache | **0 文件** | 无管线序列化/冷启动预加载 |
| direct_memory_access | **0 文件** | 无 DMA |

**这就是为什么改 dex 开关提不了速**：这些机制压根不在二进制里，dex 里也没有对应的 `BB_*` 开关可开。

### 2.2 但本地有它自己的一套东西（好消息）

这份 fork 不是原生 shadPS4，而是深度改造的 bbport，自带不少自研机制：

- **two-stage draw pipe**（`vk_draw_pipe.h` + `vk_rasterizer.cpp:819`）——**这就是官方 `submit_draw_interval` 的等价实现**！`BB_DRAW_PIPE`、`BB_HONEST_IDLE_FLUSH`、`BB_HONEST_IDLE_BACKLOG`、`BbStats::idle_flushes` 都在。这是本地最接近"真正提性能"的机制，但我之前在 dex 里把 `BB_DRAW_PIPE` 设成了 **0**（等于关掉了）。
- **GSR2 / FSR3 / FSR4 全套超分与帧生成 shader**（`gsr2_upscale.comp`、`fsr4_*.comp`、`fsr_pass.cpp`）。
- **PGO + LTO 支持**（`build.sh` 的 `BB_PGO` / `BB_LTO`）——但当前环境已无编译产物。
- **自适应纹理预算 `BbGcBudget`**（`bbport_gc_budget.cpp`）——按 `MemAvailable`/swap 自动降预算，这是防卡死的骨架。
- **大量 `BbToggle` 开关**（FetchShaderCache、TextureBindingMemo、DeferredUploads、ReadbackQueue、LockFreeUploadCheck 等几十个），默认大多开启——本地 fork 的"优化颗粒度"其实比官方还细。

### 2.3 卡死的真因（这次是有日志证据的）

v4.1-Safe 跑 360 帧后卡死，日志链条清楚：
- bbpresent 停在 frame 360，之后无新 Image sync / Frame stats，但 audio 继续 → guest 渲染线程停了，`poll(-1)` 无限等待；
- swap 85%、MemAvailable 1940MB、纹理预算一路被砍到 1396MB；
- 最后反复刷新同一张 1920×1088 大贴图（GpuDirty 热点）。
- 根源：`BB_HONEST_LABELS=0` + `BB_GUEST_IN_PLACE=1` 在高通 735 上被降级成 `GuestInPlace=0`，导致 `HonestLabels()=false`，走 WaitGpuIdle 不稳定路径。**v3.7 稳定跑 2700+ 帧，靠的正是 HonestLabels=true + GuestInPlace=false。**

---

## 三、新的技术路线（放弃 dex 打地鼠，按此推进）

### 原则
**先稳定（能一直玩），再提速；提速靠补齐/启用渲染器机制，不靠在 dex 里试变量。** 每一步都用 `Frame stats` / bbpresent 出帧数做量化 A/B。

### 阶段 0 —— 止血（立即可做，不重编译 so）
基于**已验证稳定 2700 帧的 v3.7 路径**，一次性把配置固定成稳定的那一套，别再让不稳定变量混进来：
- `BB_HONEST_LABELS=1`、`GuestInPlace` 关闭（v3.7 的稳定核心）；
- so 用**无补丁**版本（v4.1 已证明补丁是黑屏元凶）；
- 内部分辨率降到 **960×540（Performance）+ GSR2**（既提帧又大幅降内存——直接缓解卡死的 swap 85%）；
- 关闭一切"实验性"开关（`BB_DRAW_PIPE` 除非单独验证，否则保持稳定的默认）。

> 这一步的目标是**有一个永远不卡的版本作为基准线**，之后任何优化都在它上面做、可回滚。

### 阶段 1 —— 启用本地已有的真机制（低风险，dex/开关层）
稳定基线建立后，逐项打开本地**已经存在但被我关掉/没开**的机制，每项单独 A/B：
1. `BB_DRAW_PIPE=1`（官方 submit_draw_interval 的本地等价实现）——这是最可能真正提帧的一项；
2. `BB_HONEST_IDLE_FLUSH` / `BB_HONEST_IDLE_BACKLOG` 调优，让 GPU 少空转；
3. 确认 `BbToggle` 里 FetchShaderCache / DeferredUploads / LockFreeUploadCheck 全部开启；
4. 若稳定性 OK，试 `BB_FRAME_GEN`（FSR3 帧生成）——音游对延迟敏感，需实测可玩性。

### 阶段 2 —— 补齐缺失的引擎机制（需重编译 so，收益最大）
这是真正的"性能升级"，把官方那套移植进本地 fork：
1. **fast_shader_math**：本地 `emit_spirv.cpp:586` / `emit_spirv_floating_point.cpp:10` 有现成骨架，只需给 `Profile` 加一个 `fast_shader_math` 字段、设置加载时注入、在 `SetupInfNanPreserveMode` 和 `Decorate` 里加条件。**改动小、收益全局、最值得先做**。
2. **pipeline cache**：移植 #3816，把编译产物落盘 + 冷启动预加载，配 AOT 产物一起交付。
3. **marker 解析跳过**（#4078）+ **compute clear 优化**（#3795）——照 PR 改，改动可控。
4. **PGO/LTO**：用 `build.sh` 的 PGO 流程跑一遍游戏取 profile 再重编译，免费提升。

### 阶段 3 —— 建立测量框架（避免再打地鼠）
官方 Issue #4316 指出社区优化争论的根源是"各测各的、数据不可比"。应在 native 内建原子计数器：`Scheduler::Finish/Wait/Submit` 次数与耗时、回读字节数、`PageManager::Protect` syscall 次数、帧时间 p50/p95/p99。有了统一基线，每一版优化都能量化，不再靠"感觉快了/又卡了"。

---

## 四、一个必须面对的现实约束

**当前环境已经无法重编译 `libbbgpu.so`**：`out/gpu/build.ninja` 和构建产物已不在、`nix-shell` 不存在、系统无 boost。之前所有 APK 都是拿历史编译好的 so 改字节或只重编 dex。

这意味着：
- **阶段 0/1**（dex 与开关层）现在就能做、能立刻交付；
- **阶段 2**（真正的引擎级提速）**必须先重建构建环境**（按 `PROJECT_HANDOFF.md` §7 + `build.sh`：LLVM 23.1.3 + Boost 1.92 + NDK + Vulkan/SDL3 + nix），这是一次性投入，但做好之后所有后续优化都建立在能改 C++ 的基础上，不用再打字节补丁。

---

## 附：关键源码/PR 索引

| 主题 | 位置 |
|---|---|
| 官方所有设置默认值 | shadPS4 `src/core/emulator_settings.h:180-519` |
| submit_draw_interval | PR #5572 / `vk_rasterizer.cpp:396-407` |
| fast_shader_math | PR #5455 / `emit_spirv.cpp:595-620`、`emit_spirv_floating_point.cpp:9-16` |
| pipeline cache | PR #3816 / `vk_pipeline_serialization.cpp:305` |
| marker 跳过 | PR #4078 |
| 本地 two-stage draw pipe | `vk_draw_pipe.h`、`vk_rasterizer.cpp:819`、`BB_DRAW_PIPE` |
| 本地自适应纹理预算 | `gpu/shim/bbport_gc_budget.cpp`、`texture_cache.cpp:1379` |
| 本地 PGO/LTO | `build.sh:24-58`、`BB_PGO`/`BB_LTO` |
| 上游 upscaler 比例 | Bloodborne-Android `docs/upscaler.md`、`docs/ANDROID_LSFG.md` |
