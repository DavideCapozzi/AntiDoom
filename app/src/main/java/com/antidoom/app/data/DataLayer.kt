package com.antidoom.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

// Added indices on 'date' and 'packageName' to speed up lookup queries.
// 'date' is used in every daily stats query, making this crucial for performance.
@Entity(
    tableName = "scroll_sessions",
    indices = [
        Index(value = ["date"]),
        Index(value = ["packageName"])
    ]
)
data class ScrollSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val distanceMeters: Float,
    val timestamp: Long,
    val date: String
)

data class AppDistanceTuple(
    @ColumnInfo(name = "packageName") val packageName: String,
    @ColumnInfo(name = "total") val total: Float
)

@Dao
interface ScrollDao {
    @Insert
    suspend fun insert(session: ScrollSession)

    // Uses Index on 'date' for faster aggregation
    @Query("SELECT COALESCE(SUM(distanceMeters), 0) FROM scroll_sessions WHERE date = :date")
    fun getDailyDistance(date: String): Flow<Float>

    @Query("SELECT packageName, COALESCE(SUM(distanceMeters), 0) as total FROM scroll_sessions WHERE date = :date GROUP BY packageName")
    fun getDailyBreakdown(date: String): Flow<List<AppDistanceTuple>>

    // Maintenance query to remove old data and save space
    @Query("DELETE FROM scroll_sessions WHERE date < :thresholdDate")
    suspend fun deleteOlderThan(thresholdDate: String)
}

// Bumped version to 2 to support Schema changes (Indices).
// fallbackToDestructiveMigration() will WIPE existing data on the first run.
// In a production app, you should provide a Migration strategy.
@Database(entities = [ScrollSession::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scrollDao(): ScrollDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            Room.databaseBuilder(context, AppDatabase::class.java, "antidoom.db")
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
        }
    }
}

val Context.dataStore by preferencesDataStore("settings")

class UserPreferences(private val context: Context) {

    // Keys
    private val trackedAppsKey = stringSetPreferencesKey("tracked_apps")
    private val dailyLimitKey = floatPreferencesKey("daily_limit_meters")
    private val appLimitsKey = stringPreferencesKey("app_limits_json")

    // --- DUAL LOCK KEYS ---
    private val generalLockedUntilKey = longPreferencesKey("general_locked_until_ts")
    private val appLockedUntilKey = longPreferencesKey("app_limits_locked_until_ts")

    // Default CORE apps
    private val defaultCoreApps = setOf(
        "com.instagram.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.google.android.youtube"
    )

    val trackedApps: Flow<Set<String>> = context.dataStore.data.map {
        it[trackedAppsKey] ?: defaultCoreApps
    }

    val dailyLimit: Flow<Float> = context.dataStore.data.map {
        it[dailyLimitKey] ?: 100f
    }

    suspend fun updateDailyLimit(meters: Float) {
        context.dataStore.edit { preferences ->
            preferences[dailyLimitKey] = meters
        }
    }

    suspend fun updateTrackedApps(apps: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[trackedAppsKey] = apps
        }
    }

    // --- LOCK 1: GENERAL (Global Limit + Tracked Apps List) ---
    val isGeneralLocked: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val until = prefs[generalLockedUntilKey] ?: 0L
        System.currentTimeMillis() < until
    }

    suspend fun setGeneralLock(durationMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[generalLockedUntilKey] = System.currentTimeMillis() + durationMs
        }
    }

    // --- LOCK 2: PER-APP LIMITS ---
    val isAppLimitsLocked: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val until = prefs[appLockedUntilKey] ?: 0L
        System.currentTimeMillis() < until
    }

    suspend fun setAppLimitsLock(durationMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[appLockedUntilKey] = System.currentTimeMillis() + durationMs
        }
    }

    // Optimized limit parsing to avoid potential overhead if string is malformed
    val appLimits: Flow<Map<String, Float>> = context.dataStore.data.map { prefs ->
        val json = prefs[appLimitsKey] ?: "{}"
        parseAppLimits(json)
    }

    suspend fun updateAppLimit(packageName: String, limit: Float?) {
        context.dataStore.edit { prefs ->
            val currentJson = prefs[appLimitsKey] ?: "{}"
            val currentMap = parseAppLimits(currentJson).toMutableMap()
            if (limit == null) {
                currentMap.remove(packageName)
            } else {
                currentMap[packageName] = limit
            }
            prefs[appLimitsKey] = formatAppLimits(currentMap)
        }
    }

    // Helpers JSON
    private fun parseAppLimits(json: String): Map<String, Float> {
        if (json == "{}" || json.isEmpty()) return emptyMap()
        val map = mutableMapOf<String, Float>()
        try {
            json.split(",").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val pkg = parts[0]
                    val limit = parts[1].toFloatOrNull() ?: 0f
                    map[pkg] = limit
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return map
    }

    private fun formatAppLimits(map: Map<String, Float>): String {
        return map.entries.joinToString(",") { "${it.key}:${it.value}" }
    }
}

class ScrollRepository private constructor(context: Context) {
    private val db = AppDatabase.get(context)

    // Tracks current session in RAM to avoid excessive DB writes
    private val _activeSessionDistance = MutableStateFlow(0f)

    // Scope for background maintenance tasks
    private val repoScope = CoroutineScope(Dispatchers.IO)

    init {
        // Fire-and-forget maintenance on startup.
        // Deletes records older than 7 days to keep DB lean.
        repoScope.launch {
            try {
                // Using ISO-8601 string comparison for date
                val thresholdDate = LocalDate.now().minusDays(7).toString()
                db.scrollDao().deleteOlderThan(thresholdDate)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getDailyDistanceFlow(date: String): Flow<Float> {
        return db.scrollDao().getDailyDistance(date)
            .combine(_activeSessionDistance) { dbTotal, ramTotal ->
                dbTotal + ramTotal
            }
    }

    fun updateActiveDistance(meters: Float) {
        _activeSessionDistance.value = meters
    }

    suspend fun flushSessionToDb(packageName: String, distance: Float) {
        if (distance <= 0) return

        try {
            val session = ScrollSession(
                packageName = packageName,
                distanceMeters = distance,
                timestamp = System.currentTimeMillis(),
                date = LocalDate.now().toString()
            )
            db.scrollDao().insert(session)

            // Subtract flushed distance from RAM accumulator to avoid double counting
            // when the UI combines DB + RAM.
            _activeSessionDistance.update { (it - distance).coerceAtLeast(0f) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getDailyAppDistancesFlow(date: String): Flow<Map<String, Float>> {
        return db.scrollDao().getDailyBreakdown(date)
            .combine(_activeSessionDistance) { dbList, _ ->
                // Note: The active RAM session is currently NOT attributed to a specific app
                // in this breakdown view, only to the global total.
                // This preserves original behavior.
                val map = dbList.associate { it.packageName to it.total }.toMutableMap()
                map
            }
    }

    companion object {
        @Volatile private var INSTANCE: ScrollRepository? = null
        fun get(context: Context): ScrollRepository = INSTANCE ?: synchronized(this) {
            INSTANCE ?: ScrollRepository(context.applicationContext).also { INSTANCE = it }
        }
    }
}