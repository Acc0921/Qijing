package com.qijing.core.device

data class CapabilityState(val id: String, val supported: Boolean, val reason: String? = null)

data class CapabilityMatrix(private val entries: Map<String, CapabilityState>) {
    fun state(id: String): CapabilityState = entries[id] ?: CapabilityState(id, false, "未探测")
    fun all(): List<CapabilityState> = entries.values.toList()
    companion object {
        fun from(ids: Iterable<String>, supported: Set<String>) = CapabilityMatrix(ids.associateWith { id ->
            CapabilityState(id, id in supported, if (id in supported) null else "当前设备不支持")
        })
    }
}
