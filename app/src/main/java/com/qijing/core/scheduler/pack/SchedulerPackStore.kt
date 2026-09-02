package com.qijing.core.scheduler.pack

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class InstalledSchedulerPack(
    val pack: SchedulerPack,
    val selectedVariantId: String?,
    val installedAtMs: Long
) {
    val selectedVariant: SchedulerPackVariant?
        get() = pack.variants.firstOrNull { it.id == selectedVariantId }
}

sealed interface SchedulerPackLoad {
    data object None : SchedulerPackLoad
    data class Loaded(val value: InstalledSchedulerPack) : SchedulerPackLoad
    data class Corrupt(val reason: String) : SchedulerPackLoad
}

/** App-private, atomic persistence for declarative configuration only. */
class SchedulerPackStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "scheduler-pack-v1")
    private val target = File(directory, "active.json")
    private val atomic = AtomicFile(target)

    fun load(): SchedulerPackLoad = synchronized(PROCESS_LOCK) {
        if (!target.exists() && !File("${target.path}.bak").exists()) return@synchronized SchedulerPackLoad.None
        runCatching { SchedulerPackCodec.decode(atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }) }
            .fold(
                onSuccess = { SchedulerPackLoad.Loaded(it) },
                onFailure = { SchedulerPackLoad.Corrupt(it.message ?: "配置包无法解析") }
            )
    }

    fun install(pack: SchedulerPack, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(PROCESS_LOCK) {
        if (pack.variants.isEmpty()) return@synchronized false
        writeAtomic(InstalledSchedulerPack(pack, selectedVariantId = null, installedAtMs = nowMs))
    }

    fun selectVariant(variantId: String, device: SchedulerPackDevice): Boolean = synchronized(PROCESS_LOCK) {
        val loaded = loadUnlocked() ?: return@synchronized false
        val variant = loaded.pack.variants.firstOrNull { it.id == variantId } ?: return@synchronized false
        if (!variant.compatibilityWith(device).compatible) return@synchronized false
        writeAtomic(loaded.copy(selectedVariantId = variantId))
    }

    fun clear(): Boolean = synchronized(PROCESS_LOCK) { runCatching { atomic.delete(); true }.getOrDefault(false) }

    private fun loadUnlocked(): InstalledSchedulerPack? = if (target.exists() || File("${target.path}.bak").exists()) {
        runCatching { SchedulerPackCodec.decode(atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }) }.getOrNull()
    } else null

    private fun writeAtomic(value: InstalledSchedulerPack): Boolean {
        if (!directory.exists() && !directory.mkdirs()) return false
        var output: java.io.FileOutputStream? = null
        return try {
            val stream = atomic.startWrite()
            output = stream
            stream.write(SchedulerPackCodec.encode(value).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            true
        } catch (_: Exception) {
            output?.let(atomic::failWrite)
            false
        }
    }

    private companion object { val PROCESS_LOCK = Any() }
}

object AndroidSchedulerDeviceProbe {
    fun probe(): SchedulerPackDevice {
        val cores = parseCpuList(readText("/sys/devices/system/cpu/present"))
            .ifEmpty { (0 until Runtime.getRuntime().availableProcessors().coerceAtLeast(1)).toSet() }
        val clusters = discoverClusters(cores)
        val socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
        return SchedulerPackDevice(
            socModel = socModel.ifBlank { Build.BOARD },
            platform = Build.HARDWARE.takeIf(String::isNotBlank),
            cpuCores = cores,
            clusterCoreSets = clusters,
            socIdentifiers = setOf(Build.BOARD, Build.DEVICE).filter(String::isNotBlank).toSet()
        )
    }

    private fun discoverClusters(cores: Set<Int>): List<Set<Int>> = cores.mapNotNull { cpu ->
        parseCpuList(readText("/sys/devices/system/cpu/cpu$cpu/cpufreq/related_cpus")).takeIf(Set<Int>::isNotEmpty)
    }.distinctBy { it.sorted() }.sortedBy { it.minOrNull() }

    private fun readText(path: String): String = runCatching { File(path).readText().trim() }.getOrDefault("")

    internal fun parseCpuList(raw: String): Set<Int> {
        val result = linkedSetOf<Int>()
        raw.trim().replace(',', ' ').split(Regex("\\s+")).filter(String::isNotBlank).forEach { token ->
            val range = token.split('-', limit = 2)
            val first = range[0].toIntOrNull() ?: return emptySet()
            val last = if (range.size == 2) range[1].toIntOrNull() ?: return emptySet() else first
            if (first !in 0..255 || last !in first..255) return emptySet()
            result += first..last
        }
        return result
    }
}

internal object SchedulerPackCodec {
    private const val SCHEMA = 1

