# DIVA Launcher v1.4 — 手写存档 GUI,修复循环弹窗

## 下载
[DIVA-Launcher-v1.4-SaveGUI2.apk](DIVA-Launcher-v1.4-SaveGUI2.apk)（sha256 见 `.sha256`）

## 修复内容
v1.3 的弹窗会循环:选完一次又弹。根因是 Java 弹窗后没删除 request 文件,同一请求被反复处理。
v1.4:
- **手写 GUI**:不用系统 AlertDialog,自己用 View 画了一个存档管理界面(标题/提示/槽位列表/取消按钮)。
- **令牌机制**:请求带递增 SEQ,响应必须回显相同 SEQ;请求被消费后立即删除,保证一个 dialog 只弹一次。
- 保留超时(600s)自动应答兜底。

## 说明
画面恢复版见 v1.2.1。存档 dialog 本身不再阻塞游戏;若选完存档继续加载时停在
`native code is missing an entry at +0x.....`,那是全 AOT 严格模式的底层问题,GUI 解决不了,
把地址发我。详见 docs/SAVEDIALOG-FIX.md。
