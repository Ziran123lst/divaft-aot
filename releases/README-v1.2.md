# DIVA Launcher v1.2 — 存档 dialog + AOT miss 修复

## 下载
[DIVA-Launcher-v1.2-SaveAOT.apk](DIVA-Launcher-v1.2-SaveAOT.apk)(sha256 见 `.sha256`)

## 相对 v1.0 的修复
1. **存档黑屏**:补全 `libSceSaveDataDialog` 的 `GetResult` / `IsReadyToDisplay` /
   `CommonDialogIsUsed` 等,自动应答合法存档槽 `SAVEDATA00`。真机已确认黑屏消失、画面正常。
2. **AOT miss**:v1.1 走到存档深处时 `STOP: native code is missing an entry at +0xa97d5`。
   用 `bb-recomp --extra-entries`(地址 0xa97d5)重编 libbbaot.so,补上该入口(units 57133→57134)。
3. **事件函数**:补 `sceSaveDataGetEventResult`(返回 NOT_FOUND),消除存档流程的 STUB。

## 安装
覆盖安装即可,签名与旧版一致,不丢存档。AHB 驱动修复版保持不变。

## 若仍有问题
抓新 `diva-boot.log`。若出现新的 `native code is missing an entry at +0x.....`,
把地址发我,用同样的 `--extra-entries` 流程再补一次即可。详见 `docs/SAVEDIALOG-FIX.md`。
