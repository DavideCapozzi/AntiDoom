package com.antidoom.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.LocalDate

@Entity(tableName = "scroll_sessions")
data class ScrollSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val distanceMeters: Float,
    val timestamp: Long,
    val date: String
)

@Dao
interface ScrollDao {
    @Insert
    suspend fun insert(session: ScrollSession)

    @Query("SELECT COALESCE(SUM(distanceMeters), 0) FROM scroll_sessions WHERE date = :date")
    fun getDailyDistance(date: String): Flow<Float>
}

@Database(entities = [ScrollSession::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scrollDao(): ScrollDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            Room.databaseBuilder(context, AppDatabase::class.java, "antidoom.db").build().also { INSTANCE = it }
        }
    }
}

val Context.dataStore by preferencesDataStore("settings")

class UserPreferences(private val context: Context) {

    // Whitelist approach: Only apps in this set are tracked
    private val trackedAppsKey = stringSetPreferencesKey("tracked_apps")
    private val dailyLimitKey = floatPreferencesKey("daily_limit_meters")

    // Default CORE apps enabled out-of-the-box
    private val defaultCoreApps = setOf(
        "com.instagram.android",
        "com.zhiliaoapp.musically", // TikTok (Global)
        "com.ss.android.ugc.trill", // TikTok (Alternative/Asia)
        "com.google.android.youtube"
    )

    @Suppress("SpellCheckingInspection")
    val trackedApps: Flow<Set<String>> = context.dataStore.data.map {
        // If key doesn't exist, use defaultCoreApps.
        // If user clears everything, it saves an empty set, so this default only applies on fresh install/reset.
        it[trackedAppsKey] ?: defaultCoreApps
    }

    // Default limit is 100 meters
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
}

class ScrollRepository private constructor(context: Context) {
    private val db = AppDatabase.get(context)

    private val _activeSessionDistance = MutableStateFlow(0f)

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

    companion object {
        @Volatile private var INSTANCE: ScrollRepository? = null
        fun get(context: Context): ScrollRepository = INSTANCE ?: synchronized(this) {
            INSTANCE ?: ScrollRepository(context.applicationContext).also { INSTANCE = it }
        }
    }
}