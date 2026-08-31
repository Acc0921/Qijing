# 栖境模拟器验收报告（2026-08-31）

## 验收环境

- 宿主机：Windows，AMD Ryzen 7 5800U，BIOS 虚拟化已开启
- 模拟器：Android Emulator 37.1.11，AEHD 2.2
- Android 15：API 35，x86_64，`Qijing_API_35`
- Android 6：API 23，x86_64，`Qijing_API_23`
- 独立 SDK 与 AVD：`D:\Program Files\Android\qijing-emulator`

## 自动化结果

| 验收项 | Android 15 | Android 6 |
| --- | --- | --- |
| 应用与测试 APK 安装 | 通过 | 通过 |
| 首次打开首页与服务控制 | 通过 | 通过 |
| M3 场景编辑入口 | 通过 | 通过 |
| M8 FPS 入口 | 采集控件通过 | 不支持提示通过 |
| Compose UI 与 debug 恢复测试 | 4/4 | 4/4 |

本轮同时通过 49 条 JVM 测试、debug/release APK 构建与 lint。Android 6 运行时出现的 `additionalTestOutput` 提示来自测试框架不支持 API 23 的附加输出目录，不影响测试结果。

## 验收发现与修正

1. 工程未声明 AndroidX instrumentation runner，设备端使用旧 runner 后在发现用例前崩溃。现已显式配置 `androidx.test.runner.AndroidJUnitRunner`。
2. 点击 M3/M8 模块仅更新列表底部内容，用户当前视口看不到页面变化。现已在点击后自动滚动到对应内容页。
3. 小屏 Android 6 AVD 不会预先创建列表末尾的 M8 节点。测试改为按列表位置滚动，并单独验证 Android 6 的 M8 降级提示。
4. Android 15 截图发现模块名称与编号挤在一起，已让卡片内容占满宽度并左右对齐。

## Debug 调节模拟

- 模拟 CPU governor、最低/最高频率和 swappiness，不调用 shell、Root、Shizuku 或真实 sysfs。
- 覆盖完整快照、参数拒绝、写后读回、写后失败、逆序回滚、回滚失败保留 journal、损坏 journal 安全拒绝。
- 在 Android 15 与 Android 6 上验证 SharedPreferences journal 跨存储对象重建后可恢复原始状态。
- APK 静态检查确认 debug 包含模拟实现，release DEX 中模拟符号为 0，release Manifest 中 debug 条目为 0。

## 模拟器验收边界

模拟器结果可证明安装、启动、Compose 导航、最低版本降级和应用内流程可运行，但不能替代以下真机结论：

- Root 管理器授权与不同厂商 `su` 行为
- CPU governor、最低/最高频率节点的真实写入效果
- 厂商内核、SELinux 与 sysfs 权限差异
- ZRAM 重建、温度、功耗和真实性能
- 其他应用 FPS 与设备刷新率表现

Root/Shizuku 和真实调节在完成真机验证前不得标记为发布可用；ZRAM 重建继续保持禁用。
