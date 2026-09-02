package com.qijing.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.qijing.core.data.NewDataStore
import com.qijing.core.device.observation.AndroidBatteryPlatformSource
import com.qijing.core.device.observation.BatteryObservation
import com.qijing.core.device.observation.BatteryObservationReader
import com.qijing.core.device.observation.CpuObservation
import com.qijing.core.device.observation.CpuObservationReader
import com.qijing.core.device.observation.CpuPolicyObservation
import com.qijing.core.device.observation.GpuObservation
import com.qijing.core.device.observation.GpuObservationReader
import com.qijing.core.device.observation.MemoryObservation
import com.qijing.core.device.observation.MemoryObservationReader
import com.qijing.core.device.observation.MetricStatus
import com.qijing.core.device.observation.ObservedMetric
import com.qijing.core.execution.BackendPreference
import com.qijing.core.execution.BackendRuntimeFactory
import com.qijing.core.logging.SharedPreferencesTaskLogStore
import com.qijing.core.model.CpuIntent
import com.qijing.core.model.CpuPolicyIntent
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.model.MemoryIntent
import com.qijing.core.model.SceneProfile
import com.qijing.core.scheduler.FasRsSchedulerAdapter
import com.qijing.core.scheduler.ConfigBridgeSchedulerAdapter
import com.qijing.core.scheduler.PrivilegedSchedulerProbe
import com.qijing.core.scheduler.SchedulerAvailability
import com.qijing.core.scheduler.SchedulerMode
import com.qijing.core.scheduler.SchedulerProbeResult
import com.qijing.core.scheduler.SchedulerProviderId
import com.qijing.core.scheduler.pack.AndroidSchedulerDeviceProbe
import com.qijing.core.scheduler.pack.SchedulerPackImportResult
import com.qijing.core.scheduler.pack.SchedulerPackImporter
import com.qijing.core.scheduler.pack.SchedulerPackLoad
import com.qijing.core.scheduler.pack.SchedulerPackStore
import com.qijing.core.scheduler.pack.SchedulerPackCompatibility
import com.qijing.core.scheduler.pack.SchedulerPackVariant
import com.qijing.core.scheduler.profile.InstalledProfileSceneExpander
import com.qijing.core.scheduler.profile.ProfileCompileResult
import com.qijing.core.scheduler.profile.ProfileCompiler
import com.qijing.core.scheduler.UperfGtSchedulerAdapter
import com.qijing.core.scheduler.UperfSchedulerAdapter
import com.qijing.core.scene.CapabilityValueReader
import com.qijing.core.scene.CommandValueReader
import com.qijing.core.scene.SceneEngine
import com.qijing.core.scene.SceneCommandExpander
import com.qijing.core.scene.SceneCommandExpansion
import com.qijing.core.scene.SceneSnapshotManager
import com.qijing.core.scene.SceneServicePhase
import com.qijing.core.scene.SceneServiceStateStore
import com.qijing.core.scene.SharedPreferencesSceneTransactionJournalStore
import com.qijing.feature.tuning.profile.GlobalTuningConfiguration
import com.qijing.feature.tuning.profile.GlobalAutomationApprovalStore
import com.qijing.feature.tuning.profile.GlobalTuningLoad
import com.qijing.feature.tuning.profile.GlobalTuningResolution
import com.qijing.feature.tuning.profile.GlobalTuningResolver
import com.qijing.feature.tuning.profile.GlobalTuningRecoveryLoad
import com.qijing.feature.tuning.profile.GlobalTuningRecoveryPlan
import com.qijing.feature.tuning.profile.SharedPreferencesGlobalTuningRecoveryStore
import com.qijing.feature.tuning.profile.SharedPreferencesGlobalTuningProfileStore
import com.qijing.feature.tuning.profile.TuningProfileReference
import com.qijing.feature.tuning.profile.displayName
import com.qijing.feature.tuning.profile.commitVerifiedGlobalTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

private enum class TuningTab { CPU, GPU, MEMORY, POWER }

