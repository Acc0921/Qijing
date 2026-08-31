# 第一版实现覆盖

这份文档只描述新产品的行为契约，不依赖任何旧数据格式。状态以当前仓库实现和已经完成的自动化/设备验收为准；真实特权写入未执行时，不以模拟器或接口存在替代真机结论。

| 能力 | 代码入口 | 当前状态 |
| --- | --- | --- |
| 特权执行与 dry-run | `core.execution.ExecutionBroker` / `BackendRuntimeFactory` | 默认 dry-run；用户可显式选择 Root 或 Shizuku，不可用时明确拒绝且不静默切换；切换后端会停用已启用场景，且自动化未安全停止时拒绝切换 |
| Root 后端 | `core.execution.RootExecutionBroker` / `ProcessSuTransport` | 固定支持 CPU governor/频率与 swappiness；参数白名单、超时、写后读回、稳定错误码和特权快照读取已实现；Root 只读握手已通过，尚未执行真机真实性能写入 |
| Shizuku 后端 | `core.execution.ShizukuExecutionBroker` / `ShizukuUserServiceTransport` | SDK、显式授权、Binder UserService、固定白名单、断连错误和特权快照读取已实现；Android 7+ 可用，尚未执行真机真实写入 |
| Debug 调节模拟 | `debug.tuning.DebugTuningExecutionBroker` / `DebugRecoveryRunner` | 仅编入 debug；覆盖四项白名单能力、故障注入、journal 与重启恢复，release DEX/Manifest 隔离检查通过；不代表真实硬件可用 |
| 只读 ADB | `core.execution.ReadOnlyAdbExecutionBroker` / `ProcessAdbTransport` | 固定命令映射和白名单；可注入 adb 进程传输，仍仅允许读取设备状态；Android 端不装配为写入后端 |
| 设备能力探测 | `core.device.DeviceCapabilityProbe` / `BackendDetector` / `PrivilegedReadCommandMapper` | 探测 CPU、内存、ZRAM、GPU 和后端状态；Root/Shizuku 可通过固定只读模板获取写入前原值，真实设备可写性仍以逐项执行验收为准 |
| 场景同源预演与事务 | `core.scene.SceneEngine.prepare` / `SceneEngine.apply` | `prepare` 与真实应用共用命令生成、白名单预检和快照逻辑，全程零写入；空计划和真实后端无快照读取能力均拒绝；`apply` 执行时重新准备，失败按已执行命令逆序回滚 |
| 全新数据层 | `core.data.NewDataStore` / `SharedPreferencesNewDataStore` | 新 schema、并发保护和损坏数据降级；应用、场景与遥测独立于旧数据，`priority`/`enabled` 可保真持久化；尚缺正式场景事务的跨进程恢复 journal |
| 任务与错误记录 | `core.logging.TaskLogStore` / `SharedPreferencesTaskLogStore` | 持久化并发保护，最多保留 500 条，记录预检、快照、执行与回滚结果；运行状态尚未形成页面内实时轨迹 |
| 设备总览 | `feature.overview.OverviewPresenter` / `ui.OverviewScreen` | 聚合设备、应用/场景数、后端选择、Usage Stats/自动化服务和最近任务；可直达场景与调节任务 |
| 应用列表 | `feature.apps.ApplicationCatalog` / `AppListController` / `ui.AppsScreen` | 查询真实安装应用并展示 PackageManager 图标、名称、包名、版本和应用类型；支持名称/包名、用户/系统、全部/已有场景/未配置筛选；点击时携带完整 `AppEntry` 进入工作台 |
| 场景链路工作台 | `ui.ScenesScreen` / `ui.SceneChainComponents` | 已按“应用→意图→优先级→预演→启用”组织编辑链路；预演绑定当前不可变场景及后端，异步旧结果会作废；普通保存不能启用，审批入口再次校验报告一致性 |
| 场景选择与生命周期 | `core.scene.SceneSelector` / `SceneActivationCoordinator` | 仅选择已启用场景并按包名/优先级决策；最高优先级并列时拒绝执行；同场景去重、切换前恢复、离场/停止/事件源失效恢复已实现 |
| 前台事件源 | `core.scene.UsageStatsForegroundAppSource` | 通过 Usage Stats 只读获取前台包名；未授权时安全降级并阻止无效轮询 |
| 场景轮询 | `core.scene.ScenePollingLoop` | 支持启动、停止和可配置间隔；每次有效采样都会协调一次，使同一前台应用下的停用/重新启用也能触发恢复或应用，协调器负责去重避免重复写入 |
| 后台承载 | `core.scene.SceneTriggerService` / `SceneServiceStateStore` | `STOPPED/RUNNING/STOPPING/RECOVERY_REQUIRED` 跨 Activity 持久化；停止等待恢复终态，失败或超时保留高危日志并锁住后端；仍缺原始快照的跨进程 journal |
| 场景快照与恢复 | `core.scene.SceneSnapshotManager` / `BrokerSceneRestoreExecutor` | Root/Shizuku 已使用特权只读模板建立 CPU governor/频率及 swappiness 快照；缺任一原值即零写入阻断，恢复按逆序执行；正式跨进程 journal 和真机真实写后恢复仍待完成 |
| CPU 手动调节 | `ui.TuningScreen` / `feature.tuning.CpuTuner` | 已有只读状态、设备可用 governor 约束、方案预览、原值快照、高风险确认、写后读回和失败恢复链路；dry-run/自动化已验证，真机真实写尚未执行 |
| 内存与 ZRAM 手动调节 | `ui.TuningScreen` / `feature.tuning.MemoryTuner` | 已有内存/ZRAM 只读状态及 swappiness 的范围校验、预览、快照、确认、读回和恢复链路；ZRAM 重建继续关闭，swappiness 真机真实写尚未执行 |
| FPS 当前窗口采集 | `feature.telemetry.WindowFpsCollector` / `FpsMonitor` / `ui.MonitorScreen` | Android 7+ 使用 FrameMetrics 按 1 秒窗口聚合并持久化；API 35 设备测试通过，API 23 明确显示不支持；仍不代表外部应用 FPS |
| FPS 分析与导出 | `feature.telemetry.FpsSessionAnalyzer` / `FpsCsvExporter` | 支持平均/极值/P95/jank、历史列表、摘要回看和 CSV 系统分享；外部应用采集不在当前实现内 |
| 五栏 UI | `MainActivity` / `ui.QijingApp` | 总览、应用、场景、调节、监控五栏以及亮/暗主题和专属组件已接入；完整设备套件已在 API 23 与 API 35 运行，8 项通过，Root-only 用例按模拟器条件跳过，不再是“仅编译”状态 |

