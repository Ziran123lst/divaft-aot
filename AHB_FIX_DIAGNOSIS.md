# 真机 AHB swapchain 失败 · 诊断与修复记录

## 真机日志（用戶上傳「未來銀色.zip」）

`diva-boot.log` 关键行：

```
GPU: Turnip Adreno (TM) 735, 8481 MiB; turnip Mesa driver 26.3.1 (Mesa 26.3.1-lto), Vulkan 1.4.363
GPU: 2 Vulkan recording threads, segments 64 KiB
Present: connected to the app (protocol 3), 3 buffers 1920x1080
AHB swapchain [0]: RGBA8 color+sampled+overlay: getProperties failed (ErrorInvalidExternalHandle)
AHB swapchain [0]: RGBA8 color+sampled:        getProperties failed (ErrorInvalidExternalHandle)
AHB swapchain [0]: RGBA8 color:                getProperties failed (ErrorInvalidExternalHandle)
AHB swapchain [0]: RGBX8 color+sampled:        getProperties failed (ErrorInvalidExternalHandle)
GPU [Debug] <Critical> vk_swapchain_ahb.cpp:233 CreateBuffer: Assertion Failed!
No AHardwareBuffer (format,usage) combination is importable by this Vulkan driver
STOP: GPU library assertion failed
```

结论：**游戏在 GPU 初始化阶段就停了**，还没到移交文档里记录的「渲染上下文/堆 abort」那一步。这是一个更早、更靠前的失败点。

## 根因分析（已定位到驱动侧，非 swapchain 逻辑）

1. `AHardwareBuffer_allocate` **成功**（日志没有出现 "allocate failed"），说明系统 gralloc 能分配 buffer。
2. 4 种 (format, usage) 组合的 `vkGetAndroidHardwareBufferPropertiesANDROID` **全部**返回 `VK_ERROR_INVALID_EXTERNAL_HANDLE`。换格式/usage 无效。
3. 检查当前加载的 `libvulkan_freedreno.so`（Turnip 26.3.1，本地有同版本副本）内部字符串，确认它依赖 u_gralloc 解析 AHB：

   ```
   QCOM Gralloc API is old. Consider using Gralloc4 API instead.
   u_gralloc_get_buffer_basic_info failed
   Failed to import dma-buf (%s)
   No compatible AHB mem types (img_reqs=%#x,fd_reqs=%#x)
   No gralloc hwmodule detected (video buffers won't be supported)
   Using fallback gralloc implementation
   ```

   `vkGetAndroidHardwareBufferPropertiesANDROID` 返回 `INVALID_EXTERNAL_HANDLE` 的直接原因就是这条链上 **u_gralloc 无法从 AHardwareBuffer 的 gralloc handle 提取 dma-buf fd / 格式 / modifier**。

4. 仓库里 `tools/android/mesa-patches/v37/a8xx_gen8.patch` 明确记录了同类问题的修复：

   > u_gralloc checks if the current buffer was allocated by the Qualcomm vendor gralloc by comparing 'gmsm' magic with the buffer data. Since newer gralloc versions this way isn't working anymore, causing the condition to never success - essentially skipping setting proper DRM format modifier.

   即：**新版 gralloc 上，u_gralloc 的 'gmsm' magic 检测失效 → 拿不到正确的 DRM format modifier → AHB 属性查询/导入失败**。这正是 `INVALID_EXTERNAL_HANDLE` 的来源。

## 关键矛盾（为什么旧环境能 SHOW 帧）

- 移交文档记载：同一台设备、真机 SHOW 过帧（旧二进制 + 旧驱动组合）。
- 本次：同一设备、Turnip 26.3.1、新编译的 libbbgpu.so（含 AHB 多尝试诊断）。
- 仓库 pin 的驱动是 Mesa 26.2.3/26.3.0 + fexdroid 补丁（commit `35b085c`），日志里的却是 **26.3.1-lto**，来源不明。
- 结论：**驱动变体/构建与能工作的旧组合不一致，是本次失败的最大嫌疑**。swapchain 的多尝试改动与基线第一个尝试等价，不是根因（4 种全失败已证明）。

## 决定性证据（反汇编 Turnip 26.3.1 驱动）

对真机所用同版本 `libvulkan_freedreno.so` 做反汇编，确认根因：

1. `vk_common_GetAndroidHardwareBufferPropertiesANDROID` 遍历 AHB 的 native_handle，逐个比对 gralloc handle magic：
   - `0x3ba1ee22` = **'gmsm'**（Qualcomm 私有 gralloc magic）
   - `0x3b9cc1ee` / `0x3b9cc1ea`（其他 gralloc handle 版本）
2. magic **全部匹配失败** → 走 `mesa_log` 分支，打印 `AHB (format=%u,usage=0x%lx) has no GPU usage`，返回 `0xc4641cbd` = **-1000072003 = VK_ERROR_INVALID_EXTERNAL_HANDLE**（已换算验证）。
3. chip6/7/8 的模板实现是运行时填充的 dispatch，最终错误来自 u_gralloc 无法解析 handle → dma-buf 导入失败。