private data class PendingTune(
    val label: String,
    val before: String,
    val after: String,
    val cpu: CpuIntent = CpuIntent(),
    val memory: MemoryIntent = MemoryIntent(),
    val provider: SchedulerProviderId = SchedulerProviderId.SYSTEM,
    val mode: SchedulerMode? = null,
    val globalConfiguration: GlobalTuningConfiguration? = null,
    val warnings: List<String> = emptyList(),
    val recoveryPlan: GlobalTuningRecoveryPlan? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TuningScreen(dataStore: NewDataStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backend = remember(context) { BackendPreference(context).selected() }
    val cpuReader = remember { CpuObservationReader() }
    val memoryReader = remember { MemoryObservationReader() }
    val gpuReader = remember { GpuObservationReader() }
    val batteryReader = remember(context) { BatteryObservationReader(AndroidBatteryPlatformSource(context)) }
    val profileStore = remember(context) { SharedPreferencesGlobalTuningProfileStore(context) }
    val globalApprovalStore = remember(context) { GlobalAutomationApprovalStore(context) }
    val packStore = remember(context) { SchedulerPackStore(context) }
    val recoveryStore = remember(context) { SharedPreferencesGlobalTuningRecoveryStore(context) }
    var savedGlobal by remember(profileStore) { mutableStateOf(loadGlobal(profileStore)) }
    var draftGlobal by remember(savedGlobal) { mutableStateOf(savedGlobal) }
    var recoveryPlan by remember(recoveryStore) {
        mutableStateOf((recoveryStore.load() as? GlobalTuningRecoveryLoad.Loaded)?.plan)
    }
    var tab by remember { mutableStateOf(TuningTab.CPU) }
    var cpu by remember { mutableStateOf<CpuObservation?>(null) }
    var memory by remember { mutableStateOf<MemoryObservation?>(null) }
    var gpu by remember { mutableStateOf<GpuObservation?>(null) }
    var battery by remember { mutableStateOf<BatteryObservation?>(null) }
    var probes by remember { mutableStateOf<List<SchedulerProbeResult>>(emptyList()) }
    var showModeSheet by remember { mutableStateOf(false) }
    var showProviderSheet by remember { mutableStateOf(false) }
    var showPackSheet by remember { mutableStateOf(false) }
    val modeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var packLoad by remember(packStore) { mutableStateOf(packStore.load()) }
    var editingPolicy by remember { mutableStateOf<CpuPolicyObservation?>(null) }
    var policyGovernor by remember { mutableStateOf("") }
    var policyRangeMHz by remember { mutableStateOf(0f..0f) }
    var pending by remember { mutableStateOf<PendingTune?>(null) }
    var applying by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var resultError by remember { mutableStateOf(false) }
    var refreshToken by remember { mutableStateOf(0) }
    val packPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            applying = true
            val outcome = withContext(Dispatchers.IO) {
                val imported = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { SchedulerPackImporter().import(it) }
                        ?: SchedulerPackImportResult.Rejected(
                            com.qijing.core.scheduler.pack.SchedulerPackRejectReason.INVALID_ZIP,
                            "无法读取所选文件"
                        )
                }.getOrElse {
                    SchedulerPackImportResult.Rejected(
                        com.qijing.core.scheduler.pack.SchedulerPackRejectReason.INVALID_ZIP,
                        it.message ?: "配置包读取失败"
                    )
                }
                when (imported) {
                    is SchedulerPackImportResult.Rejected -> imported.detail to false
                    is SchedulerPackImportResult.Imported -> {
                        val compileFailure = imported.pack.variants.firstNotNullOfOrNull { variant ->
                            when (val compiled = ProfileCompiler().compile(variant.profileJson, variant.imports)) {
                                is ProfileCompileResult.Compiled -> null
                                is ProfileCompileResult.Rejected -> "${variant.relativePath}：${compiled.reason}"
                            }
                        }
                        when {
                            compileFailure != null -> "配置 profile 未通过编译：$compileFailure" to false
                            !packStore.install(imported.pack) -> "配置包无法保存到应用私有目录" to false
                            else -> {
                                disableProfileScenes(dataStore)
                                "已导入 ${imported.pack.module.name}；原配置场景已停用，请选择变体后重新预演" to true
                            }
                        }
                    }
                }
            }
            packLoad = packStore.load()
            resultMessage = outcome.first
            resultError = !outcome.second
            showPackSheet = outcome.second
            applying = false
        }
    }

    LaunchedEffect(refreshToken, backend) {
        var cycle = 0
        while (isActive) {
            val values = withContext(Dispatchers.IO) {
                val cpuValue = cpuReader.read()
                val memoryValue = if (cycle % 2 == 0) memoryReader.read() else null
                val gpuValue = gpuReader.read()
                val batteryValue = if (cycle % 5 == 0) batteryReader.read() else null
                val schedulerValues = if (cycle % 5 == 0) probeSchedulers(context, backend) else null
                listOf(cpuValue, memoryValue, gpuValue, batteryValue, schedulerValues)
            }
            cpu = values[0] as CpuObservation
            (values[1] as? MemoryObservation)?.let { memory = it }
            gpu = values[2] as GpuObservation
            (values[3] as? BatteryObservation)?.let { battery = it }
            @Suppress("UNCHECKED_CAST")
            (values[4] as? List<SchedulerProbeResult>)?.let { probes = it }
            cycle += 1
            delay(1_000)
        }
    }

    LazyColumn(
        modifier = Modifier.testTag("tuning-list"),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            QijingTopAppBar(
                title = "调节",
                subtitle = "先观察，再选择目标并安全预演",
                accent = QijingAmber,
                actions = {
                    IconButton(onClick = { refreshToken += 1 }) { Icon(Icons.Rounded.Refresh, "刷新设备状态") }
                }
            )
        }
        item {
            QijingSurfaceGroup(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                PageSectionHeader("全局调节", "场景可跟随这套默认意图", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                NativeListRow(
                    title = "全局模式",
                    supporting = when {
                        draftGlobal != savedGlobal -> "尚未预演和保存"
                        !savedGlobal.selectionKnown -> "设备已恢复旧值，但旧恢复计划未记录对应模式；请重新选择并预演"
                        else -> "当前保存的设备默认意图"
                    },
                    status = if (draftGlobal == savedGlobal && !savedGlobal.selectionKnown) "未知" else draftGlobal.selected.displayName(),
                    modifier = Modifier.testTag("global-mode-row"),
                    onClick = { showModeSheet = true }
                )
                HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                val selectedProbe = probes.firstOrNull { it.provider == draftGlobal.provider }
                NativeListRow(
                    title = "调度控制方",
                    supporting = selectedProbe?.detail ?: if (draftGlobal.provider == SchedulerProviderId.SYSTEM) "栖境结构化白名单" else "正在探测固定契约",
                    status = if (draftGlobal == savedGlobal && !savedGlobal.selectionKnown) "未知" else draftGlobal.provider.displayName(),
                    modifier = Modifier.testTag("scheduler-provider-row"),
                    onClick = { showProviderSheet = true }
                )
                HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                NativeListRow(
                    title = "执行方式",
                    supporting = if (backend == ExecutionBackend.DRY_RUN) "只预演，不修改设备" else "应用前读取快照并生成恢复计划",
                    status = backend.displayName()
                )
                Button(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("global-mode-preview"),
                    enabled = cpu != null && !applying,
                    onClick = {
                        val resolution = GlobalTuningResolver().resolve(draftGlobal, cpu!!)
                        when (resolution) {
                            is GlobalTuningResolution.Blocked -> {
                                resultMessage = resolution.reason
                                resultError = true
                            }
                            is GlobalTuningResolution.Ready -> {
                                val target = resolution.target
                                pending = PendingTune(
                                    label = "全局${target.label}模式",
                                    before = "${savedGlobal.selected.displayName()} · ${savedGlobal.provider.displayName()}",
                                    after = "${target.label} · ${target.provider.displayName()}",
                                    cpu = target.cpu,
                                    memory = target.memory,
                                    provider = target.provider,
                                    mode = target.mode,
                                    globalConfiguration = draftGlobal,
                                    warnings = target.warnings
                                )
                                resultMessage = null
                            }
                        }
                    }
                ) { Text("预览全局模式") }
                recoveryPlan?.let { recovery ->
                    NativeListRow(
                        title = "恢复上次调节",
                        supporting = recovery.previousConfiguration?.let {
                            "${recovery.backend.displayName()} · 恢复为 ${it.selected.displayName()} / ${it.provider.displayName()}"
                        } ?: "${recovery.backend.displayName()} · 旧计划未记录对应全局模式，恢复后将标记为未知",
                        status = "预演 ›",
                        onClick = {
                            if (backend != ExecutionBackend.DRY_RUN && backend != recovery.backend) {
                                resultMessage = "恢复计划属于 ${recovery.backend.displayName()}，当前执行方式不一致"
                                resultError = true
                            } else {
                                pending = PendingTune(
                                    label = "恢复上次调节",
                                    before = if (savedGlobal.selectionKnown) savedGlobal.selected.displayName() else "当前模式未知",
                                    after = recovery.previousConfiguration?.let {
                                        "${it.selected.displayName()} · ${it.provider.displayName()}"
                                    } ?: "恢复系统原值；对应全局模式未知",
                                    recoveryPlan = recovery
                                )
                            }
                        }
                    )
                }
            }
        }
        item {
            val installed = (packLoad as? SchedulerPackLoad.Loaded)?.value
            val selected = installed?.selectedVariant
            val device = remember(packLoad) { AndroidSchedulerDeviceProbe.probe() }
            QijingSurfaceGroup(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                PageSectionHeader("配置调度引擎", "导入 Scene 兼容配置并绑定当前设备", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                NativeListRow(
                    title = "配置包",
                    supporting = installed?.pack?.module?.let { "${it.name} · ${it.version.orEmpty()}" }
                        ?: "仅导入声明式 JSON，不执行压缩包脚本",
                    status = if (installed == null) "导入 ›" else "更换 ›",
                    onClick = {
                        if (SceneServiceStateStore(context).current().phase != SceneServicePhase.STOPPED) {
                            resultMessage = "请先停止自动化并完成恢复，再更换配置包"
                            resultError = true
                        } else {
                            packPicker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
                        }
                    }
                )
                HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                NativeListRow(
                    title = "设备变体",
                    supporting = selected?.relativePath
                        ?: if (installed == null) "先导入配置包" else "必须通过 SoC、核心集合与簇拓扑检查",
                    status = when {
                        selected != null -> "已匹配 ›"
                        installed != null -> "选择 ›"
                        else -> "未配置"
                    },
                    onClick = if (installed != null) ({ showPackSheet = true }) else null
                )
                if (selected != null) {
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    NativeListRow(
                        title = "运行范围",
                        supporting = "四档模式 · 应用/游戏路由 · CPU/GPU/cpuset/线程规则",
                        status = if (selected.compatibilityWith(device).compatible) "可预演" else "设备不匹配"
                    )
                }
            }
        }
        item {
            QijingSegmentedControl(
                options = listOf(
                    QijingSegmentOption("CPU", "module-M4"),
                    QijingSegmentOption("GPU", "tuning-tab-gpu"),
                    QijingSegmentOption("内存", "module-M5"),
                    QijingSegmentOption("功耗", "tuning-tab-power")
                ),
                selectedIndex = tab.ordinal,
                onSelected = { tab = TuningTab.entries[it] },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        item {
            when (tab) {
                TuningTab.CPU -> CpuObservationPanel(cpu, applying) { policy ->
                    editingPolicy = policy
                    policyGovernor = policy.governor.value.orEmpty()
                    val min = policy.scalingMinFrequencyKHz.value ?: policy.hardwareMinFrequencyKHz.value ?: 0L
                    val max = policy.scalingMaxFrequencyKHz.value ?: policy.hardwareMaxFrequencyKHz.value ?: min
                    policyRangeMHz = (min / 1000f)..(max / 1000f)
                }
                TuningTab.GPU -> GpuObservationPanel(gpu)
                TuningTab.MEMORY -> MemoryObservationPanel(memory, applying) { swappiness ->
                    pending = PendingTune(
                        "内存回收倾向",
                        memory?.swappiness?.display() ?: "读取受限",
                        swappiness.toString(),
                        memory = MemoryIntent(swappiness = swappiness)
                    )
                }
                TuningTab.POWER -> BatteryObservationPanel(battery)
            }
        }
        resultMessage?.let { message ->
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (resultError) Icons.Rounded.Security else Icons.Rounded.Check, null, tint = if (resultError) MaterialTheme.colorScheme.error else QijingMint)
                    Text(message, color = if (resultError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PageSectionHeader("安全边界", "第三方调度也不能绕过事务")
                Text("• 系统参数按 CPU policy 分别快照、写入和读回", style = MaterialTheme.typography.bodyMedium)
                Text("• Uperf/fas-rs 只接受固定身份、固定模式和固定路径", style = MaterialTheme.typography.bodyMedium)
                Text("• Uperf/UperfGT/fas-rs 仅使用固定模式接口与读回验证", style = MaterialTheme.typography.bodyMedium)
                Text("• 配置包只读取声明式 JSON；压缩包脚本不会被解压或执行", style = MaterialTheme.typography.bodyMedium)
                Text("• 节点写入、刷新率和线程动作都必须完成读回验证", style = MaterialTheme.typography.bodyMedium)
                Text("• 温控移除、分区修改和配置外任意 Shell 不进入执行链", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showPackSheet) {
        val installed = (packLoad as? SchedulerPackLoad.Loaded)?.value
        val device = remember(packLoad) { AndroidSchedulerDeviceProbe.probe() }
        val rankedVariants = remember(installed, device) {
            installed?.pack?.variants.orEmpty()
                .map { variant -> variant to variant.compatibilityWith(device) }
                .sortedWith(
                    compareByDescending<Pair<SchedulerPackVariant, SchedulerPackCompatibility>> {
                        it.second.compatible
                    }.thenBy { it.first.categoryPath.joinToString(" · ") }
                )
        }
        ModalBottomSheet(onDismissRequest = { showPackSheet = false }) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item {
                    Text("选择设备变体", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
                }
                if (installed == null) item {
                    Text("尚未导入配置包", modifier = Modifier.padding(horizontal = 20.dp))
                } else {
                    item {
                        val compatibleCount = rankedVariants.count { it.second.compatible }
                        Text(
                            if (compatibleCount > 0) "已优先显示 $compatibleCount 个匹配当前设备的变体"
                            else "没有变体通过当前设备的 SoC、平台与核心拓扑检查",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }
                    items(rankedVariants, key = { it.first.id }) { (variant, compatibility) ->
                        NativeListRow(
                            title = variant.categoryPath.joinToString(" · ").ifBlank { variant.relativePath },
                            supporting = if (compatibility.compatible) {
                                "${variant.hardware.topology.canonical} 核心拓扑 · 可用于当前设备"
                            } else {
                                "不可用：${compatibility.mismatches.joinToString()}"
                            },
                            onClick = if (compatibility.compatible) ({
                                if (SceneServiceStateStore(context).current().phase != SceneServicePhase.STOPPED) {
                                    resultMessage = "请先停止自动化并完成恢复，再更换设备变体"
                                    resultError = true
                                    return@NativeListRow
                                }
                                val saved = packStore.selectVariant(variant.id, device)
                                if (saved) disableProfileScenes(dataStore)
                                packLoad = packStore.load()
                                resultMessage = if (saved) "已选择 ${variant.categoryPath.joinToString(" · ")}" else "设备变体保存失败"
                                resultError = !saved
                                if (saved) showPackSheet = false
                            }) else null,
                            trailing = { RadioButton(installed.selectedVariantId == variant.id, null, enabled = compatibility.compatible) }
                        )
                    }
                }
            }
        }
    }

    if (showModeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showModeSheet = false },
            sheetState = modeSheetState
        ) {
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item { Text("选择全局模式", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp)) }
                items(SchedulerMode.entries, key = { it.name }) { mode ->
                    NativeListRow(
                        title = mode.displayName(),
                        supporting = mode.description(),
                        modifier = Modifier.testTag("global-mode-${mode.name}"),
                        onClick = {
                            draftGlobal = draftGlobal.copy(
                                selected = TuningProfileReference.BuiltIn(mode),
                                selectionKnown = true
                            )
                            showModeSheet = false
                        },
                        trailing = { RadioButton((draftGlobal.selected as? TuningProfileReference.BuiltIn)?.mode == mode, null) }
                    )
                }
            }
        }
    }

    if (showProviderSheet) {
        ModalBottomSheet(onDismissRequest = { showProviderSheet = false }) {
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item { Text("选择调度控制方", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp)) }
                items(SchedulerProviderId.entries, key = { it.name }) { provider ->
                    val probe = probes.firstOrNull { it.provider == provider }
                    val profileReady = provider == SchedulerProviderId.QIJING_PROFILE &&
                        (packLoad as? SchedulerPackLoad.Loaded)?.value?.selectedVariant != null
                    val available = provider == SchedulerProviderId.SYSTEM || profileReady ||
                        probe?.availability in setOf(SchedulerAvailability.READY, SchedulerAvailability.DEGRADED)
                    NativeListRow(
                        title = provider.displayName(),
                        supporting = probe?.detail ?: if (provider == SchedulerProviderId.SYSTEM) "按栖境白名单调节 CPU 与内存" else "当前执行方式未检测到",
                        onClick = if (available) ({
                            draftGlobal = draftGlobal.copy(provider = provider)
                            showProviderSheet = false
                        }) else null,
                        trailing = { RadioButton(draftGlobal.provider == provider, null, enabled = available) }
                    )
                }
            }
        }
    }

    editingPolicy?.let { policy ->
        val hardwareMin = policy.hardwareMinFrequencyKHz.value
        val hardwareMax = policy.hardwareMaxFrequencyKHz.value
        ModalBottomSheet(onDismissRequest = { editingPolicy = null }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("${policy.id} 自定义调节", style = MaterialTheme.typography.titleLarge)
                Text("作用核心 ${policy.relatedCores.sorted().joinToString()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                policy.availableGovernors.value.orEmpty().sorted().forEach { governor ->
                    NativeListRow(
                        title = governor,
                        supporting = if (governor == policy.governor.value) "当前策略" else "设备声明可用",
                        onClick = { policyGovernor = governor },
                        trailing = { RadioButton(policyGovernor == governor, { policyGovernor = governor }) }
                    )
                }
                if (hardwareMin != null && hardwareMax != null && hardwareMax > hardwareMin) {
                    Text("频率范围 ${policyRangeMHz.start.roundToLong()}–${policyRangeMHz.endInclusive.roundToLong()} MHz")
                    RangeSlider(
                        value = policyRangeMHz,
                        onValueChange = { policyRangeMHz = it },
                        valueRange = (hardwareMin / 1000f)..(hardwareMax / 1000f)
                    )
                    Text("设备范围 ${hardwareMin / 1000}–${hardwareMax / 1000} MHz", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = policyGovernor.isNotBlank() && !applying,
                    onClick = {
                        val id = policy.id.removePrefix("policy").toIntOrNull() ?: return@Button
                        val intent = CpuPolicyIntent(
                            id,
                            policyGovernor,
                            policyRangeMHz.start.roundToLong() * 1000,
                            policyRangeMHz.endInclusive.roundToLong() * 1000
                        )
                        pending = PendingTune(
                            "${policy.id} 自定义策略",
                            "${policy.governor.display()} · ${policy.scalingMinFrequencyKHz.displayFrequency()}–${policy.scalingMaxFrequencyKHz.displayFrequency()}",
                            "$policyGovernor · ${intent.minFrequencyKHz?.div(1000)}–${intent.maxFrequencyKHz?.div(1000)} MHz",
                            cpu = CpuIntent(policies = listOf(intent))
                        )
                        editingPolicy = null
                    }
                ) { Text("预览此策略域") }
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = { editingPolicy = null }) { Text("取消") }
            }
        }
    }

    pending?.let { plan ->
        ModalBottomSheet(onDismissRequest = { if (!applying) pending = null }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(if (backend == ExecutionBackend.DRY_RUN) "调节预演" else "确认特权调节", style = MaterialTheme.typography.titleLarge)
                Text("${plan.label} · ${backend.displayName()}", color = if (backend == ExecutionBackend.DRY_RUN) QijingBlue else QijingAmber)
                NativeListRow(title = "原值", supporting = plan.before)
                NativeListRow(title = "目标值", supporting = plan.after, status = "待执行")
                plan.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(
                    if (backend == ExecutionBackend.DRY_RUN) "只记录同源命令计划，不修改系统。" else "执行阶段：快照 → 写入 → 读回验证；失败时按逆序恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (backend == ExecutionBackend.DRY_RUN) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
                Button(modifier = Modifier.fillMaxWidth(), enabled = !applying, onClick = {
                    applying = true
                    scope.launch {
                        val result = applyManualTune(context, backend, plan, recoveryStore, savedGlobal)
                        var message = result.first
                        var success = result.second
                        if (success && backend != ExecutionBackend.DRY_RUN && plan.recoveryPlan != null) {
                            dataStore.scenes().filter { it.enabled && it.followsGlobalProfile }
                                .forEach { dataStore.saveScene(it.copy(enabled = false)) }
                            val restored = profileStore.restoreAfterVerifiedRecovery(
                                plan.recoveryPlan.previousConfiguration
                            )
                            if (restored != null) {
                                globalApprovalStore.clear()
                                savedGlobal = restored
                                draftGlobal = restored
                                message += if (restored.selectionKnown) {
                                    " 全局模式已同步切回 ${restored.selected.displayName()}；跟随场景已停用，需重新预演。"
                                } else {
                                    " 系统原值已恢复；旧计划未记录对应模式，当前全局状态已标记为未知；跟随场景已停用，请重新选择并预演。"
                                }
                            } else {
                                success = false
                                message += " 系统原值已恢复，但全局配置状态无法持久化；跟随场景已停用，请勿继续自动化。"
                            }
                        }
                        if (success && plan.globalConfiguration != null) {
                            globalApprovalStore.clear()
                            dataStore.scenes().filter { it.enabled && it.followsGlobalProfile }
                                .forEach { dataStore.saveScene(it.copy(enabled = false)) }
                            val saved = saveGlobal(profileStore, savedGlobal, plan.globalConfiguration)
                            if (saved != null) {
                                if (!globalApprovalStore.approve(saved, backend)) {
                                    success = false
                                    message += " 全局模式已保存，但自动化批准记录无法持久化；请勿启动自动化。"
                                }
                                savedGlobal = saved
                                draftGlobal = saved
                                message += if (backend == ExecutionBackend.DRY_RUN) " 全局意图已保存；跟随场景需重新预演。" else " 全局模式已保存；跟随场景需重新预演。"
                            } else {
                                success = false
                                message += " 全局配置保存失败，请勿继续自动化。"
                            }
                        }
                        resultMessage = message
                        resultError = !success
                        recoveryPlan = (recoveryStore.load() as? GlobalTuningRecoveryLoad.Loaded)?.plan
                        applying = false
                        pending = null
                        refreshToken += 1
                    }
                }) { Text(if (applying) "执行中…" else if (backend == ExecutionBackend.DRY_RUN) "确认预览" else "确认并应用") }
                TextButton(modifier = Modifier.fillMaxWidth(), enabled = !applying, onClick = { pending = null }) { Text("取消") }
            }
        }
    }
}

