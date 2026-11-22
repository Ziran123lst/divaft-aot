# v3.2 — 纹理带宽 + 各向异性 + 内置 FSR3 帧生成（原生 AOT）

继续深挖 bb native 的 358 个开关后，加入三个对 GPU-bound 场景真正有效的杠杆。仅重编 classes.dex，native/AOT/签名不变，可覆盖安装。

## 为什么这些有效（源码依据）
- 文档明确："GPU spends most of its time on geometry/texture bandwidth"。降纹理带宽往往比降分辨率更有效。
- `texture_cache.cpp:634` BB_TEXTURE_QUALITY: full/half/quarter 跳过 0/1/2 级 mip。8GB 设备 auto 默认已是 half；quarter 再省一半纹理带宽。
- `vk_rasterizer.cpp:3794` BB_ANISO 默认强制 16x 各向异性；降到 4x/关闭可省采样带宽。
- `vk_temporal_upscaler.cpp:2450` BB_FRAME_GEN=1 + upscaler=fsr3 启用内置 FSR 3.1 帧生成（纯 Vulkan shader，全套 FFX API 已编进 libbbgpu.so，无需外部 Lossless.dll/MAKO 模型），画面可翻倍流畅。

## 新增设置（选择即自动保存）
1. **纹理质量**：四分之一1/4（默认，卡顿优选）/ 一半1/2 / 自动 / 完整full。
2. **各向异性过滤**：关闭 / 2x / 4x（默认）/ 8x / 16x（原默认）。
3. **FSR3 帧生成**：默认关（音游对延迟敏感，开了会增加约1帧延迟）。勾选时若升采样不是 FSR3 会自动切换；若分辨率是原生档会自动降到 960×540（帧生成只对 scaled preset 生效）。

## 已核实不能开（实测更慢，未加入）
- BB_ASYNC_FENCES=1（79 vs 83 FPS）、BB_PACE_CATCHUP=1（30帧抖动）——源码注释明确实测变慢，保持默认关。
- LSFG（Lossless Scaling）需要外部 DLL/模型，APK 未内置，不走这条路；用内置 FSR3 帧生成替代。

## 不变项
签名一致（serial c2d1dead5839fd99，证书 SHA-256 04908028…）；libbbprobe/libbbgpu/libbbcpu/libbbaot/boot-linked/资源全部与 v3.1 逐字节一致；仅替换 classes.dex（6fd35b43…）。

sha256: df4909020c93d38eeb14218c5ed9224b0582d3b6541812b83602f32037fa4b11
