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

// --- ENTITIES ---
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

@Entity(
    tableName = "daily_app_history",
    primaryKeys = ["date", "packageName"]
)
data class DailyAppHistory(
    val date: String,
    val packageName: String,
    val totalMeters: Float
)

data class AppDistanceTuple(
    @ColumnInfo(name = "packageName") val packageName: String,
    @ColumnInfo(name = "total") val total: Float
)

// NEW: Data class for Weekly Chart
data class DailyTotalTuple(
    @ColumnInfo(name = "date") val date: String,
    @ColumnInfo(name = "total") val total: Float
)

// --- DAO ---
@Dao
interface ScrollDao {
    @Insert
    suspend fun insert(session: ScrollSession)

    @Query("SELECT COALESCE(SUM(distanceMeters), 0) FROM scroll_sessions WHERE date = :date")
    fun getDailyDistance(date: String): Flow<Float>

    @Query("SELECT packageName, COALESCE(SUM(distanceMeters), 0) as total FROM scroll_sessions WHERE date = :date GROUP BY packageName")
    fun getDailyBreakdown(date: String): Flow<List<AppDistanceTuple>>

    // NEW: Get daily totals for the last 7 days for the chart
    @Query("""
        SELECT date, COALESCE(SUM(distanceMeters), 0) as total 
        FROM scroll_sessions 
        GROUP BY date 
        ORDER BY date DESC 
        LIMIT 7
    """)
    fun getLast7DaysTotals(): Flow<List<DailyTotalTuple>>

    @Query("""
        INSERT OR REPLACE INTO daily_app_history (date, packageName, totalMeters)
        SELECT date, packageName, SUM(distanceMeters)
        FROM scroll_sessions
        WHERE date < :thresholdDate
        GROUP BY date, packageName
    """)
    suspend fun archiveOldData(thresholdDate: String)

    @Query("DELETE FROM scroll_sessions WHERE date < :thresholdDate")
    suspend fun deleteRawData(thresholdDate: String)

    @Transaction
    suspend fun performArchiveAndCleanup(thresholdDate: String) {
        archiveOldData(thresholdDate)
        deleteRawData(thresholdDate)
    }

    @Query("SELECT * FROM daily_app_history WHERE date BETWEEN :startDate AND :endDate")
    fun getHistoryRange(startDate: String, endDate: String): Flow<List<DailyAppHistory>>
}

// --- DATABASE ---
@Database(entities = [ScrollSession::class, DailyAppHistory::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scrollDao(): ScrollDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "antidoom.db")
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
        }
    }
}

// --- PREFERENCES ---
val Context.dataStore by preferencesDataStore("settings")

class UserPreferences private constructor(private val context: Context) {

    companion object {
        @Volatile private var INSTANCE: UserPreferences? = null
        fun get(context: Context): UserPreferences = INSTANCE ?: synchronized(this) {
            INSTANCE ?: UserPreferences(context.applicationContext).also { INSTANCE = it }
        }
    }

    private val trackedAppsKey = stringSetPreferencesKey("tracked_apps")
    private val dailyLimitKey = floatPreferencesKey("daily_limit_meters")
    private val appLimitsKey = stringPreferencesKey("app_limits_json")
    private val generalLockedUntilKey = longPreferencesKey("general_locked_until_ts")
    private val appLockedUntilKey = longPreferencesKey("app_limits_locked_until_ts")

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

    val isGeneralLocked: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val until = prefs[generalLockedUntilKey] ?: 0L
        System.currentTimeMillis() < until
    }

    suspend fun setGeneralLock(durationMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[generalLockedUntilKey] = System.currentTimeMillis() + durationMs
        }
    }

    val isAppLimitsLocked: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val until = prefs[appLockedUntilKey] ?: 0L
        System.currentTimeMillis() < until
    }

    suspend fun setAppLimitsLock(durationMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[appLockedUntilKey] = System.currentTimeMillis() + durationMs
        }
    }

    val appLimits: Flow<Map<String, Float>> = context.dataStore.data.map { prefs ->
        val json = prefs[appLimitsKey] ?: ""
        parseAppLimitsOptimized(json)
    }

    suspend fun updateAppLimit(packageName: String, limit: Float?) {
        context.dataStore.edit { prefs ->
            val currentJson = prefs[appLimitsKey] ?: ""
            val currentMap = parseAppLimitsOptimized(currentJson).toMutableMap()
            if (limit == null) {
                currentMap.remove(packageName)
            } else {
                currentMap[packageName] = limit
            }
            prefs[appLimitsKey] = formatAppLimits(currentMap)
        }
    }

    private fun parseAppLimitsOptimized(raw: String): Map<String, Float> {
        if (raw.isEmpty() || raw == "{}") return emptyMap()

        if (raw.startsWith("{") && raw.contains("\"")) {
            return try {
                val map = mutableMapOf<String, Float>()
                val jsonObj = org.json.JSONObject(raw)
                jsonObj.keys().forEach { key ->
                    map[key] = jsonObj.getDouble(key).toFloat()
                }
                map
            } catch (e: Exception) { emptyMap() }
        }

        val map = mutableMapOf<String, Float>()
        val entries = raw.split(',')
        for (entry in entries) {
            val idx = entry.lastIndexOf(':')
            if (idx != -1) {
                val pkg = entry.substring(0, idx)
                val limitStr = entry.substring(idx + 1)
                val limit = limitStr.toFloatOrNull() ?: 0f
                map[pkg] = limit
            }
        }
        return map
    }

    private fun formatAppLimits(map: Map<String, Float>): String {
        return map.entries.joinToString(",") { "${it.key}:${it.value}" }
    }
}

// --- REPOSITORY ---
class ScrollRepository private constructor(context: Context) {
    private val db = AppDatabase.get(context)
    private val _activeSessionDistance = MutableStateFlow(0f)
    private val repoScope = CoroutineScope(Dispatchers.IO)

    init {
        repoScope.launch {
            try {
                val thresholdDate = LocalDate.now().minusDays(7).toString()
                db.scrollDao().performArchiveAndCleanup(thresholdDate)
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

    // NEW: Expose weekly stats
    fun getLast7DaysStats(): Flow<List<DailyTotalTuple>> {
        return db.scrollDao().getLast7DaysTotals()
        // Note: We are not combining with _activeSessionDistance here to keep it simple
        // and because historical days don't change.
        // For "Today", it might lag slightly behind the live counter on Home, which is acceptable for a generic chart.
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
            _activeSessionDistance.update { (it - distance).coerceAtLeast(0f) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getDailyAppDistancesFlow(date: String): Flow<Map<String, Float>> {
        return db.scrollDao().getDailyBreakdown(date)
            .combine(_activeSessionDistance) { dbList, _ ->
                dbList.associate { it.packageName to it.total }.toMutableMap()
            }
    }

    companion object {
        @Volatile private var INSTANCE: ScrollRepository? = null
        fun get(context: Context): ScrollRepository = INSTANCE ?: synchronized(this) {
            INSTANCE ?: ScrollRepository(context.applicationContext).also { INSTANCE = it }
        }
    }
}