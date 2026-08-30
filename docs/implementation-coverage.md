# 第一版实现覆盖

这份文档只描述新产品的行为契约，不依赖任何旧数据格式。

| 能力 | 代码入口 | 当前状态 |
| --- | --- | --- |
| 特权执行与 dry-run | `core.execution.ExecutionBroker` | 已有统一接口和安全默认实现 |
| 设备能力探测 | `core.device.DeviceCapabilityProbe` | 已有 Android 基础探测器 |
| 场景应用事务 | `core.scene.SceneEngine` | 已将 CPU/内存意图转换为结构化 command，并写入任务日志 |
| 全新数据层 | `core.data.NewDataStore` | 已有设备、应用、场景、遥测新模型；无迁移 API |
| 任务与错误记录 | `core.logging.TaskLogStore` | 已有内存实现，后续替换为持久化实现 |
| 设备总览 | `feature.overview.OverviewPresenter` | 已有设备快照、应用数和场景数状态聚合，UI 使用持久化数据层 |
| 应用列表 | `feature.apps.ApplicationCatalog` / `AppListController` | 已有系统应用查询、搜索和系统应用筛选 |
| 应用场景 | `feature.scene.SceneDraft` / `SceneDraftStore` | 已有表单校验、应用包名绑定、意图转换和持久化保存 |
| CPU 调节 | `feature.tuning.CpuTuner` / `CpuTuningController` | 已有参数范围校验、执行状态和 command 入口 |
| 内存与 ZRAM | `feature.tuning.MemoryTuner` / `MemoryTuningController` | 已有 swappiness/ZRAM 参数校验、执行状态和 command 入口 |
| FPS 监控 | `feature.telemetry.FpsMonitor` / `FpsSessionAnalyzer` | 已有 session、采样、平均/极值/P95 frame time/jank 摘要 |
| 新 UI | `MainActivity` | 已有可点击模块导航、M1 总览、M2 搜索筛选、M3 场景保存和 M4/M5/M8 状态页 |

下一阶段按此表逐项替换内存实现：先落地持久化 schema 和测试，再接入 root/ADB/Shizuku 后端，最后实现真实设备采集。