## 已验证边界

- M2 已形成“找到应用→按任务筛选→携带完整应用上下文进入场景”的真实用户路径，图标由 PackageManager 读取，不使用占位字母作为正常路径。
- M3 的 `prepare` 预演和真实执行来自同一命令/快照管线；预演不调用写入。保存草稿不启用，编辑配置会取消启用并使旧预演失效。
- 同一应用的同优先级场景会在预演阶段阻断，不依赖内部 id 排序向用户伪装确定性。
- 服务状态不再使用 Compose 临时布尔值；运行、停止恢复和恢复未确认状态在重建页面后保持一致，只有 `STOPPED` 能切换后端。
- Root/Shizuku 已能通过特权 transport 读取快照，但尚未对真实 CPU 或 swappiness 执行写入、读回和恢复，因此不能标记为真机调节可用。
- M4/M5 不再只是只读卡片：手动安全调节链路已经接入；ZRAM 重建仍明确关闭。
- API 23/API 35 已完整运行设备 UI 测试；Root-only 条件跳过只说明模拟器没有 Root，不算失败，也不能作为 Root 写入证据。

## 下一阶段完成定义

当前剩余的发布关键链路集中在三项：

1. 将预演、命中、快照、应用、读回、恢复和异常以实时状态轨迹接入场景工作台。
2. 为正式场景事务持久化原始快照与未完成状态，完成进程被杀后的跨进程恢复 journal。
3. 在明确授权的真机上逐项执行 Root/Shizuku 真实写入、读回、离场恢复、失败回滚和撤权测试。

完成口径继续遵循 [后端与恢复验收标准](backend-acceptance.md) 与 [场景链路工作台规格](scene-chain-workbench.md)，不以 debug 模拟或模拟器通过替代真实硬件写入结论。
