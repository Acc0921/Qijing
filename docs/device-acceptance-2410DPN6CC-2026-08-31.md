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
- 显式固定 AndroidX Tracing 1.2.0，修复 Espresso 3.7 与旧版 Tracing 组合产生的 `Trace.forceEnableAppTracing()` 缺失；2.0.1 因超出当前 AGP/R8 的 Kotlin 元数据兼容范围未采用。
- 设备测试中的 debug 调节持久化恢复用例已通过；该用例只使用应用私有存储和模拟后端，不访问真实 Root 或系统调节节点。
- 用户已授予栖境 KernelSU 权限；应用首页显示“执行后端：ROOT”并检测到 `su`。真机专用测试通过 `/system/bin/su -c "id -u"` 在 53 ms 内返回 `0`，确认 Root transport 实际可用。ADB shell 仍不可见 `su`，符合按应用授权的配置。

## 待完成验收

原有 4 条设备测试每次均完成第 1 条 debug 持久化恢复用例；新增 Root 只读握手另行执行并通过。锁屏问题已通过临时“USB 保持唤醒”解决，测试依赖异常也已修复；后续复跑时前台被切换到系统设置或微信，Compose UI 用例无法继续获取栖境窗口。测试已主动终止，未将整套用例记为完整通过。待设备有约 2 分钟无人操作窗口后，还需完成 3 条 Compose UI 路径并最终复跑 5/5：

1. 首次打开首页与服务控制。
2. M8 FPS 页面导航。
3. M3 场景编辑页面导航。

测试结束后已卸载 `com.qijing.test`，并保留最终 `com.qijing` debug APK。测试期间临时保持唤醒均恢复为原值 `0`。除已获准的 `id -u` 只读 Root 握手外，未执行其他 Root 命令，未修改 CPU、内存、ZRAM、SELinux、权限策略或系统性能参数。

## 当前构建产物

- APK：`outputs/Qijing-v0.1.0-debug.apk`
- SHA-256：`40688EF4E6E7398B22F5FBC559D15A343E602605CF8598C07F762265B8117915`

## 尚未授权的高风险验收

- 真实 CPU governor、最低/最高频率写入与恢复
- 真实 swappiness 写入与恢复
- 厂商内核节点权限、SELinux 拒绝路径和异常回滚

以上项目不因设备已连接而自动获得授权。
