package com.qijing.core.scheduler.pack

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

class SchedulerPackImporter(
    private val limits: SchedulerPackLimits = SchedulerPackLimits()
) {
    fun import(input: InputStream): SchedulerPackImportResult {
        val retained = linkedMapOf<String, ByteArray>()
        val seen = hashSetOf<String>()
        var entryCount = 0
        var expandedBytes = 0L

        try {
            val bounded = ArchiveSizeInputStream(input, limits.maxArchiveBytes)
            ZipInputStream(bounded).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > limits.maxEntries) {
                        return rejected(SchedulerPackRejectReason.TOO_MANY_ENTRIES, "Archive entry limit exceeded")
                    }
                    val path = validatePath(entry.name, entry.isDirectory)
                        ?: return rejected(SchedulerPackRejectReason.UNSAFE_ENTRY_PATH, "Unsafe archive path")
                    if (!seen.add(canonicalIdentity(path))) {
                        return rejected(SchedulerPackRejectReason.DUPLICATE_ENTRY, "Duplicate archive entry")
                    }
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    if (entry.size > limits.maxEntryBytes || entry.size > limits.maxExpandedBytes) {
                        return rejected(SchedulerPackRejectReason.ENTRY_TOO_LARGE, "Archive entry is too large")
                    }

                    val output = if (isRecognized(path)) ByteArrayOutputStream() else null
                    val buffer = ByteArray(BUFFER_SIZE)
                    var entryBytes = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        entryBytes += count
                        expandedBytes += count
                        if (entryBytes > limits.maxEntryBytes) {
                            return rejected(SchedulerPackRejectReason.ENTRY_TOO_LARGE, "Archive entry is too large")
                        }
                        if (expandedBytes > limits.maxExpandedBytes) {
                            return rejected(
                                SchedulerPackRejectReason.EXPANDED_CONTENT_TOO_LARGE,
                                "Expanded archive content limit exceeded"
                            )
                        }
                        output?.write(buffer, 0, count)
                    }
                    output?.let { retained[path] = it.toByteArray() }
                    zip.closeEntry()
                }
            }
        } catch (_: ArchiveLimitException) {
            return rejected(SchedulerPackRejectReason.ARCHIVE_TOO_LARGE, "Archive byte limit exceeded")
        } catch (_: ZipException) {
            return rejected(SchedulerPackRejectReason.INVALID_ZIP, "Invalid ZIP archive")
        } catch (_: IOException) {
            return rejected(SchedulerPackRejectReason.INVALID_ZIP, "Unable to read ZIP archive")
        }

        val texts = linkedMapOf<String, String>()
        retained.forEach { (path, bytes) ->
            texts[path] = decodeUtf8(bytes)
                ?: return rejected(SchedulerPackRejectReason.INVALID_TEXT, "Recognized file is not valid UTF-8")
        }
        return buildPack(texts)
    }

    private fun buildPack(entries: Map<String, String>): SchedulerPackImportResult {
        val moduleRaw = entries[MODULE_PROP]
            ?: return rejected(SchedulerPackRejectReason.MISSING_MODULE_METADATA, "module.prop is missing")
        val module = parseModuleProp(moduleRaw)
            ?: return rejected(SchedulerPackRejectReason.INVALID_MODULE_METADATA, "module.prop is invalid")
        val packId = "scene-pack:${canonicalIdentity(module.id)}"

        val variantFiles = linkedMapOf<String, MutableMap<VariantFile, String>>()
        val variantImports = linkedMapOf<String, MutableMap<String, String>>()
        val variantRoots = entries.keys.filter { path ->
            path.endsWith("/profile.json") && path.startsWith("$CONFIG/")
        }.mapTo(hashSetOf()) { it.substringBeforeLast('/') }
        entries.forEach { (path, content) ->
            val parts = path.split('/')
            if (parts.firstOrNull() != CONFIG || parts.size < MIN_VARIANT_PATH_SEGMENTS) return@forEach
            val variantPath = parts.dropLast(1).joinToString("/")
            if (variantPath !in variantRoots) return@forEach
            val fileName = parts.last()
            if (IMPORT_FILE.matches(fileName)) {
                val imports = variantImports.getOrPut(variantPath) { linkedMapOf() }
                if (imports.put(fileName, content) != null) {
                    return rejected(SchedulerPackRejectReason.DUPLICATE_ENTRY, "Variant has duplicate import files")
                }
                return@forEach
            }
            val fileKind = VariantFile.from(fileName) ?: return@forEach
            val files = variantFiles.getOrPut(variantPath) { linkedMapOf() }
            if (files.put(fileKind, content) != null) {
                return rejected(SchedulerPackRejectReason.DUPLICATE_ENTRY, "Variant has duplicate recognized files")
            }
        }
        if (variantFiles.isEmpty()) {
            return rejected(SchedulerPackRejectReason.MISSING_VARIANTS, "No Config variants were found")
        }

        val variants = mutableListOf<SchedulerPackVariant>()
        variantFiles.toSortedMap().forEach { (path, files) ->
            val profile = files[VariantFile.PROFILE]
            val threads = files[VariantFile.THREADS]
            val manifestRaw = files[VariantFile.MANIFEST]
            if (profile == null || threads == null || manifestRaw == null) {
                return rejected(SchedulerPackRejectReason.INCOMPLETE_VARIANT, "Variant is missing required JSON files")
            }
            if (!isJsonObject(profile) || !isJsonArray(threads)) {
                return rejected(SchedulerPackRejectReason.INVALID_CONFIGURATION_JSON, "Profile or threads JSON is invalid")
            }
            val manifestObject = parseExactJsonObject(manifestRaw)
                ?: return rejected(SchedulerPackRejectReason.INVALID_VARIANT_METADATA, "Variant manifest is invalid")
            val pathParts = path.split('/')
            val topology = CpuTopology.parse(pathParts[1])
                ?: return rejected(SchedulerPackRejectReason.INVALID_VARIANT_METADATA, "CPU topology is invalid")
            val socIdentifier = pathParts[2].trim().takeIf(String::isNotEmpty)
                ?: return rejected(SchedulerPackRejectReason.INVALID_VARIANT_METADATA, "SoC identifier is missing")
            val hardware = parseHardware(manifestObject, socIdentifier, topology)
                ?: return rejected(SchedulerPackRejectReason.INVALID_VARIANT_METADATA, "Hardware metadata is invalid")
            val manifest = parseManifest(manifestObject)
                ?: return rejected(SchedulerPackRejectReason.INVALID_VARIANT_METADATA, "Variant manifest is invalid")
            val canonicalPath = canonicalIdentity(path)
            variants += SchedulerPackVariant(
                id = "$packId:variant:${stableDigest(canonicalPath).take(16)}",
                relativePath = path,
                categoryPath = pathParts.drop(3),
                hardware = hardware,
                manifest = manifest,
                manifestJson = manifestRaw,
                profileJson = profile,
                threadsJson = threads,
                imports = variantImports[path].orEmpty().toSortedMap(),
                description = files[VariantFile.DESCRIPTION]?.trim()?.takeIf(String::isNotEmpty)
            )
        }

        val fingerprintSource = entries.toSortedMap().entries.joinToString("\u0000") { (path, value) ->
            "${canonicalIdentity(path)}\u0000$value"
        }
        return SchedulerPackImportResult.Imported(
            SchedulerPack(
                id = packId,
                module = module,
                contentFingerprintSha256 = stableDigest(fingerprintSource),
                variants = variants
            )
        )
    }

    private fun parseModuleProp(raw: String): SchedulerModuleMetadata? {
        val values = linkedMapOf<String, String>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) return@forEach
            val separator = trimmed.indexOf('=')
            if (separator <= 0) return@forEach
            val key = trimmed.substring(0, separator).trim()
            val value = trimmed.substring(separator + 1).trim()
            if (key.length > 64 || value.length > 4_096 || values.put(key, value) != null) return null
        }
        val id = values["id"]?.takeIf { MODULE_ID.matches(it) } ?: return null
        val name = values["name"]?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
        return SchedulerModuleMetadata(
            id = id,
            name = name,
            version = values["version"]?.takeIf { it.length <= 128 },
            versionCode = values["versionCode"]?.toLongOrNull(),
            author = values["author"]?.takeIf { it.length <= 128 },
            description = values["description"]?.takeIf { it.length <= 4_096 }
        )
    }

    private fun parseManifest(root: JSONObject): SchedulerVariantManifest? {
        val features = linkedMapOf<String, Boolean>()
        val featuresObject = root.optJSONObject("features")
        if (featuresObject != null) {
            val keys = featuresObject.keys().asSequence().toList()
            if (keys.size > 64) return null
            keys.sorted().forEach { key ->
                if (!MANIFEST_KEY.matches(key)) return null
                val value = featuresObject.opt(key)
                if (value !is Boolean) return null
                features[key] = value
            }
        }
        val version = root.optionalString("version", 128) ?: if (root.hasNonNull("version")) return null else null
        val versionCode = root.optionalLong("versionCode")
            ?: if (root.hasNonNull("versionCode")) return null else null
        val author = root.optionalString("author", 128) ?: if (root.hasNonNull("author")) return null else null
        val projectUrl = root.optionalString("projectUrl", 2_048)
            ?: if (root.hasNonNull("projectUrl")) return null else null
        return SchedulerVariantManifest(
            version = version,
            versionCode = versionCode,
            author = author,
            projectUrl = projectUrl,
            features = features
        )
    }

    private fun parseHardware(
        root: JSONObject,
        pathSocIdentifier: String,
        pathTopology: CpuTopology
    ): SchedulerPackHardwareRequirement? {
        val compatibility = root.optJSONObject("compatibility")
        val topologyText = compatibility?.optionalString("cpuTopology", 64)
        if (compatibility?.hasNonNull("cpuTopology") == true && topologyText == null) return null
        val declaredTopology = topologyText?.let(CpuTopology::parse) ?: pathTopology
        if (declaredTopology != pathTopology) return null

        val alias = KnownSocAliases.resolve(pathSocIdentifier)
        val identifiers = linkedSetOf(pathSocIdentifier)
        identifiers += alias?.identifiers.orEmpty()
        val declaredIdentifiers = compatibility?.optionalStringSet("socIdentifiers", 32)
        if (compatibility?.hasNonNull("socIdentifiers") == true && declaredIdentifiers == null) return null
        identifiers += declaredIdentifiers.orEmpty()
        val models = linkedSetOf<String>()
        models += alias?.models.orEmpty()
        val declaredModels = compatibility?.optionalStringSet("socModels", 32)
        if (compatibility?.hasNonNull("socModels") == true && declaredModels == null) return null
        models += declaredModels.orEmpty()
        if (models.isEmpty()) models += pathSocIdentifier
        val platforms = linkedSetOf<String>()
        platforms += alias?.platforms.orEmpty()
        val declaredPlatforms = compatibility?.optionalStringSet("platforms", 16)
        if (compatibility?.hasNonNull("platforms") == true && declaredPlatforms == null) return null
        platforms += declaredPlatforms.orEmpty()

        val declaredCores = compatibility?.optJSONArray("cpuCores")?.toIntSet(256)
        if (compatibility?.has("cpuCores") == true && declaredCores == null) return null
        val requiredCores = declaredCores ?: declaredTopology.expectedCpuCores
        if (requiredCores != declaredTopology.expectedCpuCores) return null
        return SchedulerPackHardwareRequirement(
            socIdentifiers = identifiers,
            socModels = models,
            platforms = platforms,
            topology = declaredTopology,
            requiredCpuCores = requiredCores
        )
    }

    private fun validatePath(raw: String, directory: Boolean): String? {
        if (raw.isEmpty() || raw.length > limits.maxPathCharacters || '\u0000' in raw) return null
        if (raw.startsWith('/') || raw.startsWith('\\') || '\\' in raw || DRIVE_PATH.containsMatchIn(raw)) return null
        val path = if (directory && raw.endsWith('/')) raw.dropLast(1) else raw
        if (path.isEmpty() || (!directory && raw.endsWith('/'))) return null
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." || it.any(Char::isISOControl) }) return null
        return path
    }

    private fun isRecognized(path: String): Boolean {
        if (path == MODULE_PROP) return true
        val parts = path.split('/')
        return parts.firstOrNull() == CONFIG &&
            parts.size >= MIN_VARIANT_PATH_SEGMENTS &&
            (VariantFile.from(parts.last()) != null || IMPORT_FILE.matches(parts.last()))
    }

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun isJsonObject(raw: String): Boolean = isExactJsonType(raw, JSONObject::class.java)

    private fun isJsonArray(raw: String): Boolean = isExactJsonType(raw, JSONArray::class.java)

    private fun parseExactJsonObject(raw: String): JSONObject? = runCatching {
        val tokener = JSONTokener(raw)
        val value = tokener.nextValue() as? JSONObject ?: return@runCatching null
        value.takeIf { tokener.nextClean() == '\u0000' }
    }.getOrNull()

    private fun isExactJsonType(raw: String, type: Class<*>): Boolean = runCatching {
        val tokener = JSONTokener(raw)
        type.isInstance(tokener.nextValue()) && tokener.nextClean() == '\u0000'
    }.getOrDefault(false)

    private fun rejected(reason: SchedulerPackRejectReason, detail: String) =
        SchedulerPackImportResult.Rejected(reason, detail)

    private enum class VariantFile {
        PROFILE,
        THREADS,
        MANIFEST,
        DESCRIPTION;

        companion object {
            fun from(fileName: String): VariantFile? = when (fileName) {
                "profile.json" -> PROFILE
                "threads.json" -> THREADS
                "manifest.json" -> MANIFEST
                "description", "description.txt" -> DESCRIPTION
                else -> null
            }
        }
    }

    private object KnownSocAliases {
        private val aliases = mapOf(
            "8e" to SocAlias(setOf("8E"), setOf("sm8750", "sm8750p", "sm8750ab"), setOf("sun")),
            "8e5" to SocAlias(setOf("8E5"), setOf("sm8850", "sm8850p", "sm8850ac"), setOf("canoe"))
        )

        fun resolve(identifier: String): SocAlias? = aliases[canonicalHardwareName(identifier)]
    }

    private data class SocAlias(
        val identifiers: Set<String>,
        val models: Set<String>,
        val platforms: Set<String>
    )

    private class ArchiveSizeInputStream(
        input: InputStream,
        private val maximum: Long
    ) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) add(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) add(count.toLong())
            return count
        }

        private fun add(value: Long) {
            count += value
            if (count > maximum) throw ArchiveLimitException()
        }
    }

    private class ArchiveLimitException : IOException()

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
        const val MODULE_PROP = "module.prop"
        const val CONFIG = "Config"
        const val MIN_VARIANT_PATH_SEGMENTS = 5
        val DRIVE_PATH = Regex("^[A-Za-z]:")
        val IMPORT_FILE = Regex("_[A-Za-z0-9._-]{1,64}\\.json")
        val MODULE_ID = Regex("[A-Za-z0-9._-]{1,128}")
        val MANIFEST_KEY = Regex("[A-Za-z0-9._-]{1,64}")
    }
}

private fun JSONObject.optionalString(key: String, maximumLength: Int): String? {
    if (!has(key) || isNull(key)) return null
    return (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= maximumLength }
}

private fun JSONObject.hasNonNull(key: String): Boolean = has(key) && !isNull(key)

private fun JSONObject.optionalLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}

private fun JSONObject.optionalStringSet(key: String, maximumCount: Int): Set<String>? {
    if (!has(key) || isNull(key)) return emptySet()
    val values = optJSONArray(key) ?: return null
    if (values.length() > maximumCount) return null
    val result = linkedSetOf<String>()
    for (index in 0 until values.length()) {
        val value = (values.opt(index) as? String)?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 128 } ?: return null
        if (!result.add(value)) return null
    }
    return result
}

private fun JSONArray.toIntSet(maximumCount: Int): Set<Int>? {
    if (length() !in 1..maximumCount) return null
    val result = linkedSetOf<Int>()
    for (index in 0 until length()) {
        val value = opt(index) as? Number ?: return null
        val integer = value.toInt()
        if (integer !in 0..255 || value.toDouble() != integer.toDouble() || !result.add(integer)) return null
    }
    return result
}
