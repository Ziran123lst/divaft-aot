# v3.0 — 启动器性能设置 + 实时 FPS 显示（原生 AOT，非模拟器）

修复存档后游戏能进入但很卡。根因：旧启动器把 `BB_UPSCALER=none`（无效值→等同 off）且以原生 1920×1080 全分辨率渲染，而 DIVA 是 GPU-bound。bb 原生的全部性能开关都在 libbbgpu/libbbprobe 里现成实现、通过环境变量控制，因此本版**只重编启动器 classes.dex**，native 库 / AOT / 游戏数据 / 签名全部不变，可覆盖安装。

## 启动器新增「性能设置」（选择即自动保存，下次启动保留）
- **画面分辨率**（内部渲染尺寸 VideoOut + 渲染 patch 双设）：原生 1920×1080 / 1280×720 / **960×540（默认，推荐）** / 854×480。降分辨率是 GPU-bound 场景下最有效的提速手段。
- **画面放大 / 升采样** `BB_UPSCALER`：关闭 / FSR 3.1 / **GSR 2（默认，高通 Adreno 推荐）**。降分辨率后由升采样补回清晰度。
- **着色器异步编译** `BB_ASYNC_PIPELINES` + `BB_ASYNC_PIPELINE_THREADS`：开启·自动（默认）/ 2 线程 / 4 线程 / 关闭。避免首次进入场景时因编译管线卡顿。
- **帧率上限** `BB_FPS_LIMIT`：不限制（默认）/ 30 / 45 / 60。
- **游戏内显示 FPS**：默认开。两处同时生效：
  1. 启动器自绘的悬浮 HUD（基于 libbbpresent 呈现帧计数差分，纯 Java，触摸穿透手柄，左上角实时帧数）；
  2. 打开 native ImGui 的 FPS 计数器（`bbport.ini` 的 `show_fps=1`，会保留 ini 里的其它设置）。

## 不变项
签名一致（serial c2d1dead5839fd99，证书 SHA-256 04908028…）；libbbprobe.so / libbbgpu.so / libbbcpu.so / libbbaot.so / boot-linked.bin / 资源全部与 v2.1 逐字节一致；仅替换 classes.dex。

sha256: f0cea69ac16d59cd843187f1a7e0fc6b5a0c33fa38c7d970e3591950dabb1a8d