    fun encode(value: InstalledSchedulerPack): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("selectedVariantId", value.selectedVariantId)
        put("installedAtMs", value.installedAtMs)
        put("pack", encodePack(value.pack))
    }.toString()

    fun decode(raw: String): InstalledSchedulerPack {
        require(raw.toByteArray(Charsets.UTF_8).size <= 40 * 1024 * 1024) { "配置包存储超限" }
        val root = JSONObject(raw)
        require(root.getInt("schema") == SCHEMA) { "配置包 schema 不受支持" }
        val pack = decodePack(root.getJSONObject("pack"))
        val selected = root.optString("selectedVariantId").takeIf(String::isNotBlank)
        require(selected == null || pack.variants.any { it.id == selected }) { "选中的配置变体不存在" }
        return InstalledSchedulerPack(pack, selected, root.getLong("installedAtMs"))
    }

    private fun encodePack(pack: SchedulerPack) = JSONObject().apply {
        put("id", pack.id)
        put("fingerprint", pack.contentFingerprintSha256)
        put("module", JSONObject().apply {
            put("id", pack.module.id); put("name", pack.module.name); put("version", pack.module.version)
            put("versionCode", pack.module.versionCode); put("author", pack.module.author); put("description", pack.module.description)
        })
        put("variants", JSONArray().apply { pack.variants.forEach { put(encodeVariant(it)) } })
    }

    private fun decodePack(root: JSONObject): SchedulerPack {
        val module = root.getJSONObject("module")
        val variants = root.getJSONArray("variants")
        require(variants.length() in 1..256) { "配置变体数量无效" }
        return SchedulerPack(
            id = root.getString("id"),
            module = SchedulerModuleMetadata(
                id = module.getString("id"), name = module.getString("name"),
                version = module.nullableString("version"), versionCode = module.nullableLong("versionCode"),
                author = module.nullableString("author"), description = module.nullableString("description")
            ),
            contentFingerprintSha256 = root.getString("fingerprint"),
            variants = (0 until variants.length()).map { decodeVariant(variants.getJSONObject(it)) }
        )
    }

    private fun encodeVariant(variant: SchedulerPackVariant) = JSONObject().apply {
        put("id", variant.id); put("path", variant.relativePath); put("category", JSONArray(variant.categoryPath))
        put("topology", variant.hardware.topology.canonical)
        put("socIdentifiers", JSONArray(variant.hardware.socIdentifiers.toList()))
        put("socModels", JSONArray(variant.hardware.socModels.toList()))
        put("platforms", JSONArray(variant.hardware.platforms.toList()))
        put("cpuCores", JSONArray(variant.hardware.requiredCpuCores.sorted()))
        put("manifest", variant.manifestJson); put("profile", variant.profileJson); put("threads", variant.threadsJson)
        put("description", variant.description)
        put("imports", JSONObject(variant.imports))
    }

    private fun decodeVariant(root: JSONObject): SchedulerPackVariant {
        val topology = CpuTopology.parse(root.getString("topology")) ?: error("CPU 拓扑无效")
        val manifestJson = root.getString("manifest")
        val manifestRoot = JSONObject(manifestJson)
        val features = manifestRoot.optJSONObject("features")?.let { obj ->
            obj.keys().asSequence().associateWith { obj.getBoolean(it) }
        }.orEmpty()
        val importsObject = root.optJSONObject("imports") ?: JSONObject()
        return SchedulerPackVariant(
            id = root.getString("id"), relativePath = root.getString("path"),
            categoryPath = root.getJSONArray("category").strings(),
            hardware = SchedulerPackHardwareRequirement(
                socIdentifiers = root.getJSONArray("socIdentifiers").strings().toSet(),
                socModels = root.getJSONArray("socModels").strings().toSet(),
                platforms = root.getJSONArray("platforms").strings().toSet(), topology = topology,
                requiredCpuCores = root.getJSONArray("cpuCores").ints().toSet()
            ),
            manifest = SchedulerVariantManifest(
                version = manifestRoot.nullableString("version"), versionCode = manifestRoot.nullableLong("versionCode"),
                author = manifestRoot.nullableString("author"), projectUrl = manifestRoot.nullableString("projectUrl"), features = features
            ),
            manifestJson = manifestJson, profileJson = root.getString("profile"), threadsJson = root.getString("threads"),
            imports = importsObject.keys().asSequence().associateWith { importsObject.getString(it) },
            description = root.nullableString("description")
        )
    }

    private fun JSONArray.strings() = (0 until length()).map(::getString)
    private fun JSONArray.ints() = (0 until length()).map(::getInt)
    private fun JSONObject.nullableString(key: String) = if (!has(key) || isNull(key)) null else getString(key)
    private fun JSONObject.nullableLong(key: String) = if (!has(key) || isNull(key)) null else getLong(key)
}
