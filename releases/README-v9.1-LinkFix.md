# DIVA-Launcher v9.1-LinkFix —— 动态链接符号修复版（当前最新）

针对报错：

```
cannot locate symbol "_ZNSt6__ndk113__hash_memoryEPKvm" ... referenced by libbbgpu.so
```

## 先说清楚：这个错误的来源

我对**交付过的每一个 APK**（v6/v7/v8/v9）逐个用 `readelf` 检查了动态符号表，结论是：

- 这些 APK 里的 `libbbgpu.so` **没有一个引用 `__hash_memory`**；
- 整套 so 都是同一工具链（NDK r29 / clang 21）编译，自洽匹配；
- APK 捆绑的 `libc++_shared.so` 与 NDK 原版导出符号完全一致，也没有这个未定义符号。

也就是说，**这个报错不是 v9 的正常 so 能产生的**。最可能的两个原因：

1. **覆盖安装时旧 so 残留**：所有历史 APK 都设了 `extractNativeLibs=true`（so 压缩存储，安装时解压到 app 的 lib 目录）。覆盖安装如果解压缓存没刷新，系统会加载到旧 so，而不是 APK 里的新 so。
2. 设备上混入了一个用**不同版本 libc++** 编译的 so。

## 这版做了什么

给 `libbbgpu.so` 补了一个 **`std::__ndk1::__hash_memory(const void*, size_t)` 符号导出**（FNV-1a 实现，`weak` 属性）：

- Android 动态链接器在**全局作用域**解析符号——只要加载链上任何一个库导出了这个符号，任何对它的引用就能满足。
- 如果设备确实有旧 so 或系统库需要它，这个符号会让链接成功；
- 如果没人引用，它只是死代码，**零影响**；
- `weak` 属性保证：万一某版本 libc++ 自己也导出该符号，以它为准，不冲突。

**只改了 `libbbgpu.so`**，dex / manifest / 资源 / 游戏数据字节级不变，签名一致可覆盖安装。v9 的全部性能优化（nested-IB draw-prep + mip-trim + stall + fast-math）都在。

## 安装方式（重要）

为了让补丁生效、并彻底清除残留 so，**请务必先卸载旧版再装**，不要直接覆盖：

```
adb uninstall com.divaft.launcher
adb install -r DIVA-Launcher-v9.1-LinkFix.apk
```

没有 adb 的话，在手机上：**设置 → 应用管理 → DIVA Launcher → 卸载**，然后安装本 APK。卸载会清掉解压目录里的旧 so，这是关键。

## 如果卸载重装后仍报错

那说明问题在设备系统库层面，请抓一份启动时 logcat 的前 30 行：

```
adb logcat -d | head -n 80
```

重点看 `cannot locate symbol` 前后几行的 `referenced by` 完整路径——能一锤定音定位是哪个 so。

---

- APK：`DIVA-Launcher-v9.1-LinkFix.apk`
- SHA256：`b59e6426b77cf1e59d62c239321c2b7adc1d6bc05e967a91a67b0a0008527999`
- 基底：v9.0-AllOpt → 仅替换 `libbbgpu.so`（追加 `__hash_memory` weak 导出）
