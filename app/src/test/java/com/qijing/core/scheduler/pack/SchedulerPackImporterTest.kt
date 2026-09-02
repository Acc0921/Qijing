package com.qijing.core.scheduler.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SchedulerPackImporterTest {
    @Test fun `imports recognized files and creates order independent stable identities`() {
        val entries = validEntries()
        val first = import(zip(entries)) as SchedulerPackImportResult.Imported
        val reordered = import(zip(entries.toList().reversed().toMap())) as SchedulerPackImportResult.Imported

        assertEquals("scene-pack:scene_config_replace", first.pack.id)
        assertEquals(first.pack.id, reordered.pack.id)
        assertEquals(first.pack.contentFingerprintSha256, reordered.pack.contentFingerprintSha256)
        assertEquals(first.pack.variants.single().id, reordered.pack.variants.single().id)
        assertEquals("Demo module", first.pack.module.name)
        assertEquals("6+2", first.pack.variants.single().hardware.topology.canonical)
        assertTrue(first.pack.variants.single().hardware.socModels.containsAll(setOf("sm8750", "sm8750p", "sm8750ab")))
        assertEquals(setOf("sun"), first.pack.variants.single().hardware.platforms)
        assertEquals("Configuration notes", first.pack.variants.single().description)
        assertEquals("{\"friendly\":\"Apps\"}", first.pack.variants.single().imports["_Apps.json"])
    }

    @Test fun `matches SoC platform core set and reported cluster topology`() {
        val variant = (import(zip(validEntries())) as SchedulerPackImportResult.Imported).pack.variants.single()
        val supported = SchedulerPackDevice(
            socModel = "SM8750P",
            platform = "SUN",
            cpuCores = (0..7).toSet(),
            clusterCoreSets = listOf((0..5).toSet(), setOf(6, 7))
        )
        val wrongDevice = SchedulerPackDevice(
            socModel = "sm845",
            platform = "sdm845",
            cpuCores = (0..7).toSet(),
            clusterCoreSets = listOf(setOf(0, 1, 2, 3), setOf(4, 5, 6, 7))
        )

        assertTrue(variant.compatibilityWith(supported).compatible)
        val mismatch = variant.compatibilityWith(wrongDevice)
        assertFalse(mismatch.compatible)
        assertTrue(SchedulerPackMismatch.SOC_NOT_SUPPORTED in mismatch.mismatches)
        assertTrue(SchedulerPackMismatch.PLATFORM_NOT_SUPPORTED in mismatch.mismatches)
        assertTrue(SchedulerPackMismatch.CPU_TOPOLOGY_MISMATCH in mismatch.mismatches)
    }

    @Test fun `manifest can declare a generic SoC and hardware contract`() {
        val entries = validEntries().toMutableMap()
        entries["Config/4+4/vendorX/balanced/V1/manifest.json"] = """
            {
              "version":"V1",
              "compatibility": {
                "socIdentifiers":["vendor-x"],
                "socModels":["SOC-9000"],
                "platforms":["platform-x"],
                "cpuTopology":"4+4",
                "cpuCores":[0,1,2,3,4,5,6,7]
              }
            }
        """.trimIndent()
        entries["Config/4+4/vendorX/balanced/V1/profile.json"] = "{}"
        entries["Config/4+4/vendorX/balanced/V1/threads.json"] = "[]"

        val imported = import(zip(entries)) as SchedulerPackImportResult.Imported
        val variant = imported.pack.variants.first { it.hardware.topology.canonical == "4+4" }
        assertTrue(
            variant.compatibilityWith(
                SchedulerPackDevice(
                    "soc9000",
                    "platform_x",
                    (0..7).toSet(),
                    listOf((0..3).toSet(), (4..7).toSet())
                )
            ).compatible
        )
    }

    @Test fun `rejects traversal absolute backslash and case folded duplicate paths`() {
        listOf(
            "../module.prop",
            "/module.prop",
            "C:/module.prop",
            "Config\\6+2\\8E\\profile.json"
        ).forEach { unsafe ->
            val result = import(zip(linkedMapOf(unsafe to "x")))
            assertEquals(SchedulerPackRejectReason.UNSAFE_ENTRY_PATH, (result as SchedulerPackImportResult.Rejected).reason)
        }

        val duplicate = zip(
            linkedMapOf(
                "module.prop" to "id=a\nname=A",
                "MODULE.PROP" to "id=a\nname=A"
            )
        )
        val result = import(duplicate) as SchedulerPackImportResult.Rejected
        assertEquals(SchedulerPackRejectReason.DUPLICATE_ENTRY, result.reason)
    }

    @Test fun `enforces entry count entry size expanded size and archive size limits`() {
        val twoFiles = zip(linkedMapOf("module.prop" to "id=a\nname=A", "ignored" to "x"))
        assertEquals(
            SchedulerPackRejectReason.TOO_MANY_ENTRIES,
            reject(twoFiles, SchedulerPackLimits(maxEntries = 1)).reason
        )

        val largeEntry = zip(linkedMapOf("module.prop" to "id=a\nname=${"a".repeat(200)}"))
        assertEquals(
            SchedulerPackRejectReason.ENTRY_TOO_LARGE,
            reject(largeEntry, SchedulerPackLimits(maxEntryBytes = 32)).reason
        )

        val expanded = zip(linkedMapOf("module.prop" to "id=a\nname=A", "ignored" to "x".repeat(100)))
        assertEquals(
            SchedulerPackRejectReason.EXPANDED_CONTENT_TOO_LARGE,
            reject(expanded, SchedulerPackLimits(maxExpandedBytes = 32)).reason
        )

        val archive = zip(validEntries())
        assertEquals(
            SchedulerPackRejectReason.ARCHIVE_TOO_LARGE,
            reject(archive, SchedulerPackLimits(maxArchiveBytes = 32)).reason
        )
    }

    @Test fun `rejects incomplete variants and malformed configuration JSON`() {
        val incomplete = validEntries().filterKeys { !it.endsWith("threads.json") }
        assertEquals(
            SchedulerPackRejectReason.INCOMPLETE_VARIANT,
            (import(zip(incomplete)) as SchedulerPackImportResult.Rejected).reason
        )

        val malformed = validEntries().toMutableMap()
        malformed["Config/6+2/8E/balanced/V1/profile.json"] = "[]"
        assertEquals(
            SchedulerPackRejectReason.INVALID_CONFIGURATION_JSON,
            (import(zip(malformed)) as SchedulerPackImportResult.Rejected).reason
        )
    }

    private fun validEntries(): LinkedHashMap<String, String> = linkedMapOf(
        "Config/" to "",
        "Config/6+2/" to "",
        "module.prop" to """
            id=Scene_Config_replace
            name=Demo module
            version=1.0
            versionCode=10
            author=Tester
        """.trimIndent(),
        "Config/6+2/8E/balanced/V1/profile.json" to "{\"alias\":{}}",
        "Config/6+2/8E/balanced/V1/threads.json" to "[]",
        "Config/6+2/8E/balanced/V1/manifest.json" to """
            {"version":"V1","versionCode":1,"features":{"strict":true}}
        """.trimIndent(),
        "Config/6+2/8E/balanced/V1/_Apps.json" to "{\"friendly\":\"Apps\"}",
        "Config/6+2/8E/balanced/V1/description.txt" to "Configuration notes",
        "scripts/dangerous.sh" to "rm -rf /data"
    )

    private fun import(bytes: ByteArray): SchedulerPackImportResult =
        SchedulerPackImporter().import(ByteArrayInputStream(bytes))

    private fun reject(bytes: ByteArray, limits: SchedulerPackLimits): SchedulerPackImportResult.Rejected =
        SchedulerPackImporter(limits).import(ByteArrayInputStream(bytes)) as SchedulerPackImportResult.Rejected

    private fun zip(entries: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                if (!path.endsWith('/')) zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
