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

@Database(entities = [ScrollSession::class], version = 1)
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
    private val TRACKED_APPS_KEY = stringSetPreferencesKey("tracked_apps")

    val trackedApps: Flow<Set<String>> = context.dataStore.data.map {
        it[TRACKED_APPS_KEY] ?: setOf("com.instagram.android", "com.zhiliaoapp.musically", "com.google.android.youtube")
    }

    suspend fun addApp(pkg: String) {
        context.dataStore.edit { it[TRACKED_APPS_KEY] = (it[TRACKED_APPS_KEY] ?: emptySet()) + pkg }
    }
}

class ScrollRepository private constructor(context: Context) {
    private val db = AppDatabase.get(context)

    private val _activeSessionDistance = MutableStateFlow(0f)

    fun getDailyDistanceFlow(date: String): Flow<Float> {
        return db.scrollDao().getDailyDistance(date)
            .combine(_activeSessionDistance) { dbTotal, ramTotal ->
                (dbTotal ?: 0f) + ramTotal
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