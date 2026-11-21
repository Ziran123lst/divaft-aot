# DIVA/FTDX (CUSA08053) PS4 → Android 原生移植 · 工程接续总入口

> 本文件是**接手 AI / 工程师的第一站**。它说明这个仓库里有什么、各部分如何协作、当前精确停点、以及下一步该做什么。读完本文再按链接深入。
> 建立时间：2026-11-21。项目原始移交文档最后更新：2026-11-20。

---

## 0. 一句话现状

基础设施（CPU 全量 AOT、GPU Vulkan、音频、呈现、输入）**已全部打通并取证**；游戏能 boot 到 `Output resolution 1920x1080 (live)` 并 SHOW 过若干帧，但**尚未「完全正常启动」（持续画面 + 声音）**。最后阻塞是一条崩溃/死锁链：**渲染上下文初始化缺陷 + libc 堆完整性检查 abort**。旁路只能治标，治本需要正确的渲染上下文/堆初始化。**不谎报进度——修到完全正常启动才算完成。**

## 1. 这个仓库里有什么（重要：两套代码的关系）

本仓库 `divaft-aot` 是为此移植整合的**自包含工程快照**，包含两大块：

| 路径 | 来源 | 作用 |
|---|---|---|
| **仓库根目录**（`src/`, `gpu/`, `cpu/`, `launcher/`, `build.sh`, `run.sh` …） | Bloodborne-Android 引擎（`ducvd89/Bloodborne-Android`，已含本地 DIVA 适配改动） | **运行时引擎**：guest CPU、HLE runtime（`src/runtime*.c`）、GPU（shadPS4 + Vulkan/AHB swapchain）、Android 壳。**真正被编译成 `bb-probe` / `libbbcpu.so` / `libbbgpu.so` 的代码在这里。** |
| **`divaft-port/`** | 原私有仓库 `Ziran123lst/divaft_Android` 的完整工程文件 | **移植工程层**：构建脚本、DIVA 适配补丁、移交文档、contract 表、HLE 接线、launcher Java、shader 工具、CI、测试。 |

两套代码的关系：`divaft-port/` 里的脚本和补丁**作用于**根目录的引擎源码。典型工作流见 `divaft-port/aot/patches/apply_diva_patches.sh`（把 DIVA 适配补丁打进引擎的 `src/*.c`）和 `divaft-port/aot/scripts/`（构建配方）。

- `divaft-port/` 下的 `third_party/fex`、`third_party/shadps4` 是外部依赖的 gitlink 占位（本仓库未嵌套子模块）。拉取见 `divaft-port/scripts/fetch-fex.sh` / `fetch-shadps4.sh`，或 `git clone` 对应上游后放到该路径。

## 2. 必读文档（按此顺序）

1. **`divaft-port/aot/docs/FTDX-移交文档.md`** — 最权威、自包含的完整移交文档：目标、架构、已验证成果、当前精确停点、关键地址/符号、构建配方、环境坑、给接手 AI 的优先级行动。**这是核心，务必先读。**
2. `divaft-port/README.md` — divaft_Android 工程自身概览与架构图。
3. `divaft-port/docs/architecture.md` / `nativeness.md` / `diva-analysis.md` — 架构、原生策略、DIVA 逆向分析。
4. `divaft-port/aot/docs/next-input-audio.md` — 下一步（输入/音频）方向。

## 3. 目标与约束

- 游戏：《初音未来 歌姬计划 Future Tone DX》PS4，TitleID `CUSA08053`。
- 目标设备：一加 Ace 3V（PJF110），骁龙 7+ Gen 3，Adreno 732（a7xx），Vulkan 1.3，Android 16。
- 路线：**AOT 静态重编译**（bb-recomp 工具 + Bloodborne 运行时适配），零运行时 JIT。libc.prx / libSceFios2.prx 离线链接进镜像。
- 用户约束：
  - 游戏资产（~20GB farc）**不上传云端**，留用户手机/PC，运行时读取。
  - **修到完全正常启动才汇报**，不做中间「完成」汇报，不谎报。
  - 二进制交付一律改名 `*.txt`，用户下载后去后缀。
  - 每次修正提交 GitHub。

## 4. 当前精确停点（崩溃链）

1. **渲染上下文 NULL/垃圾/未映射**：renderer_worker 调参数设置器（guest `0x48c2e0` / `0x48c250` 等族）。分发器 `0x59d668` 取 `[rbx+0x28]`（渲染任务对象里的「谱面/参数上下文」指针）——**初始化期未被正确赋值**。
2. **libc 堆完整性 abort**：`[SceLibc] A heap error is detected.` → guest `0xc72d7d4`（libc.prx+0x5d7d4）。
3. **启动期 cond/信号量循环死锁**（breaker 关时）。

`probe.c` 的 MAPERR-only 旁路已把 heap error 降到 0，但属治标。**已测试且无效的假设、详细地址表、教训见移交文档 §4–5，不要重复踩坑。**

## 5. 下一步优先级（详见移交文档 §10）

1. **治本渲染上下文**：逆向渲染任务对象构造工厂，找 `[rbx+0x28]` 赋值点，让 ctx 真实初始化。
2. **堆 HLE**：确认 guest 堆映射与元数据写正确，从根上消除 heap error。
3. **cond/调度语义**：若仍有死锁，补 PS4 优先级继承/调度。
4. **验证**：真机全量数据复测（可行目标）；或上传 farc 到云侧后 qemu 截屏见非黑帧 + 持续音频。
5. **稳定构建环境**：重编 libbbgpu 依赖树（LLVM23.1.3 + Boost1.92 等），是完成 1–3 的前提。

## 6. 不在本仓库、但工程需要的外部资产（重要）

本仓库**只含源码与脚本**。以下二进制/资产体量过大或涉游戏版权，**不在 git 里**，接手时需从持久区或上游获取：

- **持久区**（原环境路径）：`/root/userdata/workspace/divaft-persist/`
  - `diva-aot/`：`libbbaot.so`、`libbbaot-glibc.so`、`libbbaot.json`、`boot-linked.bin`（211,904,412 B，AOT 镜像）、`content.bin`
  - `bb-runtime/`：`bb-probe`、`libbbcpu.so`、`libbbgpu.so`（147 MB bionic）、`libc++_shared.so`
  - `bb-runtime-glibc/`：`libbbgpu.so`（100 MB）等
  - 工具链包：`LLVM-23.1.3-Linux-X64.tar.zst`、`ndk-r30-linux.zip`、`shadps4-src-cache.tar.gz`、`turnip-pack/`
  - 游戏数据 db：`game-rom-db/{game_db.txt,pv_db.txt}`；交付物：`libdivadiag.so`、`DIVA-Launcher.apk`
- **持久源码**：`/root/userdata/workspace/divaft-src/`（probe.c / runtime_*.c 的工作副本，与本仓库根目录 `src/` 对应）
- **qemu 一键恢复脚本**：`/root/userdata/workspace/divaft-qemu/resume.sh`（重建环境并跑）
- **bb-recomp 工具**：上游 `github.com/ducvd89/Bloodborne-android-rebuild-tool`（AppImage 二进制，v1.3.0 ABI2）
- **上游参考**：`ducvd89/Bloodborne-Android`（运行时基线）、`shadps4-emu/shadPS4`（HLE 参考）、`FEX-Emu/FEX`

> ⚠️ 原环境约每 15–40 分钟重置 `/root`（持久区 `/root/userdata/workspace/...` 除外）。若接手环境已无这些路径，需按移交文档 §7 构建配方 + resume.sh 重建，或从上游重新拉取依赖。

## 7. 仓库历史与整理说明（透明记录）

- 本仓库由 `bb-android`（Bloodborne 引擎）改造而来：commit `c562ba7` 实现 libSceJpegDec HLE + NID 归一化；commit `0d4436b` 合入全部工作区改动，并把 `fsr-vulkan` 从子模块转为普通目录以**保留其内部的 FSR3/FSR4 framegen 本地改动**（否则 gitlink 会丢失这些改动）。
- commit `divaft-port` 内容：整批复制自原私有仓库 `divaft_Android`，作为工程层纳入本仓库，使其自包含。
- 为修复 shallow 推送失败，本仓库历史已 `unshallow`（含上游完整历史，536 提交）。

---

**现在就开始：先读 `divaft-port/aot/docs/FTDX-移交文档.md`。**
