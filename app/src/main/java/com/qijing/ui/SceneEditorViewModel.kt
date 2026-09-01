package com.qijing.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.qijing.core.model.AppEntry
import com.qijing.core.model.CpuPolicyIntent
import com.qijing.core.scheduler.SchedulerMode
import com.qijing.core.scheduler.SchedulerProviderId
import com.qijing.feature.scene.SceneDraft
import com.qijing.feature.scene.SceneEditorState
import com.qijing.feature.scene.SceneEditorStateStore

/**
 * Owns the scene editor independently of a composable destination.
 *
 * Only editable input is persisted. A preparation is deliberately never restored because every
 * process recreation or draft change must run capability discovery and rehearsal again.
 */
internal class SceneEditorViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
    private var persistentStore: SceneEditorStateStore? = null
    private var persistenceAttached = false
    private val restoredApp = restoreApp()
    private val restoredDraft = restoreDraft()

    private val targetAppState = androidx.compose.runtime.mutableStateOf(restoredApp)
    private val draftState = androidx.compose.runtime.mutableStateOf(restoredDraft ?: emptyDraft())
    private val intentState = androidx.compose.runtime.mutableStateOf(
        savedStateHandle[KEY_INTENT] ?: restoredDraft?.let(::inferIntent).orEmpty().ifBlank { INTENT_GLOBAL }
    )
    private val editorOpenState = androidx.compose.runtime.mutableStateOf(
        savedStateHandle[KEY_EDITOR_OPEN] ?: (restoredApp != null && restoredDraft != null)
    )

    var targetApp: AppEntry?
        get() = targetAppState.value
        private set(value) {
            targetAppState.value = value
            persistApp(value)
            persistEditorState()
        }

    var draft: SceneDraft
        get() = draftState.value
        set(value) {
            draftState.value = value
            persistDraft(value)
            persistEditorState()
        }

    var selectedIntent: String
        get() = intentState.value
        set(value) {
            intentState.value = value
            savedStateHandle[KEY_INTENT] = value
            persistEditorState()
        }

    var editorOpen: Boolean
        get() = editorOpenState.value
        private set(value) {
            editorOpenState.value = value
            savedStateHandle[KEY_EDITOR_OPEN] = value
            persistEditorState()
        }

    val hasRecoverableDraft: Boolean
        get() = targetApp != null && draft.id.isNotBlank()

    /** Attaches process-durable input after ViewModel creation without persisting any rehearsal. */
    fun attachPersistence(store: SceneEditorStateStore) {
        if (persistenceAttached) return
        persistenceAttached = true
        persistentStore = store
        val durable = store.load()
        if (!hasRecoverableDraft && durable != null) {
            targetAppState.value = durable.app
            draftState.value = durable.draft.copy(enabled = false)
            intentState.value = durable.selectedIntent
            editorOpenState.value = durable.editorOpen
            persistApp(durable.app)
            persistDraft(durable.draft.copy(enabled = false))
            savedStateHandle[KEY_INTENT] = durable.selectedIntent
            savedStateHandle[KEY_EDITOR_OPEN] = durable.editorOpen
        }
        persistEditorState()
    }

    /** Selecting another app changes the trigger object without discarding the tuning intent. */
    fun selectApp(app: AppEntry) {
        val existing = draft.takeIf { hasRecoverableDraft }
        targetApp = app
        draft = if (existing == null) {
            newDraft(app)
        } else {
            existing.copy(
                name = existing.name.ifBlank { "${app.label} · 场景" },
                packages = setOf(app.packageName),
                enabled = false
            )
        }
        if (existing == null) selectedIntent = INTENT_GLOBAL
        editorOpen = true
    }

    fun open(draft: SceneDraft, app: AppEntry, intent: String) {
        targetApp = app
        this.draft = draft.copy(enabled = false)
        selectedIntent = intent
        editorOpen = true
    }

    fun closeEditor() {
        editorOpen = false
    }

    fun resumeEditor() {
        if (hasRecoverableDraft) editorOpen = true
    }

    fun discardDraft() {
        targetApp = null
        draft = emptyDraft()
        selectedIntent = INTENT_GLOBAL
        editorOpen = false
        ALL_KEYS.forEach { savedStateHandle.remove<Any?>(it) }
        persistentStore?.clear()
    }

    private fun persistEditorState() {
        val app = targetAppState.value
        val value = draftState.value
        val store = persistentStore ?: return
        if (app == null || value.id.isBlank()) {
            store.clear()
            return
        }
        store.save(SceneEditorState(app, value.copy(enabled = false), intentState.value, editorOpenState.value))
    }

    private fun persistApp(app: AppEntry?) {
        if (app == null) {
            APP_KEYS.forEach { savedStateHandle.remove<Any?>(it) }
            return
        }
        savedStateHandle[KEY_APP_PACKAGE] = app.packageName
        savedStateHandle[KEY_APP_LABEL] = app.label
        savedStateHandle[KEY_APP_VERSION] = app.versionName
        savedStateHandle[KEY_APP_SYSTEM] = app.isSystem
        savedStateHandle[KEY_APP_LAUNCHABLE] = app.isLaunchable
    }

    private fun restoreApp(): AppEntry? {
        val packageName = savedStateHandle.get<String>(KEY_APP_PACKAGE) ?: return null
        return AppEntry(
            packageName = packageName,
            label = savedStateHandle[KEY_APP_LABEL] ?: packageName,
            versionName = savedStateHandle[KEY_APP_VERSION] ?: "",
            isSystem = savedStateHandle[KEY_APP_SYSTEM] ?: false,
            isLaunchable = savedStateHandle[KEY_APP_LAUNCHABLE] ?: true
        )
    }

    private fun persistDraft(value: SceneDraft) {
        savedStateHandle[KEY_DRAFT_ID] = value.id
        savedStateHandle[KEY_DRAFT_NAME] = value.name
        savedStateHandle[KEY_DRAFT_PACKAGES] = ArrayList(value.packages)
        savedStateHandle[KEY_DRAFT_GOVERNOR] = value.governor
        savedStateHandle[KEY_DRAFT_MIN] = value.minFrequencyKHz
        savedStateHandle[KEY_DRAFT_MAX] = value.maxFrequencyKHz
        savedStateHandle[KEY_DRAFT_POLICIES] = ArrayList(value.policyIntents.map(::encodePolicy))
        savedStateHandle[KEY_DRAFT_HAS_ONLINE] = value.onlineCores != null
        savedStateHandle[KEY_DRAFT_ONLINE] = value.onlineCores?.toIntArray() ?: intArrayOf()
        savedStateHandle[KEY_DRAFT_ZRAM_ENABLED] = value.zramEnabled?.toString()
        savedStateHandle[KEY_DRAFT_ZRAM_SIZE] = value.zramSizeMiB
        savedStateHandle[KEY_DRAFT_COMPRESSION] = value.compressionAlgorithm
        savedStateHandle[KEY_DRAFT_SWAPPINESS] = value.swappiness
        savedStateHandle[KEY_DRAFT_PRIORITY] = value.priority
        savedStateHandle[KEY_DRAFT_PROVIDER] = value.schedulerProvider.name
        savedStateHandle[KEY_DRAFT_MODE] = value.schedulerMode?.name
        savedStateHandle[KEY_DRAFT_GLOBAL] = value.followsGlobalProfile
    }

    private fun restoreDraft(): SceneDraft? {
        val id = savedStateHandle.get<String>(KEY_DRAFT_ID) ?: return null
        val provider = savedStateHandle.get<String>(KEY_DRAFT_PROVIDER)
            ?.let { runCatching { SchedulerProviderId.valueOf(it) }.getOrNull() }
            ?: SchedulerProviderId.SYSTEM
        val mode = savedStateHandle.get<String>(KEY_DRAFT_MODE)
            ?.let { runCatching { SchedulerMode.valueOf(it) }.getOrNull() }
        val onlineCores = if (savedStateHandle.get<Boolean>(KEY_DRAFT_HAS_ONLINE) == true) {
            savedStateHandle.get<IntArray>(KEY_DRAFT_ONLINE)?.toSet() ?: emptySet()
        } else null
        return SceneDraft(
            id = id,
            name = savedStateHandle[KEY_DRAFT_NAME] ?: "",
            packages = savedStateHandle.get<ArrayList<String>>(KEY_DRAFT_PACKAGES)?.toSet() ?: emptySet(),
            governor = savedStateHandle[KEY_DRAFT_GOVERNOR] ?: "",
            minFrequencyKHz = savedStateHandle[KEY_DRAFT_MIN] ?: "",
            maxFrequencyKHz = savedStateHandle[KEY_DRAFT_MAX] ?: "",
            policyIntents = savedStateHandle.get<ArrayList<String>>(KEY_DRAFT_POLICIES)?.mapNotNull(::decodePolicy) ?: emptyList(),
            onlineCores = onlineCores,
            zramEnabled = savedStateHandle.get<String>(KEY_DRAFT_ZRAM_ENABLED)?.toBooleanStrictOrNull(),
            zramSizeMiB = savedStateHandle[KEY_DRAFT_ZRAM_SIZE] ?: "",
            compressionAlgorithm = savedStateHandle[KEY_DRAFT_COMPRESSION] ?: "",
            swappiness = savedStateHandle[KEY_DRAFT_SWAPPINESS] ?: "",
            priority = savedStateHandle[KEY_DRAFT_PRIORITY] ?: 50,
            enabled = false,
            schedulerProvider = provider,
            schedulerMode = mode,
            followsGlobalProfile = savedStateHandle[KEY_DRAFT_GLOBAL] ?: false
        )
    }

    private fun encodePolicy(policy: CpuPolicyIntent): String = listOf(
        policy.policyId.toString(),
        policy.governor.orEmpty(),
        policy.minFrequencyKHz?.toString().orEmpty(),
        policy.maxFrequencyKHz?.toString().orEmpty()
    ).joinToString("|")

    private fun decodePolicy(value: String): CpuPolicyIntent? {
        val parts = value.split('|')
        val id = parts.getOrNull(0)?.toIntOrNull() ?: return null
        return runCatching {
            CpuPolicyIntent(
                policyId = id,
                governor = parts.getOrNull(1)?.ifBlank { null },
                minFrequencyKHz = parts.getOrNull(2)?.toLongOrNull(),
                maxFrequencyKHz = parts.getOrNull(3)?.toLongOrNull()
            )
        }.getOrNull()
    }

    private fun inferIntent(value: SceneDraft): String = when {
        value.followsGlobalProfile -> INTENT_GLOBAL
        value.schedulerMode == SchedulerMode.POWER_SAVE -> "saver"
        value.schedulerMode == SchedulerMode.BALANCED -> "balanced"
        value.schedulerMode == SchedulerMode.PERFORMANCE -> "performance"
        value.schedulerMode == SchedulerMode.EXTREME -> "extreme"
        else -> "custom"
    }

    private fun newDraft(app: AppEntry) = SceneDraft(
        id = "scene-${System.currentTimeMillis()}",
        name = "${app.label} · 场景",
        packages = setOf(app.packageName),
        enabled = false,
        followsGlobalProfile = true
    )

    private fun emptyDraft() = SceneDraft(id = "", name = "", enabled = false)

    private companion object {
        const val INTENT_GLOBAL = "global"
        const val KEY_INTENT = "scene.intent"
        const val KEY_EDITOR_OPEN = "scene.editor.open"
        const val KEY_APP_PACKAGE = "scene.app.package"
        const val KEY_APP_LABEL = "scene.app.label"
        const val KEY_APP_VERSION = "scene.app.version"
        const val KEY_APP_SYSTEM = "scene.app.system"
        const val KEY_APP_LAUNCHABLE = "scene.app.launchable"
        const val KEY_DRAFT_ID = "scene.draft.id"
        const val KEY_DRAFT_NAME = "scene.draft.name"
        const val KEY_DRAFT_PACKAGES = "scene.draft.packages"
        const val KEY_DRAFT_GOVERNOR = "scene.draft.governor"
        const val KEY_DRAFT_MIN = "scene.draft.min"
        const val KEY_DRAFT_MAX = "scene.draft.max"
        const val KEY_DRAFT_POLICIES = "scene.draft.policies"
        const val KEY_DRAFT_HAS_ONLINE = "scene.draft.has_online"
        const val KEY_DRAFT_ONLINE = "scene.draft.online"
        const val KEY_DRAFT_ZRAM_ENABLED = "scene.draft.zram_enabled"
        const val KEY_DRAFT_ZRAM_SIZE = "scene.draft.zram_size"
        const val KEY_DRAFT_COMPRESSION = "scene.draft.compression"
        const val KEY_DRAFT_SWAPPINESS = "scene.draft.swappiness"
        const val KEY_DRAFT_PRIORITY = "scene.draft.priority"
        const val KEY_DRAFT_PROVIDER = "scene.draft.provider"
        const val KEY_DRAFT_MODE = "scene.draft.mode"
        const val KEY_DRAFT_GLOBAL = "scene.draft.global"
        val APP_KEYS = listOf(KEY_APP_PACKAGE, KEY_APP_LABEL, KEY_APP_VERSION, KEY_APP_SYSTEM, KEY_APP_LAUNCHABLE)
        val ALL_KEYS = APP_KEYS + listOf(
            KEY_INTENT, KEY_EDITOR_OPEN, KEY_DRAFT_ID, KEY_DRAFT_NAME, KEY_DRAFT_PACKAGES,
            KEY_DRAFT_GOVERNOR, KEY_DRAFT_MIN, KEY_DRAFT_MAX, KEY_DRAFT_POLICIES,
            KEY_DRAFT_HAS_ONLINE, KEY_DRAFT_ONLINE, KEY_DRAFT_ZRAM_ENABLED,
            KEY_DRAFT_ZRAM_SIZE, KEY_DRAFT_COMPRESSION, KEY_DRAFT_SWAPPINESS,
            KEY_DRAFT_PRIORITY, KEY_DRAFT_PROVIDER, KEY_DRAFT_MODE, KEY_DRAFT_GLOBAL
        )
    }
}
