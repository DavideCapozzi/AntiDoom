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

// 1. RAW DATA (Short Term: 7 Days)
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

// 2. ARCHIVED HISTORY (Long Term: Forever)
// Questa tabella salva solo il TOTALE giornaliero per ogni app.
// Composite Primary Key assicura che ci sia una sola riga per App+Data.
@Entity(
    tableName = "daily_app_history",
    primaryKeys = ["date", "packageName"]
)
data class DailyAppHistory(
    val date: String,
    val packageName: String,
    val totalMeters: Float
)

// Helper class for UI logic (unchanged)
data class AppDistanceTuple(
    @ColumnInfo(name = "packageName") val packageName: String,
    @ColumnInfo(name = "total") val total: Float
)

// --- DAO ---

@Dao
interface ScrollDao {
    // --- RAW OPERATIONS ---
    @Insert
    suspend fun insert(session: ScrollSession)

    @Query("SELECT COALESCE(SUM(distanceMeters), 0) FROM scroll_sessions WHERE date = :date")
    fun getDailyDistance(date: String): Flow<Float>

    @Query("SELECT packageName, COALESCE(SUM(distanceMeters), 0) as total FROM scroll_sessions WHERE date = :date GROUP BY packageName")
    fun getDailyBreakdown(date: String): Flow<List<AppDistanceTuple>>

    // --- ARCHIVING OPERATIONS ---

    // Passo 1: Aggrega i dati grezzi vecchi e li copia nella tabella storico
    // Usa INSERT OR REPLACE per evitare duplicati se la manutenzione gira due volte
    @Query("""
        INSERT OR REPLACE INTO daily_app_history (date, packageName, totalMeters)
        SELECT date, packageName, SUM(distanceMeters)
        FROM scroll_sessions
        WHERE date < :thresholdDate
        GROUP BY date, packageName
    """)
    suspend fun archiveOldData(thresholdDate: String)

    // Passo 2: Cancella i dati grezzi che abbiamo appena archiviato
    @Query("DELETE FROM scroll_sessions WHERE date < :thresholdDate")
    suspend fun deleteRawData(thresholdDate: String)

    // Transazione atomica: o fa tutto (copia + cancella) o niente. Sicurezza dati garantita.
    @Transaction
    suspend fun performArchiveAndCleanup(thresholdDate: String) {
        archiveOldData(thresholdDate)
        deleteRawData(thresholdDate)
    }

    // --- FUTURE HISTORY QUERIES (Esempi per il futuro) ---
    // Potrai usare questa per i grafici storici
    @Query("SELECT * FROM daily_app_history WHERE date BETWEEN :startDate AND :endDate")
    fun getHistoryRange(startDate: String, endDate: String): Flow<List<DailyAppHistory>>
}

// --- DATABASE ---

// Bumped version to 3 for new History Table
@Database(entities = [ScrollSession::class, DailyAppHistory::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scrollDao(): ScrollDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            Room.databaseBuilder(context, AppDatabase::class.java, "antidoom.db")
                .fallbackToDestructiveMigration() // Wipe DB on schema change (Dev mode)
                .build()
                .also { INSTANCE = it }
        }
    }
}

// --- PREFERENCES (Unchanged) ---
val Context.dataStore by preferencesDataStore("settings")

class UserPreferences(private val context: Context) {
    // ... (Il codice di UserPreferences rimane invariato, l'ho omesso per brevità)
    // Assumo che tu abbia il codice precedente per questa classe.
    // Se ti serve ricopiato, fammelo sapere.

    // Keys
    private val trackedAppsKey = stringSetPreferencesKey("tracked_apps")
    private val dailyLimitKey = floatPreferencesKey("daily_limit_meters")
    private val appLimitsKey = stringPreferencesKey("app_limits_json")
    private val generalLockedUntilKey = longPreferencesKey("general_locked_until_ts")
    private val appLockedUntilKey = longPreferencesKey("app_limits_locked_until_ts")

    // Default CORE apps
    private val defaultCoreApps = setOf(
        "com.instagram.android",
        "com.zhiliaoapp.musically", // TikTok
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

// --- REPOSITORY ---

class ScrollRepository private constructor(context: Context) {
    private val db = AppDatabase.get(context)
    private val _activeSessionDistance = MutableStateFlow(0f)
    private val repoScope = CoroutineScope(Dispatchers.IO)

    init {
        // MAINTENANCE ROUTINE
        // Sposta i dati vecchi (> 7 giorni) nello storico e pulisce il raw.
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