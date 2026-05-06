# OnePlusPlusLauncher v1.3.2

## 修复第三方输入法下拉后键盘闪现收回

**基于 v1.2.7（稳定版）构建**

### 问题
使用手心输入法等第三方键盘时，下拉打开应用抽屉后键盘短暂弹出又立即收回。

### 原因
第三方输入法（如手心输入法）在抽屉动画结束时（约 430ms）会主动调用 `hideSoftInput` 收起自身，而原有的 500ms 焦点触发延迟已晚于这个时机。

### 修复
- 将焦点触发延迟从 500ms 缩短至 300ms
- 在 T+100ms 使用 `SHOW_FORCED` 锁定键盘，在输入法自动收回（~430ms）之前完成

### 已验证兼容输入法
| 输入法 | 包名 | 状态 |
|--------|------|------|
| 微信输入法 | `com.tencent.wetype` | ✅ 正常（v1.2.7 已支持） |
| 手心输入法 | `com.xinshuru.inputmethod` | ✅ 此版本修复 |

> 其他第三方输入法如遇类似问题欢迎反馈

## Installation

1. Make sure your device is rooted and you have LSPosed installed.
2. Download the latest release APK from the assets section below.
3. Install the APK on your device.
4. Enable the module in LSPosed and make sure **System Launcher** is in scope.
5. Restart System Launcher (or reboot).