@Composable
private fun CpuObservationPanel(value: CpuObservation?, applying: Boolean, onEdit: (CpuPolicyObservation) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        PageSectionHeader("CPU 策略域", "频率、Governor 与核心归属来自实时采样", Modifier.padding(16.dp, 8.dp))
        if (value == null) {
            QijingSurfaceGroup(Modifier.padding(horizontal = 16.dp)) { NativeListRow("CPU", "正在读取设备状态", "采样中") }
            return@Column
        }
        QijingSurfaceGroup(Modifier.padding(horizontal = 16.dp)) {
            value.policies.forEachIndexed { index, policy ->
                NativeListRow(
                    title = "${policy.id} · 核心 ${policy.relatedCores.sorted().joinToString()}",
                    supporting = "${policy.governor.display()} · 限制 ${policy.scalingMinFrequencyKHz.displayFrequency()}–${policy.scalingMaxFrequencyKHz.displayFrequency()}",
                    status = policy.currentFrequencyKHz.displayFrequency(),
                    onClick = if (applying) null else ({ onEdit(policy) }),
                    leading = { QijingIconTile(QijingMint) { Icon(Icons.Rounded.Speed, null) } }
                )
                if (index != value.policies.lastIndex) HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        PageSectionHeader("每核状态", "负载需要连续两次 /proc/stat 采样", Modifier.padding(16.dp, 12.dp))
        QijingSurfaceGroup(Modifier.padding(horizontal = 16.dp)) {
            value.cores.forEachIndexed { index, core ->
                NativeListRow(
                    title = "CPU ${core.id}",
                    supporting = "${core.policyId ?: "未关联策略域"} · ${core.currentFrequencyKHz.displayFrequency()}",
                    status = when {
                        core.online.value == false -> "离线"
                        core.loadPercent.status == MetricStatus.SAMPLING -> "采样中"
                        core.loadPercent.value != null -> "%.0f%%".format(core.loadPercent.value)
                        else -> core.loadPercent.status.displayName()
                    },
                    leading = { QijingIconTile(if (core.online.value == false) MaterialTheme.colorScheme.onSurfaceVariant else QijingBlue) { Icon(Icons.Rounded.DeveloperBoard, null) } }
                )
                if (index != value.cores.lastIndex) HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun GpuObservationPanel(value: GpuObservation?) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        PageSectionHeader("GPU 实时状态", "只读观察，不开放 GPU 写入", Modifier.padding(16.dp, 8.dp))
        if (value == null) {
            QijingSurfaceGroup(Modifier.padding(horizontal = 16.dp)) { NativeListRow("GPU", "正在识别驱动节点", "采样中") }
        } else if (value.devices.isEmpty()) {
            EmptyState("GPU 指标不可用", value.detail ?: value.status.displayName(), Modifier.padding(16.dp))
        } else value.devices.forEach { device ->
            QijingSurfaceGroup(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
                NativeListRow("${device.adapter} · ${device.id}", "范围 ${device.minFrequencyHz.displayMHzHz()}–${device.maxFrequencyHz.displayMHzHz()}", device.currentFrequencyHz.displayMHzHz())
                HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                NativeListRow("GPU 负载", "驱动忙碌时间采样", device.loadPercent.value?.let { "%.0f%%".format(it) } ?: device.loadPercent.status.displayName())
                HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                NativeListRow("GPU Governor", "驱动当前策略", device.governor.display())
            }
        }
    }
}

@Composable
private fun MemoryObservationPanel(value: MemoryObservation?, applying: Boolean, onPlan: (Int) -> Unit) {
    var target by remember(value?.swappiness?.value) { mutableStateOf((value?.swappiness?.value ?: 60).coerceIn(0, 200).toFloat()) }
    val total = value?.totalBytes?.value
    val available = value?.availableBytes?.value
    val ratio = if (total != null && available != null && total > 0) ((total - available).toFloat() / total).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        PageSectionHeader("内存与交换", "RAM、Swap 与全部 ZRAM 设备", Modifier.padding(16.dp, 8.dp))
        QijingSurfaceGroup(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("内存使用", style = MaterialTheme.typography.titleMedium)
                Text("可用 ${formatBytes(available)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator({ ratio }, Modifier.fillMaxWidth(), color = if (ratio > .85f) QijingDanger else QijingMint)
            Text("总计 ${formatBytes(total)} · 缓存 ${formatBytes(value?.cachedBytes?.value)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        NativeListRow("Swap", "已用 ${formatBytes(value?.swapUsedBytes?.value)}", formatBytes(value?.swapTotalBytes?.value))
        value?.zramDevices.orEmpty().forEach { zram ->
            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            NativeListRow(
                zram.device,
                "${zram.currentAlgorithm.display()} · 压缩后 ${formatBytes(zram.compressedDataBytes.value)} · 内存占用 ${formatBytes(zram.memoryUsedBytes.value)}",
                if (zram.active.value == false) "未启用" else formatBytes(zram.diskSizeBytes.value)
            )
        }
        }
        PageSectionHeader("内存回收倾向", "低值更少换出，高值更积极换出", Modifier.padding(16.dp, 12.dp))
        QijingSurfaceGroup(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("目标 Swappiness")
                Text(target.toInt().toString(), color = MaterialTheme.colorScheme.primary)
            }
            Slider(target, { target = it }, valueRange = 0f..200f, steps = 19)
        }
        Button(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), enabled = !applying, onClick = { onPlan(target.toInt()) }) { Text("预览内存调节") }
        }
    }
}

@Composable
private fun BatteryObservationPanel(value: BatteryObservation?) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        PageSectionHeader("电池侧功耗", "功率是电池端瞬时值或估算，不代表 CPU/GPU 分项功耗", Modifier.padding(16.dp, 8.dp))
        QijingSurfaceGroup(Modifier.padding(horizontal = 16.dp)) {
        NativeListRow("当前功率", value?.powerMilliWatts?.detail ?: "电流 × 电压", value?.powerMilliWatts?.value?.let { "%.2f W%s".format(it / 1000.0, if (value.powerMilliWatts.estimated) " · 估算" else "") } ?: value?.powerMilliWatts?.status?.displayName().orEmpty(), leading = { Icon(Icons.Rounded.BatteryChargingFull, null, tint = QijingMint) })
        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        NativeListRow("电流", "正负方向依设备上报", value?.currentMicroAmps?.value?.let { "%.0f mA".format(it / 1000.0) } ?: value?.currentMicroAmps?.status?.displayName().orEmpty())
        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        NativeListRow("电压", "Android Battery API / sysfs", value?.voltageMilliVolts?.value?.let { "%.3f V".format(it / 1000.0) } ?: value?.voltageMilliVolts?.status?.displayName().orEmpty())
        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        NativeListRow("电池温度", "设备电池传感器", value?.temperatureCelsius?.value?.let { "%.1f °C".format(it) } ?: value?.temperatureCelsius?.status?.displayName().orEmpty())
        }
    }
}

