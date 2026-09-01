# 第一版实现覆盖

这份文档只描述新产品的行为契约，不依赖任何旧数据格式。状态以当前仓库实现和已经完成的自动化/设备验收为准；真实特权写入未执行时，不以模拟器或接口存在替代真机结论。

| 能力 | 代码入口 | 当前状态 |
| --- | --- | --- |
| 特权执行与 dry-run | `core.execution.ExecutionBroker` / `BackendRuntimeFactory` | 默认 dry-run；用户可显式选择 Root 或 Shizuku，不可用时明确拒绝且不静默切换；切换后端会停用已启用场景，且自动化未安全停止时拒绝切换 |
| Root 后端 | `core.execution.RootExecutionBroker` / `ProcessSuTransport` | 固定支持逐 CPU policy 的 governor/频率、swappiness 与 Uperf/UperfGT/fas-rs 模式；参数、身份、路径和模式均为白名单，超时、写后读回、稳定错误码和特权快照读取已实现；Root 只读握手已通过，尚未执行真机真实性能写入 |
| Shizuku 后端 | `core.execution.ShizukuExecutionBroker` / `ShizukuUserServiceTransport` | SDK、显式授权、Binder UserService、固定白名单、断连错误和特权快照读取已实现；Android 7+ 可用，尚未执行真机真实写入 |
| Debug 调节模拟 | `debug.tuning.DebugTuningExecutionBroker` / `DebugRecoveryRunner` | 仅编入 debug；覆盖四项白名单能力、故障注入、journal 与重启恢复，release DEX/Manifest 隔离检查通过；不代表真实硬件可用 |
| 只读 ADB | `core.execution.ReadOnlyAdbExecutionBroker` / `ProcessAdbTransport` | 固定命令映射和白名单；可注入 adb 进程传输，仍仅允许读取设备状态；Android 端不装配为写入后端 |
| 设备能力探测 | `core.device.DeviceCapabilityProbe` / `core.device.observation` / `BackendDetector` / `PrivilegedReadCommandMapper` | 探测每个 CPU 核心与 policy、GPU、RAM、Swap、多 ZRAM 设备、电池电流/电压/温度/功率和后端状态；无法读取时区分未公开、权限不足、无效与采样中。Root/Shizuku 可通过固定模板获取写入前原值，真实设备可写性仍以逐项执行验收为准 |
| 场景同源预演与事务 | `core.scene.SceneEngine.prepare` / `SceneEngine.apply` | `prepare` 与真实应用共用命令生成、白名单预检和快照逻辑，全程零写入；真实事务在首次写入前同步落盘完整恢复计划，每条命令先记 `WRITE_STARTED` 再执行，失败按逆序回滚 |
| 全新数据层 | `core.data.NewDataStore` / `SharedPreferencesSceneTransactionJournalStore` | 应用、场景与遥测独立于旧数据；高频遥测采用追加式会话文件并摊销压缩，避免每秒重写完整历史；正式 journal 持久化场景、包名、后端、恢复命令和逐项阶段，损坏数据 fail-closed |
| 任务与错误记录 | `core.logging.TaskLogStore` / `TaskLogPresentation` / `SharedPreferencesSceneTaskEventStore` | 审计日志与结构化事件分离，最多各保留 500 条；内部能力标识保留为证据，界面转换为用户可读的预演、验证、恢复与异常语言，并可从总览直接打开完整任务记录 |
| 设备总览 | `feature.overview.OverviewPresenter` / `ui.OverviewScreen` | 聚合设备、应用/场景数、后端选择、Usage Stats/自动化服务和最近任务；Usage Stats 首次检查具有独立加载态，避免未确认前显示错误授权结论 |
| 应用列表 | `feature.apps.ApplicationCatalog` / `AppListController` / `ui.AppsScreen` | 查询真实安装应用并展示 PackageManager 图标、名称、包名、版本和应用类型；默认显示有桌面入口的触发对象，内部包位于专家范围且标明资格未知；扫描与图标解码在后台线程完成 |
| 场景链路工作台 | `ui.SceneEditorViewModel` / `SharedPreferencesSceneEditorStateStore` / `ui.ScenesScreen` / `ui.SceneChainComponents` | 已按“应用→意图→优先级→预演→启用”组织编辑链路；SavedStateHandle 与独立新版本草稿存储共同保留可编辑输入，栏目切换、系统返回、进程重建和用户强停后均可继续；能力探测与预演结果从不持久化，恢复后必须重新预演 |
| 场景选择与生命周期 | `core.scene.SceneSelector` / `SceneActivationCoordinator` | 仅选择已启用场景并按包名/优先级决策；最高优先级并列时拒绝执行；同场景去重、切换前恢复、离场/停止/事件源失效恢复已实现 |
| 前台事件源 | `core.scene.UsageStatsForegroundAppSource` | 通过 Usage Stats 只读获取前台包名；未授权时安全降级并阻止无效轮询 |
| 场景轮询 | `core.scene.ScenePollingLoop` | 支持启动、停止和可配置间隔；每次有效采样都会协调一次，使同一前台应用下的停用/重新启用也能触发恢复或应用，协调器负责去重避免重复写入 |
| 后台承载 | `core.scene.SceneTriggerService` / `SceneServiceStateStore` | 用户启动的自动化使用带明确用途的 `specialUse` FGS；具备 START_STICKY 安全重启、Android 15 超时入口、15 秒心跳和陈旧状态校准；异常退出锁定真实写入并依赖 journal 恢复，不在主线程阻塞等待 |
| 场景快照与恢复 | `core.scene.SceneSnapshotManager` / `BrokerSceneRestoreExecutor` | Root/Shizuku 使用特权只读模板建立 CPU governor/频率及 swappiness 快照；正常恢复与重启恢复逐项持久化进度，重复恢复保持幂等；真机真实写后恢复仍待授权验收 |
| 全局模式与第三方调度 | `feature.tuning.profile` / `core.scheduler` / `ui.TuningScreen` | 省电、均衡、性能、极速会按设备实际声明的 CPU policy 与 Governor 解析；可选择系统、Uperf、UperfGT 或 fas-rs 控制方，第三方只接受固定身份、固定路径、固定四档模式和读回验证。成功后保存撤销计划，跟随全局的场景会失效并要求重新预演 |
| CPU 手动调节与观察 | `ui.TuningScreen` / `core.device.observation.CpuObservationReader` | 展示逐 policy Governor/当前与硬件频率范围、关联核心，并展示每核频率、在线状态与负载；点击 policy 可从设备实际候选中自定义 Governor 与频率范围，再经过预览、快照、高风险确认、写后读回和失败恢复；真机真实写尚未执行 |
| GPU 只读观察 | `core.device.observation.GpuObservationReader` / `ui.TuningScreen` | 识别 KGSL、Mali 和通用 devfreq 固定节点，展示当前/范围频率、负载与 Governor；无可识别节点或无权限时明确降级，不开放 GPU 写入 |
| 内存、ZRAM 与功耗 | `core.device.observation.MemoryObservationReader` / `BatteryObservationReader` / `ui.TuningScreen` | 展示 RAM、Swap、全部 ZRAM 设备、压缩数据/内存占用/算法，以及电池电流、电压、温度和电池侧瞬时或估算功率；swappiness 具备范围校验、预览、快照、确认、读回和恢复链路。ZRAM 重建继续关闭，功率不冒充 CPU/GPU 分项数据 |
| FPS 当前窗口采集 | `feature.telemetry.WindowFpsCollector` / `FpsMonitor` / `ui.MonitorScreen` | Android 7+ 使用 FrameMetrics；渲染 FPS 以单调时钟真实经过时间计算，每个窗口批量保存逐帧耗时；界面明确静止窗口低渲染频率不等于卡顿，并引导结合帧耗时/P95 判断；API 23 明确显示不支持，且始终不代表外部应用 FPS |
| FPS 分析与导出 | `feature.telemetry.FpsSessionAnalyzer` / `FpsCsvExporter` | 新会话使用逐帧分布计算真实 P95；旧窗口级数据明确显示为“P95 窗口均值”；支持平均/极值/jank、历史、摘要和 CSV 系统分享 |
| 五栏 UI | `MainActivity` / `ui.QijingApp` | 五页使用 Navigation Compose 返回栈、edge-to-edge、稳定底部导航、任务型 Top App Bar、低阴影成组表面与 Bottom Sheet；总览状态核心、应用连续列表、场景对象、调节分段数据组和监测会话面板采用同一观感语言，并为系统字体放大提供收敛/重排路径；系统返回与对象编辑层级已进入自动化回归 |