**结论闭合**：系统 gralloc 分配的 AHB handle 的 magic 无法被这个 Turnip 26.3.1 的 u_gralloc 识别 → 驱动判定 AHB「没有 GPU usage」→ 拒绝 import → `INVALID_EXTERNAL_HANDLE`。这与仓库里 `v37/a8xx_gen8.patch` 描述的 bug 完全一致（原文：*newer gralloc versions → 'gmsm' magic check never succeeds → skipping proper DRM format modifier*）。

## 修复选项（按推荐顺序）

### 选项 A（推荐，治本）：重编并部署带 a7xx gralloc 修复的 Turnip
- 用 `tools/android/build_turnip_android.sh gen8` 或应用 `v37/a8xx_gen8.patch` 的变体重编 `libvulkan_freedreno.so`，替换真机上的 26.3.1。
- 前提：需要 mesa 源码 + NDK（本地 NDK r29/r30 与工具链齐全，但 mesa 源码需联网拉取，环境可能重置）。
- 风险：编译耗时长（10–30 分钟），环境频繁重置可能中断。

### 选项 B（快速定位）：诊断增强构建
- 在 `CreateBuffer` 里打印 AHB 的 native handle / dma-buf fd / format / usage，以及 `vkGetPhysicalDeviceExternalBufferProperties` 对 AHB handle type 的支持情况。
- 出一版带强诊断的 libbbgpu.so，真机跑一次即可确认到底是「驱动不支持 AHB import」还是「gralloc handle 有问题」。
- 不改变现有失败行为，但能让下一次定位一锤定音。

### 选项 C（治标绕开）：换用非 AHB 呈现路径
- 改为普通 device-local VkImage 渲染 + readback 到内存，通过 socket 传像素给 app。
- 性能差，但能绕开 AHB import 问题，先让游戏画面出来验证其余逻辑。
- 改动大，不推荐作为首选。

## 我的建议

先做 **选项 B**（诊断增强，快速、低风险、能一锤定音），根据真机返回的诊断信息再决定 A 还是 C。

---

## 实施记录（用户选择选项 A，已执行）

用户选择选项 A（治本）。已完成修复版驱动的编译与打包：

**编译产物**：`drivers/turnip-ahbfix/libvulkan_freedreno.so`（13.7 MB，Mesa 26.3.0-devel @ 35b085c）

**打入的补丁**（BUILD-INFO.json 记录，共 7 个）：
- 基础 6 个：0001 / 0006 / 0008 / 0009 / **0010（lazy hw_get_module → u_gralloc fallback）** / 0011
- 新增 **0012-u_gralloc-always-ubwc-path.patch**：从 `v37/a8xx_gen8.patch` 里抽出 GPU 无关的 u_gralloc HACK——注释掉失效的 `'gmsm'` magic 门禁，改为无条件按 UBWC flag 计算 modifier。这个 hunk 只改 `src/util/u_gralloc/u_gralloc_fallback.c`，对 a7xx（735）同样适用。

**为什么不直接用 26.3.1-lto / gen8 / 整个 a8xx_gen8.patch**：
- 真机原来的 nihui 26.3.1-lto（17.6MB）与移交文档记载能 SHOW 帧的 Turnip R2（8.36MB）是两个不同驱动，本地均已丢失可用副本。
- `gen8` / 整个 `a8xx_gen8.patch` 是针对 Adreno 8xx 的 V37 recipe，含大量 8xx 专用改动，不能套到 gen7 的 735 上。
- 因此选用项目验证过的 main 配方（35b085c + fexdroid 补丁）+ 单独抽出 u_gralloc hunk，兼顾兼容性与针对性。

**验证结果**：
- BUILD-INFO 确认 0012 已打入；修复版 .so 中 gmsm magic 常量 count=0。
- 反汇编确认 `vk_android_get_ahb_image_properties` → `u_gralloc_get_buffer_basic_info` → `fallback_gralloc_get_buffer_info`（修复点）的调用链成立：修复让 AHB 属性查询拿到正确 modifier 而非 `DRM_FORMAT_MOD_INVALID`。
- 导出 `HMI`（HAL 直载，真机路径）+ `vk_icdGetInstanceProcAddr`（ICD 路径）双入口。
- NEEDED 仅 Android 系统库，无 libhardware.so（0010 生效），可被 app namespace dlopen。

**诚实边界**：无法在沙箱连接真机验证。部署与回退步骤见 `drivers/turnip-ahbfix/部署说明.md`。若真机仍失败，需根据新日志（是否变成 `No compatible AHB mem types` / `Failed to import dma-buf` / `has no GPU usage`）进一步定位，可能需要换驱动变体或改呈现后端（选项 C）。
