# 第一版实现覆盖

这份文档只描述新产品的行为契约，不依赖任何旧数据格式。

| 能力 | 代码入口 | 当前状态 |
| --- | --- | --- |
| 特权执行与 dry-run | `core.execution.ExecutionBroker` | 已有统一接口和安全默认实现 |
| 只读 ADB | `core.execution.ReadOnlyAdbExecutionBroker` | 固定命令映射和白名单，仅允许读取设备状态 |
| 设备能力探测 | `core.device.DeviceCapabilityProbe` / `BackendDetector` | 只读探测 CPU、内存、ZRAM、GPU 路径及后端可用性 |
| 场景应用事务 | `core.scene.SceneEngine` | 已将 CPU/内存意图转换为结构化 command，并写入任务日志 |
| 全新数据层 | `core.data.NewDataStore` | 已有设备、应用、场景、遥测新模型；无迁移 API |
| 任务与错误记录 | `core.logging.TaskLogStore` / `SharedPreferencesTaskLogStore` | 已有内存和持久化实现，最多保留 500 条并支持重启后读取 |
| 设备总览 | `feature.overview.OverviewPresenter` | 已有设备快照、应用数和场景数状态聚合，UI 使用持久化数据层 |
| 应用列表 | `feature.apps.ApplicationCatalog` / `AppListController` | 已有系统应用查询、搜索和系统应用筛选 |
| 应用场景 | `feature.scene.SceneDraft` / `SceneDraftStore` | 已有表单校验、应用包名绑定、意图转换和持久化保存 |
| 场景选择 | `core.scene.SceneSelector` / `SceneActivationCoordinator` | 按前台包名、启用状态和优先级选择场景，并交给事务引擎执行 |
| 前台事件源 | `core.scene.UsageStatsForegroundAppSource` | 通过 Usage Stats 只读获取前台包名，未授权时安全降级 |
| 场景轮询 | `core.scene.ScenePollingLoop` | 可启动/停止、可配置间隔，仅在前台包名变化时发出事件 |
| 场景快照 | `core.scene.SceneSnapshotManager` | 执行前读取已声明能力并生成 restore command |
| CPU 调节 | `feature.tuning.CpuTuner` / `CpuTuningController` | 已有参数范围校验、执行状态和 command 入口 |
| CPU 状态 | `feature.tuning.CpuStatusReader` | M4 页面只读显示核心在线数、governor、频率范围并支持刷新 |
| 内存与 ZRAM | `feature.tuning.MemoryTuner` / `MemoryTuningController` | 已有 swappiness/ZRAM 参数校验、执行状态和 command 入口 |
| 内存状态 | `feature.tuning.MemoryStatusReader` | M5 页面只读显示 MemTotal/MemAvailable、ZRAM 容量和压缩算法并支持刷新 |
| FPS 监控 | `feature.telemetry.FpsMonitor` / `FpsSessionAnalyzer` | 已有 session、采样、平均/极值/P95 frame time/jank 摘要 |
| 新 UI | `MainActivity` | 已有可点击模块导航、M1 总览、M2 搜索筛选、M3 场景保存和 M4/M5/M8 状态页 |

下一阶段按此表逐项替换内存实现：先扩大只读采集覆盖和测试，再接入需要回滚快照的写入后端。
