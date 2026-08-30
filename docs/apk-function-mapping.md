# APK 静态解析功能映射

来源：`scene_9.3.8.apk`（包名 `com.omarea.vtools`，版本 9.3.8，versionCode `920260712`）。本文件记录解析到的能力，以及第一版如何重新实现；它不是旧数据迁移清单。

## 解析到的主要能力

- root/ADB/Shizuku/scene-daemon 多后端执行，包含 BusyBox、binder 工具和 JNI native 库。
- 应用场景切换：前后台应用切换、通知监听、无障碍、开机恢复、Quick Settings Tile。
- CPU/性能：核心在线、频率上下限、governor、boost、FAS/FAS Lite 和厂商适配脚本。
- 内存：swapfile、ZRAM、压缩算法、swappiness、drop_caches、force compact、冻结后台应用。
- FPS/性能实验：FPS session、frame time、jank、CPU/GPU/温度/功耗关联记录。
- 应用管理：搜索、分类、冻结/解冻、启动/停止/清理、应用详情。
- 173 个 KrScript、16 个内置命令和 Addin/脚本资源。

## 第一版重实现映射

| APK 能力 | 新模块 | 实现方式 |
| --- | --- | --- |
| root/ADB/Shizuku/daemon | C0 | `ExecutionBroker` 统一后端，命令是结构化 `CapabilityCommand`，默认 dry-run。 |
| 场景切换与恢复 | C1 + M3 | `SceneEngine` 创建事务快照，按应用包名和触发器应用/恢复 `SceneProfile`。 |
| 厂商/内核能力判断 | C2 | `DeviceCapabilityProbe` 输出可探测能力，不把具体 sysfs 路径写进 UI。 |
| 旧 SQLite/`scene_config3` | C3 | 新的 `DeviceSnapshot`、`AppEntry`、`SceneProfile`、`TelemetrySample` 模型；不读取旧表。 |
| shell 日志与脚本错误 | C4 | `TaskLog` 和 `ExecutionResult` 记录阶段、耗时、错误码和 rollback token。 |
| 多 Activity/XML 页面 | C5 | 单 Activity Compose 导航，模块页面只消费 ViewModel 状态。 |
| 应用查询/冻结等 | M2 | `ApplicationCatalog` 接口；冻结/解冻作为需要 C0 授权的后续 command。 |
| CPU profile/FAS | M4 | `CpuTuner` 将用户意图转换为 capability command，并先做范围校验。 |
| swap/ZRAM/compact | M5 | `MemoryTuner` 只允许声明式配置，写入前验证设备能力并保存回滚值。 |
| FPS session/jank | M8 | `FpsMonitor` 产生时间序列样本，C3 只保存新 schema。 |

KrScript 不在第一版直接执行；先把高价值能力收敛为有版本的 Kotlin command，脚本运行时留到后续版本。
