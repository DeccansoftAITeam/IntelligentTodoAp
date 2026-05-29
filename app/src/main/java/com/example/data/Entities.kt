package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "todo_items")
data class TodoItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val category: String, // "To-Do List", "Items to Buy", "Goals & Recurring"
    val tag: String, // Dynamic tag, e.g., "Fitness", "Groceries"
    val quadrant: String, // "Basic Needs", "Aspirational", "Luxury", "Investments"
    val isCompleted: Boolean = false,
    val isRecurring: Boolean = false,
    val recurrencePattern: String? = null, // "daily", "weekly"
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val isFailedAI: Boolean = false, // True if background classification fails
    val aiProvisional: Boolean = true // True during provisional local regex state
)

@Entity(tableName = "completion_logs")
data class CompletionLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val todoId: Int,
    val completedDate: Long // Midnight timestamp of task completion day
)

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val id: Int = 1,
    val provider: String = "gemini", // "gemini", "openai", "claude", "custom"
    val apiKey: String = "",
    val customEndpoint: String = "",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val coachSystemPrompt: String = DEFAULT_COACH_SYSTEM_PROMPT,
    val modelName: String = "gemini-3.5-flash"
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT = """You are an expert taxonomy assistant for IntelligentTodoApp.
Your task is to classify this task input into a 3-tier taxonomy.
You must return a raw JSON object with the following schema:
{
  "title": "Cleaned, title-case task description (e.g. 'Buy Almonds and Oat Milk')",
  "category": "To-Do List" or "Items to Buy" or "Goals & Recurring",
  "tag": "Single dynamic title-case category/tag (e.g., 'Groceries', 'Fitness', 'Work', 'Finance', 'Home')",
  "quadrant": "Basic Needs" or "Aspirational" or "Luxury" or "Investments",
  "isRecurring": true or false,
  "recurrencePattern": "daily" or "weekly" or null
}

Guidelines:
- "To-Do List": normal single tasks (e.g., send email, clean room, build feature).
- "Items to Buy": physical things or purchases (e.g. groceries, subscriptions, electronics).
- "Goals & Recurring": habits, daily drills, repetitive routines, long-term targets, or tasks containing recurrence intent words.
- Life Quadrants:
  - "Basic Needs": essentials for survival, basic chores, food, bills, standard health, hygiene.
  - "Aspirational": personal growth, learning, skill-building, fitness, strategic milestones, creative projects.
  - "Luxury": comfort, amusement, fine dining, designer items, hobbies, lifestyle upgrades, streaming boxes.
  - "Investments": assets, long-term capital allocation, savings, self-betterment seeds that pay compounding dividends.

Analyze the user's input and reply with ONLY the compliant JSON."""

        const val DEFAULT_COACH_SYSTEM_PROMPT = """You are the AI Productivity Analyst & Coach inside सुरेश (Suresh)'s IntelligentTodoApp.
Analyze his current tasks and quadrants (Basic Needs, Aspirational, Luxury, Investments) to offer motivational guidance, identify missing habits, and encourage healthy balance.
Identify where things overlap and offer actionable tips to rebalance. Keep answers short, punchy, visually rich with formatting, and deeply contextual."""
    }
}

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sender: String, // "user", "coach"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)
