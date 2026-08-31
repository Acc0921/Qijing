package com.qijing.debug.tuning

import android.content.Context

/** Debug-only persistent store used by emulator instrumentation and restart recovery checks. */
class DebugSharedPreferencesStateStore(context: Context) : DebugTuningStateStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun <T> exclusive(block: () -> T): T = synchronized(STORE_LOCK) { block() }

    override fun loadValues(): DebugTuningValues = exclusive { DebugTuningValues(
        governor = preferences.getString(KEY_GOVERNOR, null) ?: "schedutil",
        minFrequencyKHz = preferences.getLong(KEY_MIN, 300_000L),
        maxFrequencyKHz = preferences.getLong(KEY_MAX, 2_400_000L),
        swappiness = preferences.getInt(KEY_SWAPPINESS, 60)
    ) }

    override fun loadJournal(): DebugJournalLoad = exclusive {
        val encoded = preferences.getString(KEY_JOURNAL, null) ?: return@exclusive DebugJournalLoad.None
        runCatching { decodeJournal(encoded) }
            .fold(onSuccess = DebugJournalLoad::Loaded, onFailure = { DebugJournalLoad.Corrupt(it.message ?: "invalid journal") })
    }

    override fun commit(values: DebugTuningValues, journal: DebugTuningJournal?): Boolean = exclusive {
        val editor = preferences.edit()
            .putString(KEY_GOVERNOR, values.governor)
            .putLong(KEY_MIN, values.minFrequencyKHz)
            .putLong(KEY_MAX, values.maxFrequencyKHz)
            .putInt(KEY_SWAPPINESS, values.swappiness)
        if (journal == null) editor.remove(KEY_JOURNAL) else editor.putString(KEY_JOURNAL, encodeJournal(journal))
        editor.commit()
    }

    fun clear(): Boolean = exclusive { preferences.edit().clear().commit() }

    private fun encodeJournal(journal: DebugTuningJournal): String {
        val original = journal.original
        val records = journal.records.joinToString(";") { "${it.capability},${it.phase.name}" }
        return listOf(
            SCHEMA_VERSION,
            journal.transactionId,
            original.governor,
            original.minFrequencyKHz,
            original.maxFrequencyKHz,
            original.swappiness,
            records
        ).joinToString("|")
    }

    private fun decodeJournal(encoded: String): DebugTuningJournal {
        val parts = encoded.split('|', limit = 7)
        require(parts.size == 7 && parts[0] == SCHEMA_VERSION) { "unsupported debug journal schema" }
        val records = if (parts[6].isBlank()) emptyList() else parts[6].split(';').map { encodedRecord ->
            val fields = encodedRecord.split(',', limit = 2)
            require(fields.size == 2) { "invalid debug journal record" }
            DebugWriteRecord(fields[0], DebugWritePhase.valueOf(fields[1]))
        }
        return DebugTuningJournal(
            transactionId = parts[1].also { require(it.isNotBlank()) },
            original = DebugTuningValues(parts[2], parts[3].toLong(), parts[4].toLong(), parts[5].toInt()),
            records = records
        ).also { journal -> require(journal.validationError() == null) { journal.validationError() ?: "invalid journal" } }
    }

    private companion object {
        const val PREFERENCES_NAME = "qijing_debug_tuning_sim_v1"
        const val SCHEMA_VERSION = "1"
        const val KEY_GOVERNOR = "governor"
        const val KEY_MIN = "min_frequency_khz"
        const val KEY_MAX = "max_frequency_khz"
        const val KEY_SWAPPINESS = "swappiness"
        const val KEY_JOURNAL = "journal"
        val STORE_LOCK = Any()
    }
}
