# v3.1 — 性能设置修正版（真正的场景缩放路径）

深入研究 bb native 源码后修正 v3.0 的两处设置错误，并对齐真机（Adreno 735, 8GB）实测有效的路径。仅重编 classes.dex，native/AOT/签名不变，可覆盖安装。

## 为什么 v3.0 不够有效
- v3.0 用 `BB_RENDER_RES=960x540` 降分辨率，但 DIVA 的 boot-linked.bin 里 Bloodborne 那套分辨率 patch 地址全是 0（不匹配），`PatchSceneSize` 会失败；而且只要设了 BB_RENDER_RES 就会触发 `scaled_session`，**绕过**通用的 preset 缩放路径。对 DIVA 无效甚至有害。
- v3.0 设了 `BB_UPSCALER=gsr2` 却没设 preset，而 preset 默认是 NativeAA(1.0x) → 场景没有真正缩小，升采样器几乎空转。

## v3.1 修正 / 新增
1. **画面分辨率改用 `BB_UPSCALE_PRESET`**（DivA 全 GPU 通用的 reduced-target 路径）：`ForPreset` 用除数 {1,1.5,1.7,2,3} → 输出 1920×1080 下场景依次为 1080p/1280×720/1130×636/960×540/640×360。默认「性能 960×540」(preset=3)。
2. **升采样「关闭」档改用 `none`**：native TemporalUpscaler 只认字符串 "none" 为关闭，之前写 off 会让 enabled/Active 状态不一致。
3. **升采样关闭时强制 preset=0**：避免缩放空转误判；日志会提示「未缩放」。
4. 清除 v3.0 遗留的 BB_RENDER_RES / BB_INTERNAL_WIDTH/HEIGHT。
5. **新增呈现缓冲 `BB_PRESENT_BUFFERS`**（默认3，可选4/5/2）削掉掉帧抖动。
6. **新增 RCAS 锐化开关 `BB_RCAS`**（默认开，可关掉省 GPU）。
7. FPS 双显示保留（Java 悬浮 HUD + native ImGui show_fps）。

## 原理依据（native 源码）
- `vk_temporal_upscaler.cpp:248 Active()`：upscaler ∈ {fsr3, 外部(gsr2), taa} 且 `BB_UPSCALER != none` 才激活缩放。
- 同文件 `:210 !scaled_session` 分支：`scene_targets.SetSize(SceneResolution::ForPreset(preset, output))` 直接决定场景尺寸。
- `scene_resolution.h:13 ForPreset`：scale={1,1.5,1.7,2,3,4}，输出尺寸除以 scale 即场景分辨率。

## 不变项
签名一致（serial c2d1dead5839fd99）；libbbprobe/libbbgpu/libbbcpu/libbbaot/boot-linked/资源全部与 v3.0 逐字节一致；仅替换 classes.dex（a969fd82…）。

sha256: 32ed2d82337dd48d0f2f4bf0eab1e83d3bcb392e3011aaf180c1bd563462629a
