# DIVA-Launcher v3.4 — 性能根因修复（帧率断崖）

真机日志 + 对照 shadPS4 / bb-recomp 上游源码，定位到"进游戏帧率高、随后急速下降"的**真正根因**，v3.4 予以修复。

## 根因 1（致命）：纹理缓存预算被设成 64 MB（差 56 倍）

v3.3 设置面板把 `BB_GC_BUDGET_MB` 默认成了 **64 MB**。而 bb-recomp 上游 `run-bionic.sh` 对 8GB 机型的推荐是 **3584–4096 MB**。

`texture_cache.cpp` 里触发淘汰的阈值是 `budget*0.7`：64MB 时只要纹理占用超过 ~45MB 就疯狂 evict/reload，形成"渲染→填满→全部淘汰→重新上传→再填满"的死循环，帧率断崖式下降。这正好解释"刚进去快、随后崩"。

**v3.4 修复**："自动"档按上游公式 `MemAvailable - 2560MB(游戏直连内存) - 1536MB(系统预留)`，clamp 到 3584–4096MB，8GB 机型得到 3584MB。启动日志会打印实际预算。

## 根因 2：两阶段绘制管线被硬编码关掉

`BB_DRAW_PIPE=0` 硬编码关闭了 `DrawPipe`。上游在 16 线程实测 **+19%**、在 8 线程（类 Steam Deck）实测 **+18%**。

**v3.4 修复**：移除该硬编码，恢复 native 按 CPU 线程数自动启用（>=8 线程开）。

## 诊断开关

`BB_FRAME_STATS=1` 默认开启，`diva-boot.log` 每 5 秒输出一行真实 FPS、最差帧时间、shader 编译数、绘制数、纹理淘汰数、GPU 线程耗时。下一轮若仍有问题，可直接据此精确定位，无需再猜。

## 覆盖安装安全

- 签名与 v1.1～v3.3 完全一致（debug key，SHA-256 `04908028...e5e18`）。
- native 库 / boot-linked / AOT / Turnip 驱动与 v3.3 逐字节一致，仅替换 `classes.dex`。

直接覆盖 v3.3 即可，无需卸载、无需清数据。

## 若仍卡

把新的 `diva-boot.log` 发来，重点看 `Frame stats:` 行：绘制数、纹理淘汰数、GPU 线程 us/draw、FPS。
