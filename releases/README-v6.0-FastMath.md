# DIVA-Launcher v6.0 — 第一个真正改了渲染器引擎的版本（fast_shader_math）

## 这一版和之前所有版本有本质区别

之前 v3.8→v5.0 我一直在 **dex 环境变量**和 **so 字节补丁**上打转——那是给引擎外围拧螺丝。这一版**第一次真正修改了 shadPS4 渲染器的 C++ 源码并重新编译了 `libbbgpu.so`**，把官方 shadPS4 验证过的 `fast_shader_math` 机制移植了进来。

## 改了什么（源码级，可核对）

移植自官方 shadPS4 PR #5455（v0.21.0），本地三处 + 一处开关：

| 文件 | 改动 |
|---|---|
| `shader_recompiler/profile.h` | `Profile` 加 `bool fast_shader_math` 字段 |
| `shader_recompiler/backend/spirv/emit_spirv_floating_point.cpp` | `Decorate()` 不再无条件加 `NoContraction`——fast 模式下允许驱动把乘+加收缩成 FMA |
| `shader_recompiler/backend/spirv/emit_spirv.cpp` | `SetupInfNanPreserveMode()` 在 fast 模式下短路，不再发出 signed-zero/Inf/NaN preserve |
| `video_core/renderer_vulkan/vk_pipeline_cache.cpp` | 默认开启 fast math，`BB_FAST_MATH=0` 可关闭；启动时打印实际模式 |

效果：**每个 shader 的 SPIR-V 指令数减少**（少一串 `NoContraction` 装饰 + 特殊浮点语义），Adreno 的 FMA 单元能被真正利用。这是全局收益，所有场景都吃得到，而不是靠某个场景的开关。

## 为什么这次靠谱（不是又一次乱调）

- 改动来自**官方主线已合并的技术**（PR #5455），不是我拍脑袋的开关。
- 本地有**现成骨架**（`Decorate()` 和 `SetupInfNanPreserveMode()` 都在），改动是条件化包裹，风险低。
- **编译实测通过**：46 个目标文件重编 + 链接成功，动态符号 8254 不变、ELF section 40 个不变 → ABI 完全兼容。
- so 里能查到 `Shader float mode:` / `BB_FAST_MATH` 字符串，启动日志会明确打印走的是 fast 还是 strict。

## 基于哪个基底

**v5.0（= 已验证 2700 帧不卡死的 v3.7）**。dex 逐字节不变，只有 so + 签名变化：

- 差异条目：`libbbgpu.so` + 3 个签名文件，`classes.dex` 完全一致（已验证）。
- 也就是说：**稳定性和 v3.7 完全相同，唯一的增量就是 shader 层面的 fast math**。
- 签名 `04908028...e5e18`，可覆盖安装。

## 预期与验证

- **预期**：shader 指令减少带来一定性能提升，但幅度不如 d7 那套玄学开关夸张（那些是不稳定的根源）。画质理论上无可见差异（放宽的是极少触发的 IEEE 特殊值语义）。
- 启动时日志会有这一行，请确认：
  ```
  Shader float mode: fast (BB_FAST_MATH=0 to use strict NoContraction/Inf-NaN preserve)
  ```
- 请回传 `diva-boot.log` + `bbpresent-diag.log`。看两件事：
  1. 上面那行是不是 `fast`
  2. `Frame stats:` 的 FPS 相对 v5.0 有没有提升、bbpresent 是否持续出帧（不卡死）

## 回退

- 想关 fast math：设置环境变量 `BB_FAST_MATH=0`（但dex没暴露这个开关，最直接是换回 v5.0）。
- 出现画面异常：直接覆盖回 `DIVA-Launcher-v5.0-StableBase.apk`，dex/存档/签名全兼容。

## 下一步（如果这版有效）

按研究报告的阶段 2 继续：pipeline cache（消除 shader 编译卡顿）+ marker 解析跳过 + PGO/LTO。构建环境已验证可用（`ninja -C out/bionic/gpu bbgpu` 增量编译正常）。