## 已验证边界

- M2 已形成“找到应用→按任务筛选→携带完整应用上下文进入场景”的真实用户路径，图标由 PackageManager 读取，不使用占位字母作为正常路径。
- M3 的 `prepare` 预演和真实执行来自同一命令/快照管线；预演不调用写入。保存草稿不启用，编辑配置会取消启用并使旧预演失效。
- 同一应用的同优先级场景会在预演阶段阻断，不依赖内部 id 排序向用户伪装确定性。
- 服务状态不再使用 Compose 临时布尔值；运行、停止恢复和恢复未确认状态在重建页面后保持一致，只有 `STOPPED` 能切换后端。
- Root/Shizuku 已能通过特权 transport 读取快照，但尚未对真实 CPU 或 swappiness 执行写入、读回和恢复，因此不能标记为真机调节可用。
- M4/M5 已补齐全局四档模式、逐 policy 自定义、第三方固定调度契约、每核 CPU/GPU/内存/ZRAM/电池侧功耗观察与手动安全调节；ZRAM、GPU 与核心上下线写入仍明确关闭。
- API 23/API 35 模拟器均完成 15 项设备测试且 0 失败，Root-only 用例按设备条件跳过；JVM 112 项通过。模拟器条件跳过不能作为 Root 写入证据。

## 下一阶段完成定义

当前代码侧的实时轨迹与正式跨进程 journal 已完成，剩余发布关键链路转为设备验收：

1. 在明确授权的真机上分别执行 Root 与 Shizuku 的真实写入、读回、离场恢复、失败回滚和撤权测试。
2. 通过进程中断与部分恢复故障注入，人工核对总览/场景轨迹、通知和后端锁定是否一致。
3. 完成长时 FPS 会话、TalkBack、200% 字体、深浅色与小屏人工走查。
4. 若选择 Google Play 分发，提交 `specialUse` 前台服务与 `QUERY_ALL_PACKAGES` 的政策申报；GitHub 侧载不替代商店审核。

完成口径继续遵循 [后端与恢复验收标准](backend-acceptance.md) 与 [场景链路工作台规格](scene-chain-workbench.md)，不以 debug 模拟或模拟器通过替代真实硬件写入结论。
