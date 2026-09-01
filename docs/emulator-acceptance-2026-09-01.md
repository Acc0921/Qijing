# 栖境模拟器验收报告（2026-09-01）

## 本轮目标

验证五页 Android 原生化重构后的核心路径，并重点验收场景结构化任务轨迹、正式恢复 journal、进程重建恢复和安全失败语义。本轮仅使用测试替身与模拟器，不调用 `su`，不写入 CPU、频率、swappiness 或 ZRAM。

## 环境

- JDK 17.0.18，Gradle 8.11.1，Android SDK 35
- Android Emulator 37.1.11.0
- `Qijing_API_23`：Android 6.0 / API 23 / x86_64
- `Qijing_API_35`：Android 15 / API 35 / x86_64

## 自动化结果

| 验收项 | 结果 |
| --- | --- |
| JVM 单元测试 | 78/78 通过，0 跳过 |
| API 23 模拟器测试 | 10 项：9 通过，1 项 Root-only 条件跳过 |
| API 35 模拟器测试 | 10 项：9 通过，1 项 Root-only 条件跳过 |
| AndroidTest 编译 | 通过 |
| Android Lint | 通过 |
| Debug APK | 构建通过 |
| Release APK | 构建通过 |

API 23 的 `additionalTestOutput is not supported` 是测试框架无法使用附加输出目录的提示，不影响用例结果。两台模拟器跳过的均为只允许在已授权 Root 真机执行的 UID 0 握手测试。

## 本轮覆盖

- dry-run 轨迹以 `PREVIEWED` 结束，文案明确“系统未修改、无需恢复”，不会进入 `ACTIVE` 或伪造恢复结果。
- Root/Shizuku 场景事务在首条真实命令前必须存在完整持久化 journal；缺少 journal 时零写入阻断。
- journal 保存场景、应用、原后端、恢复命令和逐项阶段；新存储实例能够读取并逆序恢复。
- `WRITE_STARTED` 与 `APPLIED` 记录会被恢复，`PENDING` 不会被误恢复；恢复失败时保留 journal 供后续处理。
- 损坏 journal、持久化失败和恢复不完整均 fail-closed，不允许继续新的真实场景写入。
- journal 更新使用事务 id 与 revision 比较更新，旧 session 不能覆盖或清除较新的恢复进度。
- 执行与恢复 broker 必须声明身份并与 journal 原后端一致；返回结果后端不一致时保留 journal。
- 重启恢复在前台服务启动后转入 IO 协程，不在 `onCreate()` 中等待最长 30 秒。
- 总览与场景页能够观察同一任务的命中、预检、快照、执行、验证、恢复和异常事件。

## 验收中修正

1. 将重启恢复从主线程同步等待改为前台服务内异步 IO 恢复，消除 `onCreate()` 的 ANR 风险。
2. 将 dry-run 最终事件从“等待离场恢复”修正为“预演完成、无需恢复”。
3. 将持久化 journal 从生产装配约定提升为核心执行契约：真实场景执行缺少 journal 时拒绝首条写入。

## 结论与边界

本轮证明场景事务、进程重建恢复、轨迹展示和五页 UI 能在最低 API 与当前 API 模拟环境运行。它不证明 Root/Shizuku 在厂商内核上的真实写入、读回和恢复可用，也不证明真实性能收益。

Root/Shizuku 的 CPU governor、频率和 swappiness 仍需按原值、目标值、精确命令、恢复命令和风险逐项获得用户确认后，才能在真机执行。ZRAM 重建、CPU 核心上下线、任意 Shell、daemon 与外部应用 FPS 继续保持关闭。
