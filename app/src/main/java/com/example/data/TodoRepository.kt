package com.example.data

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class TodoRepository(private val todoDao: TodoDao) {

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

    // --- Flows ---
    val allTodoItems: Flow<List<TodoItem>> = todoDao.getAllTodoItems()
    val allCompletionLogs: Flow<List<CompletionLog>> = todoDao.getAllCompletionLogs()
    val appSettings: Flow<AppSettings?> = todoDao.getSettingsFlow()
    val allChatMessages: Flow<List<ChatMessage>> = todoDao.getAllChatMessages()

    suspend fun getSettings(): AppSettings {
        return todoDao.getSettings() ?: AppSettings().also {
            todoDao.insertSettings(it)
        }
    }

    suspend fun updateSettings(settings: AppSettings) {
        todoDao.insertSettings(settings)
    }

    // --- Add Todo with 3-Phase Classification ---
    suspend fun addTodoItem(rawText: String) {
        // Phase 1: Local Regex Guess
        val provisionalItem = guessLocally(rawText)
        val insertedId = todoDao.insertTodoItem(provisionalItem).toInt()

        // Phase 2 & 3: Background refinement (non-blocking fire-and-forget refinement)
        repositoryScope.launch {
            classifyWithLLMInBackground(insertedId, rawText)
        }
    }

    // Phase 1: Regex Guess
    private fun guessLocally(text: String): TodoItem {
        val lowercase = text.lowercase(Locale.ROOT)
        
        // Detect Primary Category
        val isBuyIntent = lowercase.contains("buy") || lowercase.contains("shop") || 
                lowercase.contains("purchase") || lowercase.contains("get some") ||
                lowercase.contains("order") || lowercase.contains("grocery") || lowercase.contains("groceries") ||
                lowercase.contains("milk") || lowercase.contains("almond") || lowercase.contains("price of")
        
        val isRecurringIntent = lowercase.contains("every") || lowercase.contains("daily") || 
                lowercase.contains("weekly") || lowercase.contains("monthly") || 
                lowercase.contains("habit") || lowercase.contains("routine") ||
                lowercase.contains("gym") || lowercase.contains("workout") || lowercase.contains("meditate") ||
                lowercase.contains("morning") || lowercase.contains("evening") || lowercase.contains("always")

        val category = when {
            isRecurringIntent -> "Goals & Recurring"
            isBuyIntent -> "Items to Buy"
            else -> "To-Do List"
        }

        // Detect Secondary Tag
        val tag = when {
            lowercase.contains("gym") || lowercase.contains("workout") || lowercase.contains("run") || lowercase.contains("walk") || lowercase.contains("health") || lowercase.contains("meditate") -> "Health & Fitness"
            lowercase.contains("milk") || lowercase.contains("grocer") || lowercase.contains("food") || lowercase.contains("apple") || lowercase.contains("bread") || lowercase.contains("item") -> "Groceries"
            lowercase.contains("code") || lowercase.contains("work") || lowercase.contains("project") || lowercase.contains("email") || lowercase.contains("slides") || lowercase.contains("budget") -> "Work"
            lowercase.contains("read") || lowercase.contains("learn") || lowercase.contains("study") || lowercase.contains("book") || lowercase.contains("course") -> "Learning"
            lowercase.contains("invest") || lowercase.contains("save") || lowercase.contains("bank") || lowercase.contains("mutual") || lowercase.contains("stock") -> "Finance"
            else -> "Task"
        }

        // Detect Quadrant
        val quadrant = when {
            lowercase.contains("milk") || lowercase.contains("eggs") || lowercase.contains("bread") || lowercase.contains("rent") || lowercase.contains("electricity") || lowercase.contains("bill") || lowercase.contains("clean") || lowercase.contains("laundry") || lowercase.contains("hygiene") -> "Basic Needs"
            lowercase.contains("learn") || lowercase.contains("study") || lowercase.contains("course") || lowercase.contains("gym") || lowercase.contains("workout") || lowercase.contains("run") || lowercase.contains("meditate") || lowercase.contains("journal") || lowercase.contains("project") -> "Aspirational"
            lowercase.contains("movie") || lowercase.contains("game") || lowercase.contains("play") || lowercase.contains("netflix") || lowercase.contains("luxury") || lowercase.contains("streaming") || lowercase.contains("designer") || lowercase.contains("dine") -> "Luxury"
            lowercase.contains("invest") || lowercase.contains("saving") || lowercase.contains("stocks") || lowercase.contains("shares") || lowercase.contains("asset") || lowercase.contains("bonds") -> "Investments"
            else -> "Basic Needs"
        }

        // Parse crude recurrence properties
        val recurrencePattern = if (isRecurringIntent) {
            when {
                lowercase.contains("week") -> "weekly"
                else -> "daily"
            }
        } else null

        return TodoItem(
            title = text.trim().capitalize(Locale.ROOT),
            category = category,
            tag = tag,
            quadrant = quadrant,
            isCompleted = false,
            isRecurring = isRecurringIntent,
            recurrencePattern = recurrencePattern,
            aiProvisional = true,
            isFailedAI = false
        )
    }

    // Phase 2 & 3: LLM Background Call and Commit
    private suspend fun classifyWithLLMInBackground(todoId: Int, rawText: String) {
        val settings = getSettings()
        val apiKey = settings.apiKey.ifBlank { BuildConfig.GEMINI_API_KEY }

        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e("TodoRepository", "No API Key configured. Fallback to provisional regex.")
            markAsFailedAI(todoId)
            return
        }

        try {
            val responseString = when (settings.provider.lowercase(Locale.ROOT)) {
                "gemini" -> callGeminiAPI(apiKey, settings.modelName, settings.systemPrompt, rawText, requireJson = true)
                "openai", "custom" -> callOpenAICompatibleAPI(settings, apiKey, rawText, requireJson = true)
                "claude" -> callClaudeAPI(apiKey, settings.modelName, settings.systemPrompt, rawText)
                else -> callGeminiAPI(apiKey, "gemini-3.5-flash", settings.systemPrompt, rawText, requireJson = true)
            }

            if (responseString == null) {
                markAsFailedAI(todoId)
                return
            }

            val cleanedJson = cleanJsonString(responseString)
            val json = JSONObject(cleanedJson)

            val finalTitle = json.optString("title", rawText).ifBlank { rawText }
            val finalCategory = json.optString("category", "To-Do List")
            val finalTag = json.optString("tag", "Task")
            val finalQuadrant = json.optString("quadrant", "Basic Needs")
            val finalIsRecurring = json.optBoolean("isRecurring", false)
            val finalRecurrencePattern = json.optString("recurrencePattern", "null").let { 
                if (it == "null" || it.isBlank()) null else it 
            }

            val currentItem = todoDao.getTodoItemById(todoId)
            if (currentItem != null) {
                val updatedItem = currentItem.copy(
                    title = finalTitle,
                    category = finalCategory,
                    tag = finalTag,
                    quadrant = finalQuadrant,
                    isCompleted = currentItem.isCompleted,
                    isRecurring = finalIsRecurring,
                    recurrencePattern = finalRecurrencePattern,
                    aiProvisional = false,
                    isFailedAI = false
                )
                todoDao.updateTodoItem(updatedItem)
                Log.d("TodoRepository", "Successfully updated todo classification via LLM")
            }
        } catch (e: Exception) {
            Log.e("TodoRepository", "LLM background classification failed: ${e.message}", e)
            markAsFailedAI(todoId)
        }
    }

    private suspend fun markAsFailedAI(todoId: Int) {
        val currentItem = todoDao.getTodoItemById(todoId)
        if (currentItem != null) {
            todoDao.updateTodoItem(
                currentItem.copy(
                    aiProvisional = false,
                    isFailedAI = true
                )
            )
        }
    }

    // --- Provider Implementations ---

    private fun callGeminiAPI(
        apiKey: String,
        model: String,
        sysPrompt: String,
        userText: String,
        requireJson: Boolean = false
    ): String? {
        val targetModel = model.ifBlank { "gemini-3.5-flash" }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:generateContent?key=$apiKey"
        
        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", userText)
                        })
                    })
                })
            })
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", sysPrompt)
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                if (requireJson) {
                    put("responseMimeType", "application/json")
                }
                put("temperature", if (requireJson) 0.1 else 0.7)
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingLevel", "MINIMAL")
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(mediaTypeJson))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e("TodoRepository", "Gemini API returned error: ${response.code} ${response.body?.string()}")
                return null
            }
            val bodyString = response.body?.string() ?: return null
            val obj = JSONObject(bodyString)
            return obj.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        }
    }

    private fun callOpenAICompatibleAPI(
        settings: AppSettings,
        apiKey: String,
        userText: String,
        requireJson: Boolean = false
    ): String? {
        val isCustom = settings.provider.lowercase(Locale.ROOT) == "custom"
        val baseUrl = if (isCustom && settings.customEndpoint.isNotBlank()) {
            settings.customEndpoint
        } else {
            "https://api.openai.com/v1"
        }
        val url = if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"
        val model = if (isCustom) settings.modelName.ifBlank { "local-model" } else settings.modelName.ifBlank { "gpt-4o-mini" }

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", settings.systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userText)
                })
            })
            put("temperature", if (requireJson) 0.1 else 0.7)
            if (!isCustom && requireJson) {
                put("response_format", JSONObject().apply {
                    put("type", "json_object")
                })
            }
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(mediaTypeJson))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e("TodoRepository", "OpenAI-compatible returned error: ${response.code} ${response.body?.string()}")
                return null
            }
            val bodyString = response.body?.string() ?: return null
            val obj = JSONObject(bodyString)
            return obj.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    private fun callClaudeAPI(apiKey: String, model: String, sysPrompt: String, userText: String): String? {
        val targetModel = model.ifBlank { "claude-3-5-sonnet-20241022" }
        val url = "https://api.anthropic.com/v1/messages"

        val payload = JSONObject().apply {
            put("model", targetModel)
            put("max_tokens", 1024)
            put("system", sysPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userText)
                })
            })
            put("temperature", 0.1)
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(payload.toString().toRequestBody(mediaTypeJson))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e("TodoRepository", "Claude API returned error: ${response.code} ${response.body?.string()}")
                return null
            }
            val bodyString = response.body?.string() ?: return null
            val obj = JSONObject(bodyString)
            return obj.getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
        }
    }

    private fun cleanJsonString(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```json")) {
            s = s.substringAfter("```json")
        } else if (s.startsWith("```")) {
            s = s.substringAfter("```")
        }
        if (s.endsWith("```")) {
            s = s.substringBeforeLast("```")
        }
        return s.trim()
    }

    // --- Task Manipulation & Completion History ---

    suspend fun toggleTodoCompletion(itemId: Int, isCompleted: Boolean) {
        val item = todoDao.getTodoItemById(itemId) ?: return
        if (item.isRecurring) {
            // It is a habit / recurring task
            val todayMidnight = getTodayMidnight()
            if (isCompleted) {
                // Add Completion log
                todoDao.insertCompletionLog(CompletionLog(todoId = itemId, completedDate = todayMidnight))
            } else {
                // Remove completion log
                todoDao.deleteCompletionLog(itemId, todayMidnight)
            }
            // For habits, the "isCompleted" flag of the main item can also be synced for today
            todoDao.updateTodoItem(item.copy(isCompleted = isCompleted, completedAt = if (isCompleted) System.currentTimeMillis() else null))
        } else {
            // Single shot task
            todoDao.updateTodoItem(item.copy(isCompleted = isCompleted, completedAt = if (isCompleted) System.currentTimeMillis() else null))
        }
    }

    suspend fun deleteTodoItem(itemId: Int) {
        todoDao.deleteTodoItemById(itemId)
        todoDao.clearLogsForTodo(itemId)
    }

    suspend fun clearAll() {
        todoDao.clearAllTodoItems()
    }

    // --- Chat Coach Operations ---

    suspend fun sendChatMessageToCoach(userMessage: String) {
        // Save user message in DB
        todoDao.insertChatMessage(ChatMessage(sender = "user", text = userMessage))

        // Get full list of tasks as string representation
        val items = todoDao.getAllTodoItems().firstOrNull() ?: emptyList()
        val jsonArray = JSONArray()
        for (item in items) {
            val jo = JSONObject().apply {
                put("title", item.title)
                put("category", item.category)
                put("tag", item.tag)
                put("quadrant", item.quadrant)
                put("isCompleted", item.isCompleted)
                put("isRecurring", item.isRecurring)
            }
            jsonArray.put(jo)
        }
        val tasksContext = jsonArray.toString(2)

        val settings = getSettings()
        val apiKey = settings.apiKey.ifBlank { BuildConfig.GEMINI_API_KEY }

        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            todoDao.insertChatMessage(
                ChatMessage(
                    sender = "coach",
                    text = "Hello Suresh! I'd love to coach you, but I don't see an active API key configured yet. Please configure an API critical setting in the Settings drawer (via top bar) or add it in your AI Studio Secrets panel so I can read your quadrants beautifully!"
                )
            )
            return
        }

        // Make background call to Coach
        withContext(Dispatchers.IO) {
            try {
                val fullSystemPrompt = "${settings.coachSystemPrompt}\n\nCURRENT STRUCTURED TASKS DATA:\n$tasksContext"
                
                val responseString = when (settings.provider.lowercase(Locale.ROOT)) {
                    "gemini" -> callGeminiAPI(apiKey, settings.modelName, fullSystemPrompt, userMessage)
                    "openai", "custom" -> {
                        // Make prompt with context for openai-compatible
                        val mockSettings = settings.copy(systemPrompt = fullSystemPrompt)
                        callOpenAICompatibleAPI(mockSettings, apiKey, userMessage)
                    }
                    "claude" -> callClaudeAPI(apiKey, settings.modelName, fullSystemPrompt, userMessage)
                    else -> callGeminiAPI(apiKey, "gemini-3.5-flash", fullSystemPrompt, userMessage)
                }

                val finalCoachReply = responseString ?: "I encountered a minor bump reading your tasks. Please check your API properties or connection limits."
                todoDao.insertChatMessage(ChatMessage(sender = "coach", text = finalCoachReply))
            } catch (e: Exception) {
                todoDao.insertChatMessage(ChatMessage(sender = "coach", text = "Error details: ${e.message}. Please verify your API setup!"))
            }
        }
    }

    suspend fun clearChat() {
        todoDao.clearChatHistory()
    }

    // --- Recurrence & Date helpers ---

    fun getTodayMidnight(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun getYesterdayMidnight(): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
