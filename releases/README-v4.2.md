# DIVA-Launcher v4.2 — 修復黑屏，實測 54.6 FPS（推薦版本）

## 結果：找到並修復了黑屏根因

v4.1-Safe 的二分測試**一刀切定位**了根因：

| 版本 | so | dex | 結果 |
|---|---|---|---|
| v3.7 | 無補丁 | 老配置 | 能玩（慢） |
| v3.8/v3.9/v4.0 | 有補丁 | d7 16 項 | **黑屏 frame 5** |
| **v4.1-Safe / v4.2** | **無補丁（v3.7 的）** | **d7 16 項** | **能玩，54.6 FPS** |

**結論：黑屏根因是 native so 的補丁**（HonestLabels→false 那個補丁與這台 Adreno 735 / DIVA 不相容），**不是** d7 的環境變數。d7 的 16 個環境變數本身是好的、有效的——去掉有問題的 so 補丁後，單靠環境變數就跑出接近 d7 的效能。

## v4.2 實測數據（用戶 v4.1-Safe 日誌）

```
Frame stats: 54.6 FPS          ← 穩定窗口
Frame pacing: median 16.83 ms  ← ≈59.4 FPS 幀間隔
bbpresent: 出幀到 frame 360+    ← 黑屏消失
deadlock/error: 無
Audio stats: starved 0 (port 0/1/17 全部 468-470 buffers/5s)
```

- 黑屏消失，穩定在 54~59 FPS，非常接近 d7 的 58.7 FPS。
- 開場第一個窗口 14.0 FPS 是 shader 編譯，之後升到 54.6，屬正常。

## v4.2 = v4.1-Safe 的正式命名（位元組完全一致）

- so：v3.7 的**無補丁** libbbgpu.so（ABI 8254 符號不變）。
- dex：d7 環境變數 + vblank 60 + 音頻診斷（就是跑出 54.6 的那套）。
- 簽名一致（`04908028...e5e18`），可覆蓋安裝。
- sha256 與 v4.1-Safe 相同：`f42084f...c1ec6a`。

## 關於效能還能再上一點

d7 用 so 補丁跑到 58.7，但那組補丁會讓這台設備黑屏。目前 54.6 是**不用補丁、完全穩定**的結果。介於兩者之間可以再微調環境變數（如 BB_HONEST_LABELS 的 dex 值、提交提前量），但那是錦上添花——先享受能玩且接近 60 的版本。

## 聲音線（尚未修復）

guest 音頻輸出完全正常（starved 0、buffer 滿額），所以無聲問題在 **Java AudioTrack 播放端**（AudioBridge.java），與渲染/黑屏無關。這是下一條獨立要查的線。
