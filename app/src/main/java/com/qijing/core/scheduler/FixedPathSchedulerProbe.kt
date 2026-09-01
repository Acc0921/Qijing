package com.qijing.core.scheduler

import java.io.File

/** The complete path allowlist visible to third-party scheduler probes. */
enum class FixedSchedulerPath(val absolutePath: String) {
    UPERF_MODULE_PROP("/data/adb/modules/uperf/module.prop"),
    UPERF_MODE_STATE("/sdcard/Android/yc/uperf/cur_powermode.txt"),
    UPERF_MODE_SWITCH("/data/powercfg.sh"),
    // UperfGT intentionally keeps the upstream Magisk module ID/path and is distinguished by name.
    UPERF_GT_MODULE_PROP("/data/adb/modules/uperf/module.prop"),
    FAS_RS_MODULE_PROP("/data/adb/modules/fas-rs/module.prop"),
    FAS_RS_MODE_NODE("/dev/fas_rs/mode")
}

data class FixedPathStatus(
    val path: FixedSchedulerPath,
    val exists: Boolean,
    val canonicalPath: String?,
    val directory: Boolean,
    val readable: Boolean
) {
    val exactIdentity: Boolean
        get() = exists && canonicalPath == path.absolutePath && !directory && readable
}

/** Callers can inspect only enum-backed paths and reads are hard limited. */
interface FixedSchedulerPathReader {
    fun status(path: FixedSchedulerPath): FixedPathStatus
    fun readUtf8(path: FixedSchedulerPath, maxBytes: Int): String?
}

class LocalFixedSchedulerPathReader : FixedSchedulerPathReader {
    override fun status(path: FixedSchedulerPath): FixedPathStatus {
        val file = File(path.absolutePath)
        return FixedPathStatus(
            path = path,
            exists = file.exists(),
            canonicalPath = runCatching { file.canonicalPath }.getOrNull(),
            directory = file.isDirectory,
            readable = file.canRead()
        )
    }

    override fun readUtf8(path: FixedSchedulerPath, maxBytes: Int): String? {
        if (maxBytes !in 1..MAX_READ_BYTES || !status(path).exactIdentity) return null
        return runCatching {
            File(path.absolutePath).inputStream().buffered().use { input ->
                val bytes = ByteArray(maxBytes + 1)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                if (count > maxBytes) return null
                bytes.copyOf(count).toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    private companion object { const val MAX_READ_BYTES = 16 * 1024 }
}

data class ModuleIdentity(val id: String, val name: String, val version: String?)

internal fun parseModuleIdentity(raw: String?): ModuleIdentity? {
    if (raw == null || raw.length > 16 * 1024 || '\u0000' in raw) return null
    val values = linkedMapOf<String, String>()
    raw.lineSequence().forEach { line ->
        val normalized = line.trim()
        if (normalized.isEmpty() || normalized.startsWith("#")) return@forEach
        val separator = normalized.indexOf('=')
        if (separator <= 0) return null
        val key = normalized.substring(0, separator).trim()
        val value = normalized.substring(separator + 1).trim()
        if (!MODULE_KEY.matches(key) || value.isBlank() || value.length > 512) return null
        if (key in CAPTURED_MODULE_KEYS && values.put(key, value) != null) return null
    }
    val id = values["id"]?.takeIf { MODULE_ID.matches(it) } ?: return null
    val name = values["name"]?.takeIf { it.length <= 64 } ?: return null
    return ModuleIdentity(id, name, values["version"]?.take(64))
}

private val CAPTURED_MODULE_KEYS = setOf("id", "name", "version")
private val MODULE_KEY = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
private val MODULE_ID = Regex("[A-Za-z0-9._-]{1,64}")
