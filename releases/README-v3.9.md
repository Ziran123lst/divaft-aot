# DIVA-Launcher v3.9 — 修復黑屏：補回 d7 第三個補丁

## 黑屏根因：上一版把因果判斷反了

v3.8 黑屏不是因為打了太多補丁，而是因為**少打了第三個補丁**。這一次用源碼 + 反匯編把因果鏈完全鎖死了。

關鍵源碼（`gnmdriver.cpp`）：

```cpp
bool SubmitLockOnDecode() {
    return on && Vulkan::Rasterizer::HonestLabels();
}
```

`SubmitLockOnDecode()` 的返回值**依賴 HonestLabels()**。三個版本的差異：

| 版本 | HonestLabels() | SubmitLockOnDecode() | sceGnmSubmitDone 走的路徑 | 結果 |
|---|---|---|---|---|
| v3.7（正常但慢） | =1（環境變數） | **true** | WaitPreviousFrame 正常路徑 | 能玩，~28fps |
| v3.8（黑屏） | 補丁固定 **false** | **false**（連帶被拖成 false） | WaitGpuIdle → IsGpuIdle 空等 | 5 幀後黑屏 |
| d7（58.7fps） | 補丁固定 false | **true**（第三補丁直接固定） | WaitPreviousFrame 正常路徑 | 快且穩 |

v3.8 打了 `HonestLabels()→false` 補丁，卻**沒打第三個補丁**，導致 `SubmitLockOnDecode()` 連帶變成 false，掉進 v3.5 同款的 `WaitGpuIdle` 死鎖分支——日誌鐵證：bbpresent 只有 frame 1~5 之後就再也沒有新幀，guest 音訊卻持續正常（audio stats 一波不缺）。這正是之前 d7 被誤判成「卡開頭元凶」的第三補丁，其實是 HonestLabels=false 的**必要配套**。

## v3.9 修復

把第三個補丁補回去，`libbbgpu.so` 完整復現 d7 的三個補丁：

| 補丁 | 偏移 | v3.9 |
|---|---|---|
| GpuDirty NOP | 0xB1C4DC | 已打 |
| HonestLabels()→false | 0xA5E274 | 已打 |
| SubmitLockOnDecode()→true | 0xC15C94 | **補回（mov w0,#1; ret）** |

dex 保持 v3.8 不變（d7 的 16 個環境變數 + 音頻診斷），其他一切不動。

## 覆蓋安裝

- 簽名與 v1.1~v3.8 完全一致（SHA-256 `04908028...e5e18`）。
- 逐條目驗證：相對 v3.8 只有 `libbbgpu.so` + 簽名檔變化，dex/AOT/驅動/其他庫逐位元組相同。
- so 動態符號表 8254 未變，ABI 相容。直接覆蓋即可。

## 請回傳

跑一下發新的 `diva-boot.log` + `bbpresent-diag.log`，我看三件事：
1. bbpresent 是否持續出幀（不再停在 frame 5）
2. 是否出現 `Frame stats:` 且 FPS 往 50-60 走
3. `Audio stats port N:` 是否依舊 starved 0（聲音線：guest 輸出正常，問題在 Java AudioTrack 播放端，下一步查）

如果仍有異常，面板可把 HonestLabels/VBLANK 相關調回，或直接告訴我，我按新日誌繼續定位。
