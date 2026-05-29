package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID

data class AppUiState(
    val todoItems: List<TodoItem> = emptyList(),
    val completionLogs: List<CompletionLog> = emptyList(),
    val appSettings: AppSettings = AppSettings(),
    val chatMessages: List<ChatMessage> = emptyList(),
    val isClassifying: Boolean = false,
    val infoMessage: String? = null,
    val activeCategoryTab: String = "All" // "All", "To-Do List", "Items to Buy", "Goals & Recurring"
)

class TodoViewModel(application: Application) : AndroidViewModel(application) {

    private val database = TodoDatabase.getDatabase(application)
    private val repository = TodoRepository(database.todoDao())

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _speechText = MutableStateFlow("")
    val speechText: StateFlow<String> = _speechText.asStateFlow()

    private val testPrompts = listOf(
        "Buy unsweetened organic almond milk and oat milk",
        "Go for a 35-minute walk every day after breakfast",
        "Invest $150 in index funds and savings deposit",
        "Clean the domestic kitchen countertops on Sunday afternoon",
        "Prepare the budget and financial projection slides by Friday morning",
        "Read 3 chapters of high-performance psychology book",
        "Buy custom coffee beans and filter papers",
        "Always practice mindfulness meditation for 15 minutes",
        "Subscribe to cloud computing learning certificate"
    )

    init {
        // Collect database flows and merge them safely into UI State
        viewModelScope.launch {
            combine(
                repository.allTodoItems,
                repository.allCompletionLogs,
                repository.appSettings,
                repository.allChatMessages
            ) { items, logs, settings, chats ->
                _uiState.update { state ->
                    state.copy(
                        todoItems = items,
                        completionLogs = logs,
                        appSettings = settings ?: AppSettings(),
                        chatMessages = chats
                    )
                }
            }.collect()
        }

        // Initialize default settings row if missing
        viewModelScope.launch {
            repository.getSettings()
        }
    }

    fun setCategoryTab(tabName: String) {
        _uiState.update { it.copy(activeCategoryTab = tabName) }
    }

    fun submitTodoTask(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isClassifying = true) }
            repository.addTodoItem(text)
            _uiState.update { it.copy(isClassifying = false) }
        }
    }

    fun toggleTaskCompletion(itemId: Int, isCompleted: Boolean) {
        viewModelScope.launch {
            repository.toggleTodoCompletion(itemId, isCompleted)
        }
    }

    fun deleteTask(itemId: Int) {
        viewModelScope.launch {
            repository.deleteTodoItem(itemId)
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }

    // --- Voice Microphone Handling & Fallback ---

    fun startListening() {
        _isListening.value = true
        _speechText.value = "Listening..."
    }

    fun stopListening(successText: String? = null) {
        _isListening.value = false
        if (successText != null && successText.isNotBlank()) {
            _speechText.value = successText
        } else {
            _speechText.value = ""
        }
    }

    fun triggerRandomTestPrompt(): String {
        val randomPrompt = testPrompts.random()
        showNotification("SpeechRecognizer unavailable. Inserted random test prompt: \"$randomPrompt\"")
        return randomPrompt
    }

    // --- AI Coach Functions ---

    fun sendCoachMessage(userMsg: String) {
        if (userMsg.isBlank()) return
        viewModelScope.launch {
            repository.sendChatMessageToCoach(userMsg)
        }
    }

    fun clearCoachChat() {
        viewModelScope.launch {
            repository.clearChat()
        }
    }

    // --- Settings Configuration ---

    fun saveSettings(
        provider: String,
        apiKey: String,
        endpoint: String,
        sysPrompt: String,
        coachPrompt: String,
        modelName: String
    ) {
        viewModelScope.launch {
            val currentSettings = repository.getSettings()
            val updated = currentSettings.copy(
                provider = provider,
                apiKey = apiKey,
                customEndpoint = endpoint,
                systemPrompt = sysPrompt,
                coachSystemPrompt = coachPrompt,
                modelName = modelName
            )
            repository.updateSettings(updated)
            showNotification("Configuration updated successfully.")
        }
    }

    fun resetSystemPrompts() {
        viewModelScope.launch {
            val currentSettings = repository.getSettings()
            val reset = currentSettings.copy(
                systemPrompt = AppSettings.DEFAULT_SYSTEM_PROMPT,
                coachSystemPrompt = AppSettings.DEFAULT_COACH_SYSTEM_PROMPT
            )
            repository.updateSettings(reset)
            showNotification("Prompts reset to system default.")
        }
    }

    // --- Habit Check Utilities ---

    fun isHabitCompletedToday(itemId: Int): Boolean {
        val todayMidnight = repository.getTodayMidnight()
        return _uiState.value.completionLogs.any {
            it.todoId == itemId && it.completedDate == todayMidnight
        }
    }

    /**
     * Missed habits definition:
     * A recurring daily habit (task category == "Goals & Recurring") that was created (createdAt) before today midnight,
     * which was NOT completed yesterday AND is NOT completed today.
     */
    fun getMissedHabits(): List<TodoItem> {
        val todayMidnight = repository.getTodayMidnight()
        val yesterdayMidnight = repository.getYesterdayMidnight()
        val logs = _uiState.value.completionLogs

        return _uiState.value.todoItems.filter { item ->
            if (item.category != "Goals & Recurring" || !item.isRecurring) return@filter false
            if (item.createdAt >= todayMidnight) return@filter false // Created today, too early to call it "missed"

            val completedToday = logs.any { it.todoId == item.id && it.completedDate == todayMidnight }
            val completedYesterday = logs.any { it.todoId == item.id && it.completedDate == yesterdayMidnight }

            !completedToday && !completedYesterday
        }
    }

    fun selectRandomInitialRecommendation(): String {
        return "I suggest establishing a structured daily walk or review sequence to kickstart your 'Aspirational' quadrant. Add 'Walk for 20 minutes every evening' to get started!"
    }

    // --- Informative Popups ---

    fun showNotification(msg: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(infoMessage = msg) }
            kotlinx.coroutines.delay(3500)
            _uiState.update { state ->
                // Only clear if it hasn't been changed to another message
                if (state.infoMessage == msg) state.copy(infoMessage = null) else state
            }
        }
    }

    fun clearNotification() {
        _uiState.update { it.copy(infoMessage = null) }
    }
}
