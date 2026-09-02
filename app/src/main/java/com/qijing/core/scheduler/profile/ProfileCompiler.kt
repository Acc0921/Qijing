package com.qijing.core.scheduler.profile

import org.json.JSONArray
import org.json.JSONObject

/**
 * Clean-room compiler for the bounded, declarative subset of Scene profiles.
 *
 * It does not emit shell, accept commands, or infer support for a kernel node. Its output must be
 * passed to a separately reviewed capability planner before any privileged operation is possible.
 */
class ProfileCompiler {
    fun compile(raw: String, imports: Map<String, String> = emptyMap()): ProfileCompileResult {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_DOCUMENT_BYTES) {
            return ProfileCompileResult.Rejected("profile exceeds $MAX_DOCUMENT_BYTES bytes")
        }
        if (raw.isBlank()) return ProfileCompileResult.Rejected("profile is empty")
        return try {
            if (imports.size > MAX_IMPORTS) throw RejectedProfile("import count exceeds $MAX_IMPORTS")
            val importBytes = imports.entries.sumOf { (name, content) ->
                if (!IMPORT_NAME.matches(name)) throw RejectedProfile("invalid import name: $name")
                content.toByteArray(Charsets.UTF_8).size.toLong()
            }
            if (importBytes > MAX_IMPORT_BYTES) throw RejectedProfile("imports exceed $MAX_IMPORT_BYTES bytes")
            ProfileCompileResult.Compiled(Compiler(JSONObject(raw), imports).compile())
        } catch (failure: RejectedProfile) {
            ProfileCompileResult.Rejected(failure.message ?: "profile rejected")
        } catch (_: Exception) {
            ProfileCompileResult.Rejected("profile JSON is invalid")
        }
    }

    private class Compiler(
        private val root: JSONObject,
        private val suppliedImports: Map<String, String>
    ) {
        private val aliases = linkedMapOf<String, KernelNode>()
        private val presets = linkedMapOf<String, JSONArray>()
        private val presetCache = mutableMapOf<String, List<ProfileOperation>>()
        private var sourceOperationCount = 0
        private var featureNodeCount = 0
        private val importedContentCache = mutableMapOf<String, ImportedContent>()
        private val usedImports = linkedSetOf<String>()

        fun compile(): CompiledProfileProgram {
            rejectUnknownKeys(root, ROOT_KEYS, "profile")
            parseAliases(requiredObject(root, "alias"))
            val features = parseFeatures(requiredObject(root, "features"))
            parsePresets(requiredObject(root, "presets"))
            val reset = compileArray(requiredArray(root, "reset"), "reset", emptyList())

            // Validate every preset, including unused ones. Dead configuration must not bypass checks.
            presets.keys.forEach { expandPreset(it, emptyList()) }
            val routes = compileRoutes(requiredObject(root, "schemes"))
            val applicationRules = compileApplicationRules()
            val unusedImports = suppliedImports.keys - usedImports
            if (unusedImports.isNotEmpty()) reject("unused imports: ${unusedImports.sorted().joinToString()}")
            return CompiledProfileProgram(aliases.toMap(), features, reset, routes, applicationRules)
        }

        private fun compileApplicationRules(): List<ImportedApplicationRule> {
            val output = mutableListOf<ImportedApplicationRule>()
            root.optJSONArray("apps")?.let { output += compileApplicationArray(it, ProfileWorkload.APP, "apps") }
            root.optJSONArray("games")?.let { output += compileApplicationArray(it, ProfileWorkload.GAME, "games") }
            val selectors = mutableSetOf<Pair<ProfileWorkload, String>>()
            output.forEach { rule ->
                rule.packageSelectors.forEach { selector ->
                    if (!selectors.add(rule.workload to selector)) {
                        reject("duplicate ${rule.workload.stableId} package selector: $selector")
                    }
                }
            }
            return output
        }

        private fun compileApplicationArray(
            array: JSONArray,
            workload: ProfileWorkload,
            section: String
        ): List<ImportedApplicationRule> {
            if (array.length() > MAX_APPLICATION_RULES) reject("$section has too many rules")
            return (0 until array.length()).map { index ->
                val value = array.optJSONObject(index) ?: reject("$section[$index] must be an object")
                rejectUnknownKeys(value, APPLICATION_REFERENCE_KEYS, "$section[$index]")
                val friendly = checkedText(value.opt("friendly") as? String ?: reject("$section[$index].friendly is missing"), "$section[$index].friendly")
                if (friendly.isBlank()) reject("$section[$index].friendly is blank")
                val packages = parsePackageSelectors(value.optJSONArray("packages"), "$section[$index].packages")
                val importName = value.opt("import") as? String ?: reject("$section[$index].import is missing")
                if (!IMPORT_NAME.matches(importName)) reject("invalid import name: $importName")
                val content = importContent(importName)
                ImportedApplicationRule(friendly, packages, workload, content.modes, importName)
            }
        }

        private fun parsePackageSelectors(array: JSONArray?, location: String): Set<String> {
            if (array == null || array.length() !in 1..MAX_PACKAGES_PER_RULE) reject("$location has invalid size")
            val output = linkedSetOf<String>()
            for (index in 0 until array.length()) {
                val value = array.opt(index) as? String ?: reject("$location[$index] must be a string")
                if (!PACKAGE_SELECTOR.matches(value) || !output.add(value)) reject("invalid or duplicate package selector: $value")
            }
            return output
        }

        private fun importContent(name: String): ImportedContent {
            importedContentCache[name]?.let { return it }
            val raw = suppliedImports[name] ?: reject("required import is missing: $name")
            usedImports += name
            val root = try {
                JSONObject(raw)
            } catch (_: Exception) {
                reject("import $name JSON is invalid")
            }
            rejectUnknownKeys(root, IMPORT_ROOT_KEYS, "import $name")
            // friendly is descriptive only, but must still be bounded when supplied.
            root.opt("friendly")?.takeUnless { it == JSONObject.NULL }?.let {
                checkedText(it as? String ?: reject("import $name friendly must be a string"), "import $name friendly")
            }
            val commonCall = root.optJSONArray("call")?.let { compileArray(it, "imports.$name.call", emptyList()) }.orEmpty()
            val commonState = compileImportState(root.optJSONObject("state"), "imports.$name.state")
            val commonMetadata = root.optJSONObject("fas")?.let { parseFeatureObject(it, 0, "imports.$name.fas").entries }.orEmpty()
            val modeOverrides = linkedMapOf<ProfileMode, ImportedModeParts>()
            root.optJSONArray("modes")?.let { modes ->
                if (modes.length() > MAX_IMPORTED_MODE_ENTRIES) reject("import $name has too many mode entries")
                for (index in 0 until modes.length()) {
                    val modeObject = modes.optJSONObject(index) ?: reject("import $name modes[$index] must be an object")
                    rejectUnknownKeys(modeObject, IMPORT_MODE_KEYS, "import $name modes[$index]")
                    val modeIds = modeObject.optJSONArray("mode") ?: reject("import $name modes[$index].mode is missing")
                    if (modeIds.length() !in 1..ProfileMode.entries.size + 1) reject("import $name modes[$index] has invalid modes")
                    val call = modeObject.optJSONArray("call")?.let { compileArray(it, "imports.$name.modes[$index].call", emptyList()) }.orEmpty()
                    val state = compileImportState(modeObject.optJSONObject("state"), "imports.$name.modes[$index].state")
                    val metadata = modeObject.optJSONObject("fas")?.let {
                        parseFeatureObject(it, 0, "imports.$name.modes[$index].fas").entries
                    }.orEmpty()
                    for (modeIndex in 0 until modeIds.length()) {
                        val stableId = modeIds.opt(modeIndex) as? String ?: reject("import $name mode id must be a string")
                        if (stableId == LEGACY_PEDESTAL_MODE) continue
                        val mode = ProfileMode.entries.firstOrNull { it.stableId == stableId }
                            ?: reject("import $name contains unknown mode: $stableId")
                        if (modeOverrides.put(mode, ImportedModeParts(call, state, metadata)) != null) {
                            reject("import $name repeats mode ${mode.stableId}")
                        }
                    }
                }
            }
            val programs = ProfileMode.entries.associateWith { mode ->
                val specific = modeOverrides[mode]
                ImportedModeProgram(
                    call = boundedMerge(commonCall, specific?.call.orEmpty(), "import $name ${mode.stableId} call"),
                    active = boundedMerge(commonState.active, specific?.state?.active.orEmpty(), "import $name ${mode.stableId} active"),
                    inactive = boundedMerge(commonState.inactive, specific?.state?.inactive.orEmpty(), "import $name ${mode.stableId} inactive"),
                    metadata = commonMetadata + specific?.metadata.orEmpty()
                )
            }
            return ImportedContent(programs).also { importedContentCache[name] = it }
        }

        private fun compileImportState(state: JSONObject?, location: String): ImportState {
            if (state == null) return ImportState(emptyList(), emptyList())
            rejectUnknownKeys(state, IMPORT_STATE_KEYS, location)
            val active = state.optJSONArray("active")?.let { compileArray(it, "$location.active", emptyList()) }.orEmpty()
            val inactive = state.optJSONArray("inactive")?.let { compileArray(it, "$location.inactive", emptyList()) }.orEmpty()
            return ImportState(active, inactive)
        }

        private fun boundedMerge(
            first: List<ProfileOperation>,
            second: List<ProfileOperation>,
            location: String
        ): List<ProfileOperation> {
            if (first.size + second.size > MAX_EXPANDED_OPERATIONS) reject("$location exceeds operation limit")
            return first + second
        }

        private fun parseAliases(value: JSONObject) {
            val keys = value.keys().asSequence().toList()
            if (keys.size > MAX_ALIASES) reject("alias count exceeds $MAX_ALIASES")
            keys.forEach { name ->
                if (!IDENTIFIER.matches(name)) reject("invalid alias name: $name")
                val path = value.opt(name) as? String ?: reject("alias $name must be a string")
                aliases[name] = parsePath(path, "alias $name")
            }
        }

        private fun parseFeatures(value: JSONObject): Map<String, ProfileFeatureValue> {
            val parsed = parseFeatureObject(value, 0, "features")
            return parsed.entries
        }

        private fun parseFeatureObject(value: JSONObject, depth: Int, location: String): ProfileFeatureValue.ObjectValue {
            checkFeatureDepth(depth)
            val keys = value.keys().asSequence().toList()
            if (keys.size > MAX_OBJECT_ENTRIES) reject("$location has too many entries")
            val output = linkedMapOf<String, ProfileFeatureValue>()
            keys.forEach { key ->
                if (key.isBlank() || key.length > MAX_NAME_LENGTH || '\u0000' in key) {
                    reject("$location contains an invalid key")
                }
                output[key] = parseFeatureValue(value.get(key), depth + 1, "$location.$key")
            }
            return ProfileFeatureValue.ObjectValue(output)
        }

        private fun parseFeatureValue(value: Any, depth: Int, location: String): ProfileFeatureValue {
            if (++featureNodeCount > MAX_FEATURE_NODES) reject("features exceed $MAX_FEATURE_NODES nodes")
            checkFeatureDepth(depth)
            return when (value) {
                is JSONObject -> parseFeatureObject(value, depth, location)
                is JSONArray -> {
                    if (value.length() > MAX_ARRAY_ENTRIES) reject("$location has too many entries")
                    ProfileFeatureValue.ArrayValue((0 until value.length()).map { index ->
                        parseFeatureValue(value.get(index), depth + 1, "$location[$index]")
                    })
                }
                is String -> ProfileFeatureValue.StringValue(checkedText(value, location))
                is Number -> ProfileFeatureValue.NumberValue(checkedText(value.toString(), location))
                is Boolean -> ProfileFeatureValue.BooleanValue(value)
                JSONObject.NULL -> ProfileFeatureValue.NullValue
                else -> reject("$location has an unsupported value")
            }
        }

        private fun checkFeatureDepth(depth: Int) {
            if (depth > MAX_FEATURE_DEPTH) reject("features nesting exceeds $MAX_FEATURE_DEPTH")
        }

        private fun parsePresets(value: JSONObject) {
            val keys = value.keys().asSequence().toList()
            if (keys.size > MAX_PRESETS) reject("preset count exceeds $MAX_PRESETS")
            keys.forEach { name ->
                if (!IDENTIFIER.matches(name)) reject("invalid preset name: $name")
                presets[name] = value.optJSONArray(name) ?: reject("preset $name must be an array")
            }
        }

        private fun compileRoutes(schemes: JSONObject): Map<ProfileRoute, List<ProfileOperation>> {
            val knownSchemeKeys = ProfileMode.entries.mapTo(mutableSetOf()) { it.stableId }
            // Pedestal existed in the source format but is intentionally not exposed as a Qijing mode.
            knownSchemeKeys += LEGACY_PEDESTAL_MODE
            rejectUnknownKeys(schemes, knownSchemeKeys, "schemes")
            val routes = linkedMapOf<ProfileRoute, List<ProfileOperation>>()
            ProfileMode.entries.forEach { mode ->
                val scheme = schemes.optJSONObject(mode.stableId)
                    ?: reject("scheme ${mode.stableId} is missing")
                rejectUnknownKeys(scheme, ProfileWorkload.entries.mapTo(mutableSetOf()) { it.stableId }, "scheme ${mode.stableId}")
                val inactiveName = "${mode.stableId}_inactive"
                if (inactiveName !in presets) reject("inactive preset $inactiveName is missing")
                ProfileWorkload.entries.forEach { workload ->
                    val activeArray = scheme.optJSONArray(workload.stableId)
                    val active = if (activeArray == null) emptyList() else compileArray(
                        activeArray,
                        "schemes.${mode.stableId}.${workload.stableId}",
                        emptyList()
                    )
                    routes[ProfileRoute(mode, workload, ProfilePhase.ACTIVE)] = active
                    routes[ProfileRoute(mode, workload, ProfilePhase.INACTIVE)] = expandPreset(inactiveName, emptyList())
                }
            }
            return routes
        }

        private fun expandPreset(name: String, chain: List<String>): List<ProfileOperation> {
            presetCache[name]?.let { return it }
            if (name in chain) reject("preset cycle: ${(chain + name).joinToString(" -> ")}")
            if (chain.size >= MAX_PRESET_DEPTH) reject("preset nesting exceeds $MAX_PRESET_DEPTH")
            val value = presets[name] ?: reject("unknown preset: $name")
            val expanded = compileArray(value, "presets.$name", chain + name)
            if (expanded.size > MAX_EXPANDED_OPERATIONS) reject("preset $name expands beyond the operation limit")
            presetCache[name] = expanded
            return expanded
        }

        private fun compileArray(value: JSONArray, section: String, chain: List<String>): List<ProfileOperation> {
            if (value.length() > MAX_OPERATIONS_PER_ARRAY) reject("$section has too many operations")
            val output = mutableListOf<ProfileOperation>()
            for (index in 0 until value.length()) {
                if (++sourceOperationCount > MAX_SOURCE_OPERATIONS) reject("profile has too many source operations")
                val row = value.optJSONArray(index) ?: reject("$section[$index] must be an array")
                val origin = OperationOrigin(section, chain, index)
                val compiled = compileRow(row, origin, chain)
                output += compiled
                if (output.size > MAX_EXPANDED_OPERATIONS) reject("$section expands beyond the operation limit")
            }
            return output
        }

        private fun compileRow(row: JSONArray, origin: OperationOrigin, chain: List<String>): List<ProfileOperation> {
            if (row.length() !in 1..MAX_ARGUMENTS_PER_OPERATION) reject("${origin.section}[${origin.index}] has invalid arity")
            val head = row.opt(0) as? String ?: reject("${origin.section}[${origin.index}] target must be a string")
            return when {
                head.startsWith("@") -> compileMacro(head, row, origin, chain)
                else -> {
                    if (row.length() !in 1..2) reject("node operation must contain a target and optional value")
                    val target = resolveTarget(head, origin)
                    listOf(if (row.length() == 1) {
                        ProfileOperation.Capture(target, origin)
                    } else {
                        ProfileOperation.Write(target, parseValue(row.get(1), origin), origin)
                    })
                }
            }
        }

        private fun compileMacro(
            macro: String,
            row: JSONArray,
            origin: OperationOrigin,
            chain: List<String>
        ): List<ProfileOperation> = when (macro) {
            "@preset" -> {
                requireArity(row, 2, macro)
                expandPreset(identifierArgument(row, 1, macro), chain)
            }
            "@values" -> {
                if (row.length() < 3) reject("$macro requires a preset and values")
                val preset = identifierArgument(row, 1, macro)
                val targets = captureTargets(preset, chain)
                val values = (2 until row.length()).map { parseValue(row.get(it), origin) }
                if (targets.size != values.size) reject("$macro value count does not match preset $preset")
                listOf(ProfileOperation.Values(preset, targets, values, origin))
            }
            "@cpuset" -> {
                requireArity(row, 5, macro)
                val values = valueArguments(row, origin)
                listOf(ProfileOperation.CpuSet(values[0], values[1], values[2], values[3], origin))
            }
            "@cpu_freqs_min" -> frequencyOperation(row, origin, minimum = true)
            "@cpu_freqs_max" -> frequencyOperation(row, origin, minimum = false)
            "@cpu_freq" -> {
                requireArity(row, 4, macro)
                val policy = identifierArgument(row, 1, macro)
                if (!CPU_POLICY.matches(policy)) reject("$macro contains an invalid policy")
                val bound = when (row.opt(2) as? String) {
                    "min" -> CpuFrequencyBound.MINIMUM
                    "max" -> CpuFrequencyBound.MAXIMUM
                    else -> reject("$macro bound must be min or max")
                }
                listOf(ProfileOperation.CpuFrequency(policy, bound, parseValue(row.get(3), origin), origin))
            }
            "@target_loads" -> policyValues(row, origin) { ProfileOperation.TargetLoads(it, origin) }
            "@hispeed_freq" -> policyValues(row, origin) { ProfileOperation.HispeedFrequencies(it, origin) }
            "@governor" -> {
                requireArity(row, 2, macro)
                val governor = identifierArgument(row, 1, macro)
                listOf(ProfileOperation.Governor(governor, origin))
            }
            "@limiter" -> {
                requireArity(row, 2, macro)
                val limiter = identifierArgument(row, 1, macro)
                listOf(ProfileOperation.Limiter(limiter, origin))
            }
            "@fps" -> {
                requireArity(row, 2, macro)
                listOf(ProfileOperation.FrameRate(parseValue(row.get(1), origin), origin))
            }
            "@msm_reset" -> {
                requireArity(row, 1, macro)
                listOf(ProfileOperation.PlatformReset(origin))
            }
            else -> reject("unknown macro: $macro")
        }

        private fun frequencyOperation(row: JSONArray, origin: OperationOrigin, minimum: Boolean): List<ProfileOperation> =
            policyValues(row, origin) {
                if (minimum) ProfileOperation.CpuFrequenciesMin(it, origin)
                else ProfileOperation.CpuFrequenciesMax(it, origin)
            }

        private fun policyValues(
            row: JSONArray,
            origin: OperationOrigin,
            factory: (List<ProfileValue>) -> ProfileOperation
        ): List<ProfileOperation> {
            if (row.length() !in 2..MAX_POLICY_COUNT + 1) reject("${row.optString(0)} has invalid policy count")
            return listOf(factory(valueArguments(row, origin)))
        }

        private fun valueArguments(row: JSONArray, origin: OperationOrigin): List<ProfileValue> =
            (1 until row.length()).map { parseValue(row.get(it), origin) }

        private fun captureTargets(name: String, chain: List<String>): List<KernelNode> {
            val expanded = expandPreset(name, chain)
            if (expanded.isEmpty() || expanded.any { it !is ProfileOperation.Capture }) {
                reject("@values preset $name must contain only capture targets")
            }
            return expanded.map { (it as ProfileOperation.Capture).target }
        }

        private fun resolveTarget(raw: String, origin: OperationOrigin): KernelNode {
            if (raw.startsWith("$")) {
                val name = raw.drop(1)
                if (!IDENTIFIER.matches(name)) reject("invalid alias reference at ${origin.section}[${origin.index}]")
                return aliases[name] ?: reject("unknown alias: $name")
            }
            return parsePath(raw, "${origin.section}[${origin.index}]")
        }

        private fun parsePath(raw: String, location: String): KernelNode {
            val value = raw.trim()
            if (value != raw || value.length !in 2..MAX_PATH_LENGTH || '\u0000' in value || '\\' in value) {
                reject("invalid path at $location")
            }
            if (value.any { it.isWhitespace() } || "//" in value || value.split('/').any { it == "." || it == ".." }) {
                reject("invalid path at $location")
            }
            if (ALLOWED_PATH_ROOTS.none { value == it || value.startsWith("$it/") }) {
                reject("path outside allowed kernel roots at $location")
            }
            if (!PATH.matches(value)) reject("invalid path characters at $location")
            return KernelNode(value)
        }

        private fun parseValue(value: Any, origin: OperationOrigin): ProfileValue {
            val raw = when (value) {
                is String -> value
                is Number, is Boolean -> value.toString()
                else -> reject("value at ${origin.section}[${origin.index}] must be scalar")
            }
            checkedText(raw, "${origin.section}[${origin.index}]")
            val notation = when {
                raw.startsWith("#") -> ValueNotation.HASH_PREFIXED
                raw.startsWith("^") -> ValueNotation.CARET_PREFIXED
                else -> ValueNotation.PLAIN
            }
            val text = if (notation == ValueNotation.PLAIN) raw else raw.drop(1)
            if (text.isEmpty()) reject("empty prefixed value at ${origin.section}[${origin.index}]")
            return ProfileValue(text, notation)
        }

        private fun identifierArgument(row: JSONArray, index: Int, macro: String): String {
            val value = row.opt(index) as? String ?: reject("$macro argument must be a string")
            if (!IDENTIFIER.matches(value)) reject("$macro contains an invalid identifier")
            return value
        }

        private fun requireArity(row: JSONArray, expected: Int, macro: String) {
            if (row.length() != expected) reject("$macro expects ${expected - 1} arguments")
        }

        private fun checkedText(value: String, location: String): String {
            if (value.length > MAX_VALUE_LENGTH || '\u0000' in value || value.any { it == '\r' || it == '\n' }) {
                reject("value at $location exceeds limits or contains control characters")
            }
            return value
        }

        private fun requiredObject(parent: JSONObject, key: String): JSONObject =
            parent.optJSONObject(key) ?: reject("$key must be an object")

        private fun requiredArray(parent: JSONObject, key: String): JSONArray =
            parent.optJSONArray(key) ?: reject("$key must be an array")

        private fun rejectUnknownKeys(value: JSONObject, allowed: Set<String>, location: String) {
            val unknown = value.keys().asSequence().firstOrNull { it !in allowed }
            if (unknown != null) reject("unknown key $location.$unknown")
        }

        private fun reject(reason: String): Nothing = throw RejectedProfile(reason)

        private data class ImportState(
            val active: List<ProfileOperation>,
            val inactive: List<ProfileOperation>
        )

        private data class ImportedModeParts(
            val call: List<ProfileOperation>,
            val state: ImportState,
            val metadata: Map<String, ProfileFeatureValue>
        )

        private data class ImportedContent(val modes: Map<ProfileMode, ImportedModeProgram>)
    }

    private class RejectedProfile(message: String) : RuntimeException(message)

    private companion object {
        const val MAX_DOCUMENT_BYTES = 1024 * 1024
        const val MAX_IMPORT_BYTES = 2L * 1024 * 1024
        const val MAX_IMPORTS = 32
        const val MAX_ALIASES = 256
        const val MAX_PRESETS = 256
        const val MAX_OPERATIONS_PER_ARRAY = 512
        const val MAX_SOURCE_OPERATIONS = 4096
        const val MAX_EXPANDED_OPERATIONS = 4096
        const val MAX_ARGUMENTS_PER_OPERATION = 33
        const val MAX_POLICY_COUNT = 32
        const val MAX_PRESET_DEPTH = 32
        const val MAX_FEATURE_DEPTH = 32
        const val MAX_FEATURE_NODES = 4096
        const val MAX_OBJECT_ENTRIES = 512
        const val MAX_ARRAY_ENTRIES = 512
        const val MAX_NAME_LENGTH = 64
        const val MAX_PATH_LENGTH = 256
        const val MAX_VALUE_LENGTH = 512
        const val MAX_APPLICATION_RULES = 512
        const val MAX_PACKAGES_PER_RULE = 64
        const val MAX_IMPORTED_MODE_ENTRIES = 16
        const val LEGACY_PEDESTAL_MODE = "pedestal"
        val ROOT_KEYS = setOf("alias", "features", "reset", "presets", "schemes", "apps", "games")
        val APPLICATION_REFERENCE_KEYS = setOf("friendly", "packages", "import")
        val IMPORT_ROOT_KEYS = setOf("friendly", "call", "state", "modes", "fas")
        val IMPORT_MODE_KEYS = setOf("mode", "call", "state", "fas")
        val IMPORT_STATE_KEYS = setOf("active", "inactive")
        val ALLOWED_PATH_ROOTS = setOf("/sys", "/proc", "/dev")
        val IDENTIFIER = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}")
        val CPU_POLICY = Regex("policy[0-9]{1,3}")
        val IMPORT_NAME = Regex("[A-Za-z0-9_.-]{1,64}\\.json")
        val PACKAGE_SELECTOR = Regex("(?:\\*|[A-Za-z0-9_]+(?:[.-][A-Za-z0-9_]+)*)")
        val PATH = Regex("/[A-Za-z0-9_./:+@-]+")
    }
}
