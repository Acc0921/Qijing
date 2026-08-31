# 第一版实现覆盖

这份文档只描述新产品的行为契约，不依赖任何旧数据格式。

| 能力 | 代码入口 | 当前状态 |
| --- | --- | --- |
| 特权执行与 dry-run | `core.execution.ExecutionBroker` | 已有统一接口和安全默认实现 |
| 只读 ADB | `core.execution.ReadOnlyAdbExecutionBroker` / `ProcessAdbTransport` | 固定命令映射和白名单；可注入真实 adb 进程传输，仍仅允许读取设备状态 |
| 设备能力探测 | `core.device.DeviceCapabilityProbe` / `BackendDetector` | 只读探测 CPU、内存、ZRAM、GPU 路径及后端可用性 |
| 场景应用事务 | `core.scene.SceneEngine` | 已将 CPU/内存意图转换为结构化 command，并写入任务日志 |
| 全新数据层 | `core.data.NewDataStore` / `SharedPreferencesNewDataStore` | 新 schema 版本边界、并发写入保护、损坏数据安全降级；设备、应用、场景、遥测均无旧数据迁移 API |
| 任务与错误记录 | `core.logging.TaskLogStore` / `SharedPreferencesTaskLogStore` | 持久化并发保护，最多保留 500 条并支持损坏数据降级和重启后读取 |
| 设备总览 | `feature.overview.OverviewPresenter` | 已有设备快照、应用数和场景数状态聚合，UI 使用持久化数据层 |
| 应用列表 | `feature.apps.ApplicationCatalog` / `AppListController` | 系统应用查询、搜索和筛选；展示版本/类型，点击应用可携带包名进入场景编辑 |
| 应用场景 | `feature.scene.SceneDraft` / `SceneDraftStore` | 已有表单校验、应用包名绑定、意图转换和持久化保存 |
| 场景选择 | `core.scene.SceneSelector` / `SceneActivationCoordinator` | 按前台包名、启用状态和优先级选择场景，并交给事务引擎执行 |
| 前台事件源 | `core.scene.UsageStatsForegroundAppSource` | 通过 Usage Stats 只读获取前台包名，未授权时安全降级 |
| 场景轮询 | `core.scene.ScenePollingLoop` | 可启动/停止、可配置间隔，仅在前台包名变化时发出事件 |
| 后台承载 | `core.scene.SceneTriggerService` / `SceneServiceControl` | UI 明确启动/停止，Usage Stats 权限可跳转设置；未授权时不启动轮询，停止时释放线程，当前使用 dry-run 引擎 |
| 场景快照 | `core.scene.SceneSnapshotManager` | 执行前读取已声明能力并生成 restore command |
| CPU 调节 | `feature.tuning.CpuTuner` / `CpuTuningController` | 已有参数范围校验、执行状态和 command 入口 |
| CPU 状态 | `feature.tuning.CpuStatusReader` | M4 页面只读显示核心在线数、governor、频率范围并支持刷新 |
| 内存与 ZRAM | `feature.tuning.MemoryTuner` / `MemoryTuningController` | 已有 swappiness/ZRAM 参数校验、执行状态和 command 入口 |
| 内存状态 | `feature.tuning.MemoryStatusReader` | M5 页面只读显示 MemTotal/MemAvailable、ZRAM 容量和压缩算法并支持刷新 |
| FPS 监控 | `feature.telemetry.WindowFpsCollector` / `FpsMonitor` / `FpsSessionAnalyzer` / `FpsCsvExporter` | Android 7+ 当前窗口 FrameMetrics 采集，1 秒聚合后持久化；支持历史列表、摘要回看和 CSV 系统分享；外部应用采集仍待兼容后端 |
| 新 UI | `MainActivity` | 已有可点击模块导航、M1 总览、M2 搜索筛选、M3 场景保存和 M4/M5/M8 状态页 |

下一阶段按此表逐项替换内存实现：先扩大只读采集覆盖和测试，再接入需要回滚快照的写入后端。