private suspend fun probeSchedulers(context: Context, backend: ExecutionBackend): List<SchedulerProbeResult> = withContext(Dispatchers.IO) {
    val system = SchedulerProbeResult(SchedulerProviderId.SYSTEM, SchedulerAvailability.READY, detail = "栖境系统调节器")
    val runtime = BackendRuntimeFactory.create(context, backend)
    try {
        val privileged = runtime.readCapability
        if (privileged != null) {
            val probe = PrivilegedSchedulerProbe(privileged)
            listOf(system, probe.probe(SchedulerProviderId.UPERF), probe.probe(SchedulerProviderId.UPERF_GT), probe.probe(SchedulerProviderId.FAS_RS), probe.probe(SchedulerProviderId.CONFIG_BRIDGE))
        } else listOf(system, UperfSchedulerAdapter().probe(), UperfGtSchedulerAdapter().probe(), FasRsSchedulerAdapter().probe(), ConfigBridgeSchedulerAdapter().probe())
    } finally {
        runtime.close()
    }
}

private fun disableProfileScenes(dataStore: NewDataStore) {
    dataStore.scenes().filter { it.enabled && it.schedulerProvider == SchedulerProviderId.QIJING_PROFILE }
        .forEach { dataStore.saveScene(it.copy(enabled = false)) }
}

