package dev.pocketagent.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val mode: String,
    val isMain: Boolean = false,
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** "user", "assistant" or "tool". Tool rows are shown in the UI but not replayed to the model. */
    val role: String,
    val content: String,
    val toolName: String? = null,
    val isError: Boolean = false,
    /** File name inside the media folder for an attached image. */
    val imageFile: String? = null,
    val createdAt: Long
)

@Entity(tableName = "feed_posts")
data class FeedPostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val emoji: String,
    val title: String,
    val body: String,
    val url: String? = null,
    /** 1 liked, -1 disliked, 0 neutral */
    val reaction: Int = 0,
    val createdAt: Long
)

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val note: String,
    val category: String,
    val done: Boolean = false,
    val createdAt: Long
)

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** "feed" writes the Feed; "prompt" runs [prompt] and saves the answer to the Library. */
    val kind: String,
    val prompt: String,
    val hour: Int,
    val minute: Int,
    /** "daily", "weekdays" or "weekly" (weekly runs on [dayOfWeek], 1 = Monday). */
    val repeat: String = "daily",
    val dayOfWeek: Int = 1,
    val enabled: Boolean = true,
    val lastRunAt: Long = 0,
    val lastResult: String = ""
)

@Entity(tableName = "artifacts")
data class ArtifactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val fileName: String,
    val format: String,
    val sizeBytes: Long,
    val createdAt: Long
)

@Entity(tableName = "media")
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val createdAt: Long
)

@Entity(tableName = "token_usage", primaryKeys = ["day", "provider"])
data class TokenUsageEntity(
    /** yyyy-MM-dd local date */
    val day: String,
    val provider: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val requests: Long
)

@Dao
interface ConversationDao {
    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateConversation(id: Long, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE conversations SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Query("SELECT * FROM conversations WHERE isMain = 0 AND archived = :archived ORDER BY updatedAt DESC")
    fun observeSideChats(archived: Boolean): Flow<List<ConversationEntity>>

    @Query(
        "SELECT DISTINCT c.* FROM conversations c LEFT JOIN messages m ON m.conversationId = c.id " +
            "WHERE c.title LIKE '%' || :q || '%' OR m.content LIKE '%' || :q || '%' ORDER BY c.updatedAt DESC LIMIT 50"
    )
    suspend fun search(q: String): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE isMain = 1 LIMIT 1")
    suspend fun mainConversation(): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun conversation(id: Long): ConversationEntity?

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    @Query("DELETE FROM messages WHERE conversationId = :id")
    suspend fun clearMessages(id: Long)

    @Query("DELETE FROM conversations WHERE isMain = 0")
    suspend fun deleteSideChats()

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun messages(conversationId: Long): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun messageCount(): Int
}

@Dao
interface ContentDao {
    // Feed
    @Query("SELECT * FROM feed_posts ORDER BY createdAt DESC, id ASC")
    fun observeFeed(): Flow<List<FeedPostEntity>>

    @Insert
    suspend fun insertPosts(posts: List<FeedPostEntity>)

    @Query("UPDATE feed_posts SET reaction = :reaction WHERE id = :id")
    suspend fun react(id: Long, reaction: Int)

    @Query("DELETE FROM feed_posts WHERE id = :id")
    suspend fun deletePost(id: Long)

    @Query("DELETE FROM feed_posts")
    suspend fun clearFeed()

    @Query("SELECT * FROM feed_posts WHERE reaction != 0 ORDER BY createdAt DESC LIMIT 20")
    suspend fun ratedPosts(): List<FeedPostEntity>

    // Goals
    @Query("SELECT * FROM goals ORDER BY done ASC, createdAt DESC")
    fun observeGoals(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE done = 0 ORDER BY createdAt DESC")
    suspend fun openGoals(): List<GoalEntity>

    @Insert
    suspend fun insertGoal(goal: GoalEntity): Long

    @Update
    suspend fun updateGoal(goal: GoalEntity)

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun deleteGoal(id: Long)

    // Routines
    @Query("SELECT * FROM routines ORDER BY hour, minute")
    fun observeRoutines(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines")
    suspend fun routines(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE id = :id")
    suspend fun routine(id: Long): RoutineEntity?

    @Insert
    suspend fun insertRoutine(routine: RoutineEntity): Long

    @Update
    suspend fun updateRoutine(routine: RoutineEntity)

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteRoutine(id: Long)

    // Library
    @Query("SELECT * FROM artifacts ORDER BY createdAt DESC")
    fun observeArtifacts(): Flow<List<ArtifactEntity>>

    @Insert
    suspend fun insertArtifact(artifact: ArtifactEntity): Long

    @Query("DELETE FROM artifacts WHERE id = :id")
    suspend fun deleteArtifact(id: Long)

    @Query("SELECT * FROM media ORDER BY createdAt DESC")
    fun observeMedia(): Flow<List<MediaEntity>>

    @Insert
    suspend fun insertMedia(media: MediaEntity): Long

    @Query("DELETE FROM media WHERE id = :id")
    suspend fun deleteMedia(id: Long)

    // Token usage
    @Query("SELECT * FROM token_usage ORDER BY day DESC, provider ASC")
    fun observeUsage(): Flow<List<TokenUsageEntity>>

    @Query("SELECT * FROM token_usage WHERE day = :day AND provider = :provider")
    suspend fun usage(day: String, provider: String): TokenUsageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putUsage(usage: TokenUsageEntity)

    @Query("DELETE FROM token_usage")
    suspend fun clearUsage()
}

@Database(
    entities = [
        ConversationEntity::class, MessageEntity::class, FeedPostEntity::class, GoalEntity::class,
        RoutineEntity::class, ArtifactEntity::class, MediaEntity::class, TokenUsageEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun content(): ContentDao

    companion object {
        const val NAME = "pocketagent.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .fallbackToDestructiveMigration()
                .build()
    }
}
