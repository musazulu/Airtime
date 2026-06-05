package com.example.airtimescanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "recharge_store"
private const val PREF_KEY_RECORDS = "records"
private const val THIRTY_DAYS_MS = 30L * 24L * 60L * 60L * 1000L

data class RechargeRecord(
    val key: String,
    val scannedAt: Long,
    val redeemedAt: Long? = null
) {
    val expiresAt: Long
        get() = scannedAt + THIRTY_DAYS_MS

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        return now >= expiresAt
    }
}

class RechargeStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadRecords(): List<RechargeRecord> {
        val now = System.currentTimeMillis()
        val raw = prefs.getString(PREF_KEY_RECORDS, "[]").orEmpty()
        val records = parseRecords(raw)
            .filterNot { it.isExpired(now) }
            .sortedByDescending { it.scannedAt }

        if (records.size != parseRecords(raw).size) {
            saveRecords(records)
        }

        return records
    }

    fun upsertDetectedKeys(keys: List<String>) {
        if (keys.isEmpty()) return

        val now = System.currentTimeMillis()
        val existing = loadRecords().associateBy { it.key }.toMutableMap()

        keys.forEach { key ->
            if (!existing.containsKey(key)) {
                existing[key] = RechargeRecord(
                    key = key,
                    scannedAt = now,
                    redeemedAt = null
                )
            }
        }

        saveRecords(existing.values.sortedByDescending { it.scannedAt })
    }

    fun markRedeemed(key: String) {
        if (key.isBlank()) return

        val now = System.currentTimeMillis()
        val updated = loadRecords().map { record ->
            if (record.key == key && record.redeemedAt == null) {
                record.copy(redeemedAt = now)
            } else {
                record
            }
        }
        saveRecords(updated)
    }

    private fun saveRecords(records: List<RechargeRecord>) {
        val array = JSONArray()
        records
            .filterNot { it.isExpired() }
            .forEach { record ->
                array.put(
                    JSONObject().apply {
                        put("key", record.key)
                        put("scannedAt", record.scannedAt)
                        if (record.redeemedAt != null) {
                            put("redeemedAt", record.redeemedAt)
                        } else {
                            put("redeemedAt", JSONObject.NULL)
                        }
                    }
                )
            }

        prefs.edit().putString(PREF_KEY_RECORDS, array.toString()).apply()
    }

    private fun parseRecords(raw: String): List<RechargeRecord> {
        if (raw.isBlank()) return emptyList()

        val array = JSONArray(raw)
        val results = mutableListOf<RechargeRecord>()

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val key = obj.optString("key").trim()
            if (key.isBlank()) continue

            val scannedAt = obj.optLong("scannedAt", System.currentTimeMillis())
            val redeemedAt = if (obj.isNull("redeemedAt")) null else obj.optLong("redeemedAt")

            results += RechargeRecord(
                key = key,
                scannedAt = scannedAt,
                redeemedAt = redeemedAt
            )
        }

        return results.distinctBy { it.key }
    }
}