private suspend fun applyManualTune(
    context: Context,
    backend: ExecutionBackend,
    plan: PendingTune,
    recoveryStore: SharedPreferencesGlobalTuningRecoveryStore,
    previousConfiguration: GlobalTuningConfiguration
): Pair<String, Boolean> = withContext(Dispatchers.IO) {
    val runtime = BackendRuntimeFactory.create(context, backend)
    try {
        val snapshots = runtime.readCommand?.let { SceneSnapshotManager(CommandValueReader(it)) }
            ?: runtime.readCapability?.let { SceneSnapshotManager(CapabilityValueReader(it)) }
        val journal = if (backend in setOf(ExecutionBackend.ROOT, ExecutionBackend.SHIZUKU)) SharedPreferencesSceneTransactionJournalStore(context) else null
        val recoveryCommands = runCatching { plan.recoveryPlan?.toForwardCommands() }.getOrElse { error ->
            return@withContext "恢复计划无法转换：${error.message ?: "未知错误"}" to false
        }
        val engine = SceneEngine(
            runtime.broker,
            SharedPreferencesTaskLogStore(context),
            snapshots,
            journal,
            commandExpander = recoveryCommands?.let { commands ->
                SceneCommandExpander { SceneCommandExpansion.Commands(commands) }
            } ?: InstalledProfileSceneExpander(
                context,
                runtime.readCommand,
                enableThreadRuntime = backend == ExecutionBackend.ROOT,
                executionBackend = backend
            )
        )
        val targetScene = try {
            if (recoveryCommands != null) SceneProfile(
                id = "global-restore-${System.currentTimeMillis()}",
                name = plan.label,
                packageNames = emptySet(),
                schedulerProvider = SchedulerProviderId.QIJING_PROFILE
            ) else SceneProfile(
                id = "manual-${System.currentTimeMillis()}",
                name = plan.label,
                packageNames = emptySet(),
                cpu = plan.cpu,
                memory = plan.memory,
                schedulerProvider = plan.provider,
                schedulerMode = plan.mode
            )
        } catch (error: Throwable) {
            return@withContext "恢复计划无法转换：${error.message ?: "未知错误"}" to false
        }
        val result = engine.apply(
            targetScene,
            matchDetail = "用户确认手动调节"
        )
        when {
            result.failure == null && backend == ExecutionBackend.DRY_RUN -> "${plan.label}预演完成，系统未修改。" to true
            result.failure == null -> {
                val committed = journal != null && commitVerifiedGlobalTransaction(
                    journal,
                    recoveryStore,
                    plan.label,
                    previousConfiguration
                )
                if (committed) "${plan.label}已写入并读回验证；可从调节页撤销。" to true
                else "写入已验证，但无法提交可撤销记录；恢复 journal 已保留，自动化保持锁定。" to false
            }
            result.rolledBack -> "执行失败，已恢复原值：${result.failure}" to false
            else -> "未执行或恢复不完整：${result.failure}" to false
        }
    } finally {
        runtime.close()
    }
}

