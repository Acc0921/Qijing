# 第一版实现覆盖

这份文档只描述新产品的行为契约，不依赖任何旧数据格式。状态以当前仓库实现为准；“已有入口”不等于已经完成真实写入或真机验收。

| 能力 | 代码入口 | 当前状态 |
| --- | --- | --- |
| 特权执行与 dry-run | `core.execution.ExecutionBroker` / `BackendRuntimeFactory` | 默认 dry-run；用户可显式选择 Root 或 Shizuku，不可用时返回明确拒绝且不静默切换 |
| Root 后端 | `core.execution.RootExecutionBroker` / `ProcessSuTransport` | 固定支持 CPU governor/频率与 swappiness；参数白名单、超时、写后读回和稳定错误码已实现，待真机验收 |
| Shizuku 后端 | `core.execution.ShizukuExecutionBroker` / `ShizukuUserServiceTransport` | SDK、显式授权、Binder UserService、固定白名单和断连错误已实现；Android 7+ 可用，待真机验收 |
| Debug 调节模拟 | `debug.tuning.DebugTuningExecutionBroker` / `DebugRecoveryRunner` | 仅编入 debug；覆盖四项白名单能力、故障注入、journal 与重启恢复，release DEX/Manifest 隔离检查通过；不代表真实硬件可用 |
| 只读 ADB | `core.execution.ReadOnlyAdbExecutionBroker` / `ProcessAdbTransport` | 固定命令映射和白名单；可注入 adb 进程传输，仍仅允许读取设备状态，尚无 Android 端连接管理 |
| 设备能力探测 | `core.device.DeviceCapabilityProbe` / `BackendDetector` | 只读探测 CPU、内存、ZRAM、GPU 路径及后端可用性；尚未按具体后端验证写权限 |
| 场景应用事务 | `core.scene.SceneEngine` | 整批命令先预检；真实后端要求全部快照可读才开始写入，失败按已执行命令逆序回滚并记录日志 |
| 全新数据层 | `core.data.NewDataStore` / `SharedPreferencesNewDataStore` | 新 schema 版本边界、并发写入保护、损坏数据安全降级；设备、应用、场景、遥测均无旧数据迁移 API |
| 任务与错误记录 | `core.logging.TaskLogStore` / `SharedPreferencesTaskLogStore` | 持久化并发保护，最多保留 500 条，支持损坏数据降级和重启后读取 |
| 设备总览 | `feature.overview.OverviewPresenter` | 已有设备快照、应用数和场景数状态聚合，UI 使用持久化数据层 |
| 应用列表 | `feature.apps.ApplicationCatalog` / `AppListController` | 系统应用查询、搜索和筛选；展示版本/类型，点击应用可携带包名进入场景编辑 |
| 应用场景 | `feature.scene.SceneDraft` / `SceneDraftStore` | 已有表单校验、包名绑定、意图转换、持久化保存、列表启停和编辑 |
| 场景选择 | `core.scene.SceneSelector` / `SceneActivationCoordinator` | 按包名/优先级选择；记录活跃场景、同场景去重、切换前恢复、离开匹配应用后恢复 |
| 前台事件源 | `core.scene.UsageStatsForegroundAppSource` | 已通过 Usage Stats 只读获取前台包名；未授权时返回安全降级状态 |
| 场景轮询 | `core.scene.ScenePollingLoop` | 已支持启动、停止和可配置间隔，仅在前台包名变化时发出事件 |
| 后台承载 | `core.scene.SceneTriggerService` / `SceneServiceControl` | UI 可选择预览/Root/Shizuku、请求授权并启停；服务按选择装配后端，停止与事件源失效时先恢复活跃场景 |
| 场景快照 | `core.scene.SceneSnapshotManager` / `BrokerSceneRestoreExecutor` | CPU governor/频率及 swappiness 原值可生成恢复命令；缺任一快照则阻断写入，恢复按逆序执行；debug 持久化恢复已验证，正式后端仍待真机与跨进程恢复实现 |
| CPU 调节 | `feature.tuning.CpuTuner` / `CpuTuningController` | 已有参数范围校验、执行状态和 command 入口；尚无真实写入与读回验证 |
| CPU 状态 | `feature.tuning.CpuStatusReader` | M4 页面只读显示核心在线数、governor、频率范围并支持刷新 |
| 内存与 ZRAM | `feature.tuning.MemoryTuner` / `MemoryTuningController` | 已有 swappiness/ZRAM 参数校验、执行状态和 command 入口；尚无真实写入与恢复 |
| 内存状态 | `feature.tuning.MemoryStatusReader` | M5 页面只读显示 MemTotal/MemAvailable、ZRAM 容量和压缩算法并支持刷新 |
| FPS 当前窗口采集 | `feature.telemetry.WindowFpsCollector` / `FpsMonitor` | Android 7+ 使用 FrameMetrics 采集帧耗时，按 1 秒窗口聚合并持久化；尚待真机生命周期和性能验证 |
| FPS 分析与导出 | `feature.telemetry.FpsSessionAnalyzer` / `FpsCsvExporter` | 已支持平均/极值/P95/jank、历史列表、摘要回看和 CSV 系统分享；外部应用采集尚未实现 |
| 新 UI | `MainActivity` | 已有可点击模块导航、M1 总览、M2 搜索筛选、M3 场景保存和 M4/M5/M8 状态页；Compose UI 用例已编译但未在设备执行 |

## 覆盖边界

- “前台事件源”“场景轮询”“后台承载”已有实际 Android 实现，不再列为未实现项。
- “FPS 监控”已覆盖栖境自身窗口的 FrameMetrics 采集、历史和 CSV，不等同于外部游戏 FPS。
- `SceneSnapshotManager` 已接入服务与恢复触发；当前原值读取依赖普通只读 sysfs/proc 节点，设备不可读时会安全阻断，尚缺特权读取和跨进程恢复。
- M4/M5 的模型和 command 入口是实现基础，不构成可发布的调节功能。

## 下一阶段完成定义

下一阶段不再以“类或接口存在”为完成条件，而以 [后端与恢复验收标准](backend-acceptance.md) 为准：至少一个真实后端必须完成授权、能力判定、白名单执行、读回验证和恢复闭环；不支持的设备或权限状态必须安全拒绝且不产生部分写入。
