# DIVA-Launcher v4.3 — 修復「玩一會兒卡死」（穩定優先）

## 卡死根因（有日誌證據，不是猜）

你回傳的 v4.1-Safe 日誌其實记录了卡死：bbpresent 停在 **frame 360**，之後再無新的 Image sync / Frame stats，但 audio 繼續印到結尾 —— guest 渲染執行緒停了，presenter 無限等待（bbpresent.c 的 `poll(-1)`），所以畫面定格。

逐行對比 v3.7（稳定跑 2700+ 帧）和 v4.1-Safe（卡在 360 帧），找到關鍵差異：

| 狀態 | v3.7（稳定） | v4.1-Safe（卡死） |
|---|---|---|
| `GPU: fences written ... (BB_HONEST_LABELS=1)` | **有** | **沒有** → HonestLabels=false |
| Guest memory | `BB_GUEST_IN_PLACE is not 1`（false） | `BB_GUEST_IN_PLACE=1` 但被高通降級成 0（混亂狀態） |
| swap / MemAvailable | 正常 | swap 85%、MemAvailable 1940MB |

根的因果鏈（源碼證實 `vk_rasterizer.cpp:114`）：

```cpp
const bool enabled = (env[0]=='1') || VideoCore::GuestInPlace();
```

dex 寫 `BB_HONEST_LABELS=0` + `BB_GUEST_IN_PLACE=1`，但高通 735（vendor 0x5143）不支援 in-place → native 降級成 GuestInPlace=0 → 兩個條件都不成立 → **HonestLabels()=false** → 走 WaitGpuIdle 路徑 → 在記憶體壓力下最終卡死。v3.7 靠 HonestLabels=true 稳定跑完全程。

## v4.3 修復

只改 dex 兩個值（so 保持無補丁，其餘提速變數全保留）：

- `BB_HONEST_LABELS`: `0` → **`1`**（恢復 v3.7 的穩定路徑）
- `BB_GUEST_IN_PLACE`: `1` → **移除**（避免高通降級混亂）

保留的 d7 提速項：录制线程 1、帧步进、GSR2 升采样、vblank 60 等全部不變。

## 預期與取捨（誠實說）

- **首要目標是消除卡死**：HonestLabels=1 會回到 v3.7 已验证稳定的路徑。
- **幀率可能比 54.6 低一些**：HonestLabels=1 會恢復 EOP 等待（v3.7 日誌約 17-18ms），所以帧率可能落在 30-45 區間，不一定還有 54。**穩定優先**——先能一直玩，再談提速。
- 如果這版穩定不卡了、你還想更快，我再在 HonestLabels=1 的前提下逐项加速（那是安全的方向）。

## 驗證

- 相對 v4.1-Safe 只有 dex + 簽名檔變化；so 無補丁（ABI 不變）。
- 簽名一致（`04908028...e5e18`），可覆蓋安裝。

## 請回傳

跑久一點（至少 5-10 分鐘），回傳 `diva-boot.log` + `bbpresent-diag.log`。確認兩件事：
1. bbpresent 是否持續出幀（越過 frame 360、一直往上）
2. 是否出現 `GPU: fences written ... (BB_HONEST_LABELS=1)` 這行（確認走對路徑）

如果仍卡死，日誌會告訴我是哪一步，我據此再修，不再反覆猜。
