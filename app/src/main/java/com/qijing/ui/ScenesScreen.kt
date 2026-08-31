package com.qijing.ui

import android.content.Context
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
import com.qijing.core.scene.CapabilityValueReader
import com.qijing.core.scene.SceneEngine
import com.qijing.core.scene.ScenePreparation
import com.qijing.core.scene.SceneSnapshotManager
import com.qijing.feature.scene.SceneDraft
import com.qijing.feature.scene.SceneDraftStore
import com.qijing.feature.tuning.CpuStatusReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val INTENT_KEEP = "keep"
private const val INTENT_SAVER = "saver"
private const val INTENT_SYSTEM = "system"
private const val INTENT_RESPONSIVE = "responsive"
private const val INTENT_CUSTOM = "custom"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScenesScreen(
    store: NewDataStore,
    initialApp: AppEntry?,
    onAppConsumed: () -> Unit,
    onChooseApp: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sceneStore = remember(store) { SceneDraftStore(store) }
    val backend = remember(context) { BackendPreference(context).selected() }
    var scenes by remember(store) { mutableStateOf(sceneStore.load()) }
    var targetApp by remember { mutableStateOf<AppEntry?>(initialApp) }
    var draft by remember { mutableStateOf(newDraft(initialApp)) }
    var selectedIntent by remember { mutableStateOf(intentId(draft)) }
    var editorOpen by remember { mutableStateOf(initialApp != null) }
    var governors by remember { mutableStateOf<Set<String>>(emptySet()) }
    var preparation by remember { mutableStateOf<ScenePreparation?>(null) }
    var preparationRequestId by remember { mutableStateOf(0L) }
    var preparing by remember { mutableStateOf(false) }
    var showEnableConfirmation by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageError by remember { mutableStateOf(false) }

    fun invalidatePreparation() {
        preparationRequestId += 1
        preparation = null
        preparing = false
        showEnableConfirmation = false
    }

    LaunchedEffect(Unit) {
        governors = withContext(Dispatchers.IO) { CpuStatusReader().read().governors }
    }

    LaunchedEffect(initialApp) {
        if (initialApp != null) {
            targetApp = initialApp
            draft = newDraft(initialApp)
            selectedIntent = INTENT_KEEP
            editorOpen = true
            invalidatePreparation()
            message = "已建立应用关系。选择调节意图后运行安全预演。"
            messageError = false
            onAppConsumed()
        }
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
                        IconButton(onClick = { editorOpen = false; invalidatePreparation() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回场景列表")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onChooseApp) {
                        Icon(Icons.Rounded.Add, if (editorOpen) "更换应用" else "新建场景")
                    }
                }
            )
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
                            onValueChange = { draft = draft.copy(name = it, enabled = false); invalidatePreparation() },
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
                            onSelect = { id -> selectedIntent = id; draft = applyIntent(draft, id); invalidatePreparation() }
                        )
                        AnimatedVisibility(selectedIntent == INTENT_CUSTOM) {
                            CustomIntentEditor(draft, governors) { changed -> draft = changed.copy(enabled = false); invalidatePreparation() }
                        }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PriorityTrack(draft.priority, overlapping.size, onValueChange = { value ->
                            draft = draft.copy(priority = value, enabled = false)
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
                        TextButton(modifier = Modifier.fillMaxWidth(), onClick = { editorOpen = false; invalidatePreparation() }) { Text("返回场景列表") }
                    }
                }
            }
        } else {
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
                                    draft = loadedDraft.copy(enabled = false)
                                    selectedIntent = intentId(loadedDraft)
                                    targetApp = loadedApp
                                    invalidatePreparation()
                                    editorOpen = true
                                }
                            }
                        },
                        onEdit = {
                            openScene(scene, store) { loadedDraft, loadedApp ->
                                draft = loadedDraft.copy(enabled = false)
                                selectedIntent = intentId(loadedDraft)
                                targetApp = loadedApp
                                invalidatePreparation()
                                editorOpen = true
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
    IntentTrackOption(INTENT_KEEP, "保持当前", "不设置调节目标"),
    IntentTrackOption(INTENT_SAVER, "调度节制", "映射 powersave", "powersave" in governors),
    IntentTrackOption(INTENT_SYSTEM, "系统调度", "映射 schedutil", "schedutil" in governors),
    IntentTrackOption(INTENT_RESPONSIVE, "响应积极", "映射 performance", "performance" in governors),
    IntentTrackOption(INTENT_CUSTOM, "自定义", "选择设备候选并设置范围")
)

private fun applyIntent(draft: SceneDraft, id: String): SceneDraft = when (id) {
    INTENT_KEEP -> draft.copy(governor = "", minFrequencyKHz = "", maxFrequencyKHz = "", swappiness = "", enabled = false)
    INTENT_SAVER -> draft.copy(governor = "powersave", minFrequencyKHz = "", maxFrequencyKHz = "", swappiness = "", enabled = false)
    INTENT_SYSTEM -> draft.copy(governor = "schedutil", minFrequencyKHz = "", maxFrequencyKHz = "", swappiness = "", enabled = false)
    INTENT_RESPONSIVE -> draft.copy(governor = "performance", minFrequencyKHz = "", maxFrequencyKHz = "", swappiness = "", enabled = false)
    else -> draft.copy(enabled = false)
}

private fun intentId(draft: SceneDraft): String = when {
    draft.governor == "powersave" && draft.minFrequencyKHz.isBlank() && draft.maxFrequencyKHz.isBlank() && draft.swappiness.isBlank() -> INTENT_SAVER
    draft.governor == "schedutil" && draft.minFrequencyKHz.isBlank() && draft.maxFrequencyKHz.isBlank() && draft.swappiness.isBlank() -> INTENT_SYSTEM
    draft.governor == "performance" && draft.minFrequencyKHz.isBlank() && draft.maxFrequencyKHz.isBlank() && draft.swappiness.isBlank() -> INTENT_RESPONSIVE
    draft.hasWritableIntent() -> INTENT_CUSTOM
    else -> INTENT_KEEP
}

private fun intentLabel(draft: SceneDraft): String = when (intentId(draft)) {
    INTENT_SAVER -> "调度节制"
    INTENT_SYSTEM -> "系统调度"
    INTENT_RESPONSIVE -> "响应积极"
    INTENT_CUSTOM -> "自定义"
    else -> "保持当前"
}

private fun intentLabel(scene: SceneProfile): String = intentLabel(SceneDraft.fromProfile(scene))

private fun SceneDraft.hasWritableIntent(): Boolean = governor.isNotBlank() || minFrequencyKHz.isNotBlank() || maxFrequencyKHz.isNotBlank() || onlineCores != null || swappiness.isNotBlank() || zramEnabled != null || zramSizeMiB.isNotBlank() || compressionAlgorithm.isNotBlank()

private fun conflictSummary(draft: SceneDraft, conflicts: List<SceneProfile>): String? {
    if (conflicts.isEmpty()) return null
    val winner = (conflicts + draft.toProfile()).sortedWith(compareByDescending<SceneProfile> { it.priority }.thenBy { it.id }).first()
    return if (conflicts.any { it.priority == draft.priority }) {
        "存在同优先级冲突，真实启用已阻止。"
    } else {
        "与 ${conflicts.joinToString { it.name }} 重叠；按优先级将命中“${winner.name}”。"
    }
}

private fun newDraft(app: AppEntry?): SceneDraft = SceneDraft(
    id = "scene-${System.currentTimeMillis()}",
    name = app?.label?.let { "$it · 场景" }.orEmpty(),
    packages = app?.packageName?.let(::setOf) ?: emptySet(),
    enabled = false
)

private fun appLabel(store: NewDataStore, scene: SceneProfile): String {
    val apps = store.apps().associateBy { it.packageName }
    return scene.packageNames.joinToString { apps[it]?.label ?: it }
}

private fun openScene(scene: SceneProfile, store: NewDataStore, opened: (SceneDraft, AppEntry) -> Unit) {
    val packageName = scene.packageNames.firstOrNull().orEmpty()
    val app = store.apps().firstOrNull { it.packageName == packageName }
        ?: AppEntry(packageName, packageName.ifBlank { "未绑定应用" }, "", false)
    opened(SceneDraft.fromProfile(scene), app)
}
