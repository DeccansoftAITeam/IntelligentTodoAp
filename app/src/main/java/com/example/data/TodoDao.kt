package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {
    // --- Todo Items ---
    @Query("SELECT * FROM todo_items ORDER BY createdAt DESC")
    fun getAllTodoItems(): Flow<List<TodoItem>>

    @Query("SELECT * FROM todo_items WHERE id = :id LIMIT 1")
    suspend fun getTodoItemById(id: Int): TodoItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTodoItem(item: TodoItem): Long

    @Update
    suspend fun updateTodoItem(item: TodoItem)

    @Query("DELETE FROM todo_items WHERE id = :id")
    suspend fun deleteTodoItemById(id: Int)

    @Query("DELETE FROM todo_items")
    suspend fun clearAllTodoItems()

    // --- Completion Logs ---
    @Query("SELECT * FROM completion_logs")
    fun getAllCompletionLogs(): Flow<List<CompletionLog>>

    @Query("SELECT * FROM completion_logs WHERE todoId = :todoId")
    suspend fun getLogsForTodo(todoId: Int): List<CompletionLog>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCompletionLog(log: CompletionLog)

    @Query("DELETE FROM completion_logs WHERE todoId = :todoId AND completedDate = :dateMidnight")
    suspend fun deleteCompletionLog(todoId: Int, dateMidnight: Long)

    @Query("DELETE FROM completion_logs WHERE todoId = :todoId")
    suspend fun clearLogsForTodo(todoId: Int)

    // --- App Settings ---
    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    fun getSettingsFlow(): Flow<AppSettings?>

    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettings(): AppSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettings(settings: AppSettings)

    // --- Chat Messages ---
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun getAllChatMessages(): Flow<List<ChatMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatMessage(message: ChatMessage)

    @Query("DELETE FROM chat_messages")
    suspend fun clearChatHistory()
}