private fun loadGlobal(store: SharedPreferencesGlobalTuningProfileStore): GlobalTuningConfiguration {
    return when (val load = store.load()) {
        is GlobalTuningLoad.Loaded -> load.configuration
        else -> GlobalTuningConfiguration().also { store.create(it) }
    }
}

private fun saveGlobal(
    store: SharedPreferencesGlobalTuningProfileStore,
    current: GlobalTuningConfiguration,
    requested: GlobalTuningConfiguration
): GlobalTuningConfiguration? {
    if (requested.selected == current.selected &&
        requested.provider == current.provider &&
        requested.selectionKnown == current.selectionKnown
    ) return current
    val next = requested.copy(
        revision = current.revision + 1,
        updatedAtMs = System.currentTimeMillis(),
        selectionKnown = true
    )
    return next.takeIf { store.compareAndSet(current.revision, it) }
}

private fun TuningProfileReference.displayName(): String = when (this) {
    is TuningProfileReference.BuiltIn -> mode.displayName()
    is TuningProfileReference.Custom -> "自定义"
}

private fun SchedulerProviderId.displayName(): String = when (this) {
    SchedulerProviderId.SYSTEM -> "系统调节"
    SchedulerProviderId.UPERF -> "Uperf"
    SchedulerProviderId.UPERF_GT -> "UperfGT"
    SchedulerProviderId.FAS_RS -> "fas-rs"
    SchedulerProviderId.CONFIG_BRIDGE -> "配置调度器"
    SchedulerProviderId.QIJING_PROFILE -> "栖境配置引擎"
}

