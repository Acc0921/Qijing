# 栖境真机验收报告：Xiaomi 2410DPN6CC（2026-08-31）

## 设备与安全边界

- 设备：Xiaomi `2410DPN6CC`（代号 `haotian`）
- 系统：Android 17 / API 37，安全补丁 `2026-08-01`
- 架构：`arm64-v8a`，Qualcomm，8 核，CPU policy 为 `policy0` 与 `policy6`
- 显示：1440 × 3200，600 dpi
- 安全状态：SELinux Enforcing；检测到 KernelSU；ADB shell 中没有可见 `su`；未检测到 Shizuku
- 设备用途：用户主力机，也是当前唯一 Root 设备

本轮只允许安装、启动、截图、日志、普通 ADB 只读检查与应用内 debug 模拟测试。任何 Root 请求、`su`、`/proc` 或 sysfs 写入、CPU governor/频率、swappiness、权限策略、重启及服务级操作，必须先列出原值、目标值、精确命令、恢复命令和风险，并获得用户明确确认。ZRAM 重建不执行。

## 已完成验收

- debug APK 安装成功，`com.qijing/.MainActivity` 可启动并保持前台运行。
- 首页、产品名与模块编号/名称布局显示正常，日志中未发现应用崩溃。
- 49/49 JVM 测试、lint、debug 与 release 构建通过。
- AndroidX Test 升级至 Runner 1.7.0、JUnit 1.3.0、Espresso 3.7.0；Android 17 上不再出现 `InputManager.getInstance()` 隐藏 API 兼容异常。
- 设备测试中的 debug 调节持久化恢复用例已通过；该用例只使用应用私有存储和模拟后端，不访问真实 Root 或系统调节节点。

## 待完成验收

本轮 4 条设备测试运行至 1/4 后，设备自动熄屏并进入安全锁，第二条 Compose UI 用例无法继续获取窗口焦点。测试已主动终止，未将本轮记为完整通过。待用户手动解锁并保持屏幕亮起后，需要重新执行全部 4 条设备测试：

1. 首次打开首页与服务控制。
2. M8 FPS 页面导航。
3. M3 场景编辑页面导航。
4. debug 调节 journal 跨存储重建恢复。

测试中断后已卸载 `com.qijing.test`，并重新安装最终 `com.qijing` debug APK。未请求 Root，未修改 CPU、内存、ZRAM、SELinux、权限策略或系统性能参数。

## 当前构建产物

- APK：`outputs/Qijing-v0.1.0-debug.apk`
- SHA-256：`4C81D5BE25EF0A666E3F0AFB896C2FE31A3C7640FC0A7CD32747E61610DF9EFF`

## 尚未授权的高风险验收

- KernelSU Root 授权与 `su` transport
- 真实 CPU governor、最低/最高频率写入与恢复
- 真实 swappiness 写入与恢复
- 厂商内核节点权限、SELinux 拒绝路径和异常回滚

以上项目不因设备已连接而自动获得授权。
