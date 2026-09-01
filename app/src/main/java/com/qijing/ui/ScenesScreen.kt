package com.qijing.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.qijing.core.data.NewDataStore
import com.qijing.core.execution.BackendPreference
import com.qijing.core.execution.BackendRuntimeFactory
import com.qijing.core.execution.ExecutionResult
import com.qijing.core.logging.SharedPreferencesTaskLogStore
import com.qijing.core.model.AppEntry
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.model.SceneProfile
import com.qijing.core.device.observation.CpuObservation
import com.qijing.core.device.observation.CpuObservationReader
import com.qijing.core.scheduler.SchedulerMode
import com.qijing.core.scheduler.SchedulerProviderId
import com.qijing.core.scene.CapabilityValueReader
import com.qijing.core.scene.SceneEngine
import com.qijing.core.scene.ScenePreparation
import com.qijing.core.scene.SceneSnapshotManager
import com.qijing.core.scene.SharedPreferencesSceneTaskEventStore
import com.qijing.feature.scene.SceneDraft
import com.qijing.feature.scene.SceneDraftStore
import com.qijing.feature.tuning.CpuStatusReader
import com.qijing.feature.tuning.profile.GlobalTuningConfiguration
import com.qijing.feature.tuning.profile.GlobalTuningLoad
import com.qijing.feature.tuning.profile.GlobalTuningResolution
import com.qijing.feature.tuning.profile.GlobalTuningResolver
import com.qijing.feature.tuning.profile.SharedPreferencesGlobalTuningProfileStore
import com.qijing.feature.tuning.profile.TuningProfileReference
import com.qijing.feature.tuning.profile.displayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val INTENT_GLOBAL = "global"
private const val INTENT_SAVER = "saver"
private const val INTENT_BALANCED = "balanced"
private const val INTENT_PERFORMANCE = "performance"
private const val INTENT_EXTREME = "extreme"
private const val INTENT_CUSTOM = "custom"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScenesScreen(
    store: NewDataStore,
    editor: SceneEditorViewModel,
    onChooseApp: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sceneStore = remember(store) { SceneDraftStore(store) }
    val taskEventStore = remember(context) { SharedPreferencesSceneTaskEventStore(context) }
    val globalProfileStore = remember(context) { SharedPreferencesGlobalTuningProfileStore(context) }
    val globalConfiguration = remember(globalProfileStore) {
        (globalProfileStore.load() as? GlobalTuningLoad.Loaded)?.configuration ?: GlobalTuningConfiguration()
    }
    val backend = remember(context) { BackendPreference(context).selected() }
    var scenes by remember(store) { mutableStateOf(sceneStore.load()) }
    val targetApp = editor.targetApp
    val draft = editor.draft
    val selectedIntent = editor.selectedIntent
    val editorOpen = editor.editorOpen
    var governors by remember { mutableStateOf<Set<String>>(emptySet()) }
    var cpuObservation by remember { mutableStateOf<CpuObservation?>(null) }
    var preparation by remember { mutableStateOf<ScenePreparation?>(null) }
    var preparationRequestId by remember { mutableStateOf(0L) }
    var preparing by remember { mutableStateOf(false) }
    var showEnableConfirmation by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageError by remember { mutableStateOf(false) }
    var taskEvents by remember { mutableStateOf(taskEventStore.recent(80)) }

    DisposableEffect(taskEventStore) {
        val observation = taskEventStore.observe(80) { taskEvents = it }
        onDispose { observation.close() }
    }

    fun invalidatePreparation() {
        preparationRequestId += 1
        preparation = null
        preparing = false
        showEnableConfirmation = false
    }

    LaunchedEffect(Unit) {
        val values = withContext(Dispatchers.IO) { CpuStatusReader().read().governors to CpuObservationReader().read() }
        governors = values.first
        cpuObservation = values.second
    }

    LaunchedEffect(cpuObservation, selectedIntent) {
        val observed = cpuObservation ?: return@LaunchedEffect
        if (selectedIntent == INTENT_GLOBAL && targetApp != null) {
            editor.draft = applyIntent(draft, INTENT_GLOBAL, observed, globalConfiguration)
            invalidatePreparation()
        }
    }

    BackHandler(enabled = editorOpen) {
        editor.closeEditor()
        invalidatePreparation()
    }

    val overlapping = scenes.filter { scene ->
        scene.id != draft.id && scene.enabled && scene.packageNames.any { it in draft.packages }
    }
    val samePriority = overlapping.filter { it.priority == draft.priority }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.testTag("scene-list"),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            QijingTopAppBar(
                title = if (editorOpen) "编辑场景" else "场景",
                navigationIcon = {
                    if (editorOpen) {
                        IconButton(onClick = { editor.closeEditor(); invalidatePreparation() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回场景列表")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onChooseApp) {
                        Icon(if (editorOpen) Icons.Rounded.Edit else Icons.Rounded.Add, if (editorOpen) "更换应用" else "新建场景")
                    }
                }
            )
        }

        if (taskEvents.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    PageSectionHeader("最近自动任务", "命中、验证与恢复来自同一事务")
                    SceneTaskTrail(taskEvents, Modifier.padding(top = 6.dp))
                }
            }
        }

        if (editorOpen) {
            item {
                QijingPanel(elevated = true, modifier = Modifier.padding(16.dp).testTag("scene-editor")) {
                    val app = targetApp
                    if (app == null) {
                        EmptyState("先选择一个应用", "从应用栏选择触发对象，栖境会把它带入完整链路。")
                        Button(onClick = onChooseApp) { Text("前往选择应用") }
                    } else {
                        OutlinedTextField(
                            value = draft.name,
                            onValueChange = { editor.draft = draft.copy(name = it, enabled = false); invalidatePreparation() },
                            modifier = Modifier.fillMaxWidth().testTag("scene-name"),
                            singleLine = true,
                            label = { Text("场景名称") },
                            placeholder = { Text("例如：原神 · 响应优先") }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SceneChainRail(
                            appName = app.label,
                            packageName = app.packageName,
                            intent = intentLabel(draft),
                            priority = draft.priority,
                            backend = backend
                        )
                    }
                }
            }

            if (targetApp != null) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PageSectionHeader("选择调节意图", "只描述调度倾向，不承诺性能或功耗收益")
                        IntentTrack(
                            options = intentOptions(governors),
                            selectedId = selectedIntent,
                            onSelect = { id ->
                                editor.selectedIntent = id
                                editor.draft = cpuObservation?.let { applyIntent(draft, id, it, globalConfiguration) }
                                    ?: draft.copy(enabled = false)
                                invalidatePreparation()
                            }
                        )
                        AnimatedVisibility(selectedIntent == INTENT_CUSTOM) {
                            CustomIntentEditor(draft, governors) { changed -> editor.draft = changed.copy(enabled = false); invalidatePreparation() }
                        }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PriorityTrack(draft.priority, overlapping.size, onValueChange = { value ->
                            editor.draft = draft.copy(priority = value, enabled = false)
                            invalidatePreparation()
                        })
                        if (samePriority.isNotEmpty()) {
                            Text(
                                "同优先级冲突：${samePriority.joinToString { it.name }}。草稿可以保存，但真实启用前必须调整。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                preparation?.let { report ->
                    item {
                        QijingPanel(elevated = true, modifier = Modifier.padding(16.dp).testTag("scene-preview")) {
                            RehearsalReport(
                                preparation = report,
                                backend = backend,
                                conflictMessage = conflictSummary(draft, overlapping)
                            )
                        }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        message?.let {
                            Text(
                                it,
                                modifier = Modifier.testTag("scene-save-result").semantics { liveRegion = LiveRegionMode.Polite },
                                color = if (messageError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                        OutlinedButton(
                                modifier = Modifier.fillMaxWidth().testTag("scene-save"),
                                onClick = {
                                    val errors = sceneStore.save(draft.copy(enabled = false))
                                    if (errors.isEmpty()) {
                                        scenes = sceneStore.load()
                                        message = "草稿已保存，尚未启用，也不会写入系统。"
                                        messageError = false
                                    } else {
                                        message = errors.joinToString("；")
                                        messageError = true
                                    }
                                }
                            ) { Text("保存但不启用") }
                        Button(
                                modifier = Modifier.fillMaxWidth().testTag("scene-rehearse"),
                                enabled = !preparing,
                                onClick = {
                                    val errors = draft.validate()
                                    if (errors.isNotEmpty() || !draft.hasWritableIntent()) {
                                        message = (errors + if (!draft.hasWritableIntent()) listOf("至少选择一个调节目标") else emptyList()).joinToString("；")
                                        messageError = true
                                    } else {
                                        val requestedDraft = draft.copy(enabled = false)
                                        val requestedBackend = backend
                                        val requestId = preparationRequestId + 1
                                        preparationRequestId = requestId
                                        preparing = true
                                        message = null
                                        scope.launch {
                                            try {
                                                var report = prepareScene(context, requestedBackend, requestedDraft)
                                                if (report.ready && samePriority.isNotEmpty()) {
                                                    report = report.copy(
                                                        failure = ExecutionResult.Unsupported(
                                                            "scene.priority.conflict",
                                                            "同一应用存在相同优先级场景，请先调整优先级"
                                                        )
                                                    )
                                                }
                                                if (requestId == preparationRequestId &&
                                                    draft.copy(enabled = false) == requestedDraft
                                                ) preparation = report
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (error: Throwable) {
                                                if (requestId == preparationRequestId) {
                                                    preparation = null
                                                    message = "预演失败：${error.message ?: error::class.simpleName}"
                                                    messageError = true
                                                }
                                            } finally {
                                                if (requestId == preparationRequestId) preparing = false
                                            }
                                        }
                                    }
                                }
                            ) { Text(if (preparing) "预演中…" else "运行安全预演") }
                        preparation?.takeIf { it.ready && it.plan.commands.isNotEmpty() }?.let {
                            Button(
                                modifier = Modifier.fillMaxWidth().testTag("scene-enable"),
                                onClick = {
                                    if (backend == ExecutionBackend.DRY_RUN) {
                                        val errors = sceneStore.enableApproved(draft, preparation, backend)
                                        if (errors.isEmpty()) {
                                            scenes = sceneStore.load()
                                            message = "预览场景已启用，命中时只记录计划，不修改系统。"
                                            messageError = false
                                        } else {
                                            message = errors.joinToString("；")
                                            messageError = true
                                        }
                                    } else showEnableConfirmation = true
                                }
                            ) {
                                Text(if (backend == ExecutionBackend.DRY_RUN) "启用预览场景" else "继续启用真实自动调节")
                            }
                        }
                        TextButton(modifier = Modifier.fillMaxWidth(), onClick = { editor.closeEditor(); invalidatePreparation() }) { Text("返回场景列表") }
                    }
                }
            }
        } else {
            if (editor.hasRecoverableDraft) {
                item {
                    NativeListRow(
                        title = "继续未完成的场景",
                        supporting = "${targetApp?.label ?: "已选应用"} · ${draft.name.ifBlank { "未命名场景" }}",
                        status = "草稿",
                        onClick = editor::resumeEditor,
                        leading = { Icon(Icons.Rounded.Edit, null, tint = MaterialTheme.colorScheme.primary) }
                    )
                }
            }
            item {
                NativeListRow(
                    title = "自动调节安全边界",
                    supporting = "命中时重新快照；离场、切换或停止服务时恢复",
                    status = backend.displayLabel(),
                    leading = { Icon(Icons.Rounded.Security, null, tint = if (backend == ExecutionBackend.DRY_RUN) QijingBlue else QijingAmber) }
                )
            }
            item { PageSectionHeader("已保存场景", if (scenes.isEmpty()) "从一个应用开始建立关系" else "${scenes.size} 个场景", Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) }
            if (scenes.isEmpty()) {
                item { EmptyState("还没有场景", "从应用栏选择目标应用，建立第一条安全调节链路。", Modifier.padding(horizontal = 16.dp)) }
            } else {
                items(scenes.size, key = { scenes[it].id }) { index ->
                    val scene = scenes[index]
                    SceneCard(
                        scene = scene,
                        appLabel = appLabel(store, scene),
                        backend = backend,
                        onEnabledChange = { enabled ->
                            if (!enabled) {
                                sceneStore.disable(scene.id)
                                scenes = sceneStore.load()
                            } else {
                                openScene(scene, store) { loadedDraft, loadedApp ->
                                    editor.open(loadedDraft, loadedApp, intentId(loadedDraft))
                                    invalidatePreparation()
                                }
                            }
                        },
                        onEdit = {
                            openScene(scene, store) { loadedDraft, loadedApp ->
                                editor.open(loadedDraft, loadedApp, intentId(loadedDraft))
                                invalidatePreparation()
                            }
                        }
                    )
                }
            }
        }
    }

    if (showEnableConfirmation) {
        RealEnableDialog(
            backend = backend,
            draft = draft,
            preparation = preparation,
            onDismiss = { showEnableConfirmation = false },
            onConfirm = {
                val errors = sceneStore.enableApproved(draft, preparation, backend)
                if (errors.isEmpty()) {
                    scenes = sceneStore.load()
                    message = "场景已启用，正在等待应用命中；这不代表系统已经写入。"
                    messageError = false
                } else {
                    message = errors.joinToString("；")
                    messageError = true
                }
                showEnableConfirmation = false
            }
        )
    }
}

@Composable
private fun CustomIntentEditor(draft: SceneDraft, governors: Set<String>, onChange: (SceneDraft) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("设备声明的 Governor", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (governors.isEmpty()) {
            Text("未读取到候选策略，真实写入会被预演阻止。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                governors.sorted().forEach { governor ->
                    androidx.compose.material3.FilterChip(
                        modifier = Modifier.testTag("scene-governor-$governor"),
                        selected = draft.governor == governor,
                        onClick = { onChange(draft.copy(governor = governor)) },
                        label = { Text(governor) }
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                draft.minFrequencyKHz,
                { onChange(draft.copy(minFrequencyKHz = it.filter(Char::isDigit))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("最低频率 kHz") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            OutlinedTextField(
                draft.maxFrequencyKHz,
                { onChange(draft.copy(maxFrequencyKHz = it.filter(Char::isDigit))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("最高频率 kHz") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        }
        OutlinedTextField(
            draft.swappiness,
            { onChange(draft.copy(swappiness = it.filter(Char::isDigit).take(3))) },
            modifier = Modifier.fillMaxWidth().testTag("scene-swappiness"),
            label = { Text("Swappiness 0–200") },
            supportingText = { Text("低值减少主动换出，高值提高换出倾向；不代表性能收益。") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )
    }
}

@Composable
private fun SceneCard(
    scene: SceneProfile,
    appLabel: String,
    backend: ExecutionBackend,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit
) {
    Column(Modifier.fillMaxWidth().testTag("scene-card-${scene.id}")) {
        NativeListRow(
            title = scene.name,
            supporting = "$appLabel · ${intentLabel(scene)} · 优先级 ${scene.priority}\n${if (scene.enabled) "已启用，等待命中；命中不等于已写入" else "已保存，启用前需重新预演"}",
            onClick = onEdit,
            leading = { Icon(Icons.Rounded.Edit, null, tint = MaterialTheme.colorScheme.primary) },
            trailing = {
            Switch(
                modifier = Modifier.testTag("scene-enabled-${scene.id}").semantics {
                    contentDescription = "${scene.name}场景启用状态"
                },
                checked = scene.enabled,
                onCheckedChange = onEnabledChange
            )
            }
        )
        Text(
            "${backend.displayLabel()} · 离场恢复原值",
            modifier = Modifier.padding(start = 72.dp, end = 16.dp, bottom = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RealEnableDialog(
    backend: ExecutionBackend,
    draft: SceneDraft,
    preparation: ScenePreparation?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Security, null, tint = QijingAmber)
                Text("启用并允许自动调节", style = MaterialTheme.typography.titleLarge)
            }
            NativeListRow(title = "执行方式", supporting = "不会静默切换", status = backend.displayLabel())
            NativeListRow(title = "作用应用", supporting = draft.packages.joinToString())
            preparation?.plan?.commands?.forEach { command ->
                val target = command.arguments["value"] ?: command.arguments["khz"] ?: "—"
                val original = preparation.snapshot?.values?.get(command.capability) ?: "未读取"
                NativeListRow(title = command.capability, supporting = "参考原值 $original", status = "→ $target")
            }
            NativeListRow(title = "作用时机", supporting = "目标应用进入前台")
            NativeListRow(title = "恢复条件", supporting = "离场、场景切换或停止服务")
            Text("真正执行前会重新读取原值；快照不完整则不写入。调度变化可能影响功耗、温度和稳定性。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(modifier = Modifier.fillMaxWidth().testTag("scene-enable-confirm"), onClick = onConfirm) { Text("启用并允许自动调节") }
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = onDismiss) { Text("取消") }
        }
    }
}

private suspend fun prepareScene(context: Context, backend: ExecutionBackend, draft: SceneDraft): ScenePreparation = withContext(Dispatchers.IO) {
    val runtime = BackendRuntimeFactory.create(context, backend)
    try {
        val snapshots = runtime.readCapability?.let { SceneSnapshotManager(CapabilityValueReader(it)) }
        SceneEngine(runtime.broker, SharedPreferencesTaskLogStore(context), snapshots).prepare(draft.toProfile(), recordFailureLog = false)
    } finally {
        runtime.close()
    }
}

private fun intentOptions(governors: Set<String>): List<IntentTrackOption> = listOf(
    IntentTrackOption(INTENT_GLOBAL, "跟随全局", "采用调节页保存的默认模式"),
    IntentTrackOption(INTENT_SAVER, "省电", "优先节制 Governor", governors.any { it in setOf("powersave", "conservative", "schedutil") }),
    IntentTrackOption(INTENT_BALANCED, "均衡", "优先动态调度", governors.any { it in setOf("schedutil", "interactive", "ondemand") }),
    IntentTrackOption(INTENT_PERFORMANCE, "性能", "积极响应但不锁频", governors.any { it in setOf("performance", "schedutil", "interactive") }),
    IntentTrackOption(INTENT_EXTREME, "极速", "积极策略并恢复最高限制", "performance" in governors),
    IntentTrackOption(INTENT_CUSTOM, "自定义", "按设备策略域设置参数")
)

private fun applyIntent(
    draft: SceneDraft,
    id: String,
    cpu: CpuObservation,
    global: GlobalTuningConfiguration
): SceneDraft {
    if (id == INTENT_CUSTOM) return draft.copy(
        governor = "", minFrequencyKHz = "", maxFrequencyKHz = "", policyIntents = emptyList(), swappiness = "",
        schedulerProvider = SchedulerProviderId.SYSTEM, schedulerMode = null, followsGlobalProfile = false, enabled = false
    )
    val configuration = if (id == INTENT_GLOBAL) global else global.copy(
        provider = SchedulerProviderId.SYSTEM,
        selected = TuningProfileReference.BuiltIn(id.schedulerMode() ?: SchedulerMode.BALANCED)
    )
    return when (val resolution = GlobalTuningResolver().resolve(configuration, cpu)) {
        is GlobalTuningResolution.Blocked -> draft.copy(enabled = false)
        is GlobalTuningResolution.Ready -> resolution.target.let { target ->
            draft.copy(
                governor = "",
                minFrequencyKHz = "",
                maxFrequencyKHz = "",
                policyIntents = target.cpu.policies,
                swappiness = target.memory.swappiness?.toString().orEmpty(),
                schedulerProvider = target.provider,
                schedulerMode = target.mode,
                followsGlobalProfile = id == INTENT_GLOBAL,
                enabled = false
            )
        }
    }
}

private fun intentId(draft: SceneDraft): String = when {
    draft.followsGlobalProfile -> INTENT_GLOBAL
    draft.schedulerMode == SchedulerMode.POWER_SAVE -> INTENT_SAVER
    draft.schedulerMode == SchedulerMode.BALANCED -> INTENT_BALANCED
    draft.schedulerMode == SchedulerMode.PERFORMANCE -> INTENT_PERFORMANCE
    draft.schedulerMode == SchedulerMode.EXTREME -> INTENT_EXTREME
    draft.hasWritableIntent() -> INTENT_CUSTOM
    else -> INTENT_CUSTOM
}

private fun intentLabel(draft: SceneDraft): String = when (intentId(draft)) {
    INTENT_GLOBAL -> "跟随全局"
    INTENT_SAVER -> "省电"
    INTENT_BALANCED -> "均衡"
    INTENT_PERFORMANCE -> "性能"
    INTENT_EXTREME -> "极速"
    INTENT_CUSTOM -> "自定义"
    else -> "自定义"
}

private fun intentLabel(scene: SceneProfile): String = intentLabel(SceneDraft.fromProfile(scene))

private fun SceneDraft.hasWritableIntent(): Boolean = schedulerProvider != SchedulerProviderId.SYSTEM || policyIntents.isNotEmpty() || governor.isNotBlank() || minFrequencyKHz.isNotBlank() || maxFrequencyKHz.isNotBlank() || onlineCores != null || swappiness.isNotBlank() || zramEnabled != null || zramSizeMiB.isNotBlank() || compressionAlgorithm.isNotBlank()

private fun String.schedulerMode(): SchedulerMode? = when (this) {
    INTENT_SAVER -> SchedulerMode.POWER_SAVE
    INTENT_BALANCED -> SchedulerMode.BALANCED
    INTENT_PERFORMANCE -> SchedulerMode.PERFORMANCE
    INTENT_EXTREME -> SchedulerMode.EXTREME
    else -> null
}

private fun conflictSummary(draft: SceneDraft, conflicts: List<SceneProfile>): String? {
    if (conflicts.isEmpty()) return null
    val winner = (conflicts + draft.toProfile()).sortedWith(compareByDescending<SceneProfile> { it.priority }.thenBy { it.id }).first()
    return if (conflicts.any { it.priority == draft.priority }) {
        "存在同优先级冲突，真实启用已阻止。"
    } else {
        "与 ${conflicts.joinToString { it.name }} 重叠；按优先级将命中“${winner.name}”。"
    }
}

private fun appLabel(store: NewDataStore, scene: SceneProfile): String {
    val apps = store.apps().associateBy { it.packageName }
    return scene.packageNames.joinToString { apps[it]?.label ?: it }
}

private fun openScene(scene: SceneProfile, store: NewDataStore, opened: (SceneDraft, AppEntry) -> Unit) {
    val packageName = scene.packageNames.firstOrNull().orEmpty()
    val app = store.apps().firstOrNull { it.packageName == packageName }
        ?: AppEntry(packageName, packageName.ifBlank { "未绑定应用" }, "", false, false)
    opened(SceneDraft.fromProfile(scene), app)
}