private fun SchedulerMode.description(): String = when (this) {
    SchedulerMode.POWER_SAVE -> "优先选择设备声明的节制 Governor"
    SchedulerMode.BALANCED -> "优先使用系统动态调度"
    SchedulerMode.PERFORMANCE -> "使用积极 Governor，不锁定最高频率"
    SchedulerMode.EXTREME -> "积极 Governor，并恢复各策略域硬件最高限制"
}

private fun MetricStatus.displayName(): String = when (this) {
    MetricStatus.AVAILABLE -> "可用"
    MetricStatus.SAMPLING -> "采样中"
    MetricStatus.UNSUPPORTED -> "设备未公开"
    MetricStatus.PERMISSION_DENIED -> "当前权限不可读"
    MetricStatus.INACTIVE -> "未启用"
    MetricStatus.INVALID -> "设备上报无效"
    MetricStatus.STALE -> "数据已过期"
}

private fun <T> ObservedMetric<T>.display(): String = value?.toString() ?: status.displayName()
private fun ObservedMetric<Long>.displayFrequency(): String = value?.takeIf { it > 0L }?.let { "${it / 1000} MHz" } ?: status.displayName()
private fun ObservedMetric<Long>.displayMHzHz(): String = value?.takeIf { it > 0L }?.let { "${it / 1_000_000} MHz" } ?: status.displayName()

private fun ExecutionBackend.displayName(): String = when (this) {
    ExecutionBackend.DRY_RUN -> "预览"
    ExecutionBackend.ROOT -> "Root"
    ExecutionBackend.SHIZUKU -> "Shizuku"
    else -> name
}

internal fun formatBytes(value: Long?): String = value?.let { "%.1f GiB".format(it / 1024.0 / 1024.0 / 1024.0) } ?: "未知"
