package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import com.example.data.AppSettings
import com.example.data.TodoItem
import com.example.ui.TodoViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainContent()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(viewModel: TodoViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val speechText by viewModel.speechText.collectAsStateWithLifecycle()

    var textInput by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    // UI Expansion sheets toggles
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showCoachDrawer by remember { mutableStateOf(false) }
    var showPrivacyExplanation by remember { mutableStateOf(false) }

    // Sound STT listener state
    var speechRecognizerInstance by remember { mutableStateOf<SpeechRecognizer?>(null) }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.showNotification("Microphone permission granted. Tap mic to speak!")
        } else {
            val fallbackText = viewModel.triggerRandomTestPrompt()
            textInput = fallbackText
        }
    }

    // Helper to start recognition or trigger randomized prompt fallback
    fun toggleSpeechRecognition() {
        if (isListening) {
            speechRecognizerInstance?.stopListening()
            viewModel.stopListening()
            return
        }

        // Check Permissions
        val permissionCheck = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        )

        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            val fallbackText = viewModel.triggerRandomTestPrompt()
            textInput = fallbackText
            return
        }

        // Initialize SpeechRecognizer
        viewModel.startListening()
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            speechRecognizerInstance?.destroy()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        Log.e("MainActivity", "Speech recognition error code: $error")
                        val errMsg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No voice matched. Triggering fallback prompt!"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Mic system is busy. Fallback prompt loaded."
                            else -> "Mic inputs unsupported. Fallback prompt loaded."
                        }
                        Toast.makeText(context, errMsg, Toast.LENGTH_SHORT).show()
                        val fallbackText = viewModel.triggerRandomTestPrompt()
                        textInput = fallbackText
                        viewModel.stopListening()
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            textInput = matches[0]
                            viewModel.stopListening(matches[0])
                        } else {
                            viewModel.stopListening()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            textInput = matches[0]
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            speechRecognizerInstance = recognizer
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Could not start speech: ${e.message}")
            val fallbackText = viewModel.triggerRandomTestPrompt()
            textInput = fallbackText
            viewModel.stopListening()
        }
    }

    // Show persistent notification messages from ViewModel as snackbars or custom alerts
    LaunchedEffect(uiState.infoMessage) {
        uiState.infoMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearNotification()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // Mini Privacy Indicator Banner in Bottom
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .clickable { showPrivacyExplanation = true }
                    .padding(vertical = 10.dp, horizontal = 16.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Privacy Check",
                    tint = PrimaryEmerald,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "🔒 Privacy Shield: Zero Telemetry. Local Persistence Only.",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCoachDrawer = true },
                containerColor = AccentCoachContainer,
                contentColor = AccentCoachText,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .testTag("floating_ai_coach_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Coach Drawer"
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "AI CLARITY COACH",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
        },
        floatingActionButtonPosition = FabPosition.End
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // --- TOP HEADER ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "IntelligentTodo",
                            fontWeight = FontWeight.Medium,
                            fontSize = 22.sp,
                            letterSpacing = (-0.5).sp,
                            color = TextPrimary,
                            fontFamily = FontFamily.SansSerif
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(DarkElevatedSurface, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "v2.5",
                                color = TertiaryGold,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    // Glowing Privacy Local-Only indicator row from Design HTML
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(0xFF10B981), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "PRIVACY LOCAL-ONLY",
                            color = Color(0xFF10B981),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    val dateFormatted = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.US).format(Date())
                    Text(
                        text = dateFormatted,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                // Configuration Button
                IconButton(
                    onClick = { showSettingsDialog = true },
                    modifier = Modifier
                        .background(DarkElevatedSurface, CircleShape)
                        .size(40.dp)
                        .testTag("settings_cog_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = TextPrimary
                    )
                }
            }

            // --- ALERT BANNER FOR MISSED HABITS ---
            val missedHabits = viewModel.getMissedHabits()
            if (missedHabits.isNotEmpty()) {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .border(1.dp, TertiaryGold, RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(TertiaryGold, CircleShape)
                                    .size(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Alert",
                                    tint = DarkBackground,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Suresh, you have missed ${missedHabits.size} daily habits!",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Tap block to let AI Coach strategy rebalance this.",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                            Button(
                                onClick = {
                                    val habitNames = missedHabits.joinToString(", ") { "- '${it.title}'" }
                                    viewModel.sendCoachMessage("I missed these recurring habits:\n$habitNames\n\nExplain why I missed them and give me a custom recovery strategy based on my life quadrants.")
                                    showCoachDrawer = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = TertiaryGold),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "RECOVER",
                                    color = DarkBackground,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }

            // --- UNIVERSAL TEXT + VOICE CAPTURE ---
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .border(
                        width = if (isListening) 2.dp else 1.dp,
                        color = if (isListening) PrimaryEmerald else DividerColor,
                        shape = RoundedCornerShape(32.dp)
                    ),
                shape = RoundedCornerShape(32.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isListening) DarkElevatedSurface else DarkSurface
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Quick AI prompt spark generator representing test continuity
                        IconButton(
                            onClick = {
                                val fallVal = viewModel.triggerRandomTestPrompt()
                                textInput = fallVal
                            },
                            modifier = Modifier
                                .background(DarkElevatedSurface, CircleShape)
                                .size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Add Random Test Task",
                                tint = TertiaryGold,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        TextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            placeholder = {
                                Text(
                                    text = if (isListening) "Listening carefully..." else "Type target task/purchase/habit...",
                                    color = TextSecondary,
                                    fontSize = 14.sp
                                )
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("todo_text_input"),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (textInput.isNotBlank()) {
                                    viewModel.submitTodoTask(textInput)
                                    textInput = ""
                                    keyboardController?.hide()
                                }
                            }),
                            singleLine = true
                        )

                        // Real-time Mic Toggle
                        IconButton(
                            onClick = { toggleSpeechRecognition() },
                            modifier = Modifier
                                .background(
                                    if (isListening) PrimaryEmerald else DarkElevatedSurface,
                                    CircleShape
                                )
                                .size(40.dp)
                                .testTag("microphone_toggle_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Hold to Speech",
                                tint = if (isListening) DarkBackground else TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Submit Button
                        IconButton(
                            onClick = {
                                if (textInput.isNotBlank()) {
                                    viewModel.submitTodoTask(textInput)
                                    textInput = ""
                                    keyboardController?.hide()
                                }
                            },
                            modifier = Modifier
                                .background(PrimaryEmerald, CircleShape)
                                .size(40.dp)
                                .testTag("todo_submit_button"),
                            enabled = textInput.isNotBlank()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Submit Intent",
                                tint = DarkBackground,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    if (isListening) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "🔴 SPEAK NOW — Realtime WebSpeech Emulator Active",
                                color = ErrorRed,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // --- AI CLASSIFYING BANNER (Sophisticated Dark HTML theme match) ---
            if (uiState.isClassifying) {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(AccentBlueContainer, RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = AccentBlueText
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "AI is classifying new entry...",
                            color = AccentBlueText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // --- CATEGORY TABS ---
            val categories = listOf("All", "To-Do List", "Items to Buy", "Goals & Recurring")
            ScrollableTabRow(
                selectedTabIndex = categories.indexOf(uiState.activeCategoryTab),
                containerColor = Color.Transparent,
                contentColor = PrimaryEmerald,
                edgePadding = 0.dp,
                divider = {},
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[categories.indexOf(uiState.activeCategoryTab)]),
                        color = PrimaryEmerald,
                        height = 2.dp
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                categories.forEach { cat ->
                    val isActive = uiState.activeCategoryTab == cat
                    val count = when (cat) {
                        "All" -> uiState.todoItems.size
                        else -> uiState.todoItems.count { it.category == cat }
                    }

                    Tab(
                        selected = isActive,
                        onClick = { viewModel.setCategoryTab(cat) },
                        modifier = Modifier.testTag("tab_$cat")
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = cat,
                                color = if (isActive) PrimaryEmerald else TextSecondary,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 13.sp
                            )
                            if (count > 0) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (isActive) PrimaryEmerald else DarkElevatedSurface,
                                            CircleShape
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = count.toString(),
                                        color = if (isActive) DarkBackground else TextPrimary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // --- MAIN LIST OF ITEMS ---
            val filteredItems = if (uiState.activeCategoryTab == "All") {
                uiState.todoItems
            } else {
                uiState.todoItems.filter { it.category == uiState.activeCategoryTab }
            }

            if (filteredItems.isEmpty()) {
                // Empty state tip styled beautifully matching the HTML template
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, DividerColor, RoundedCornerShape(28.dp)),
                            colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(28.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Empty",
                                    tint = SecondarySlate,
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Your Inbox is Clear!",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Tap the Gold Spark star above to auto-generate realistic sample inputs.",
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 4.dp)
                                )

                                Spacer(modifier = Modifier.height(20.dp))

                                // Dashed suggested design element from HTML
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.Transparent)
                                        .border(
                                            width = 1.dp,
                                            color = DividerColor,
                                            shape = RoundedCornerShape(16.dp)
                                        )
                                        .padding(12.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "“Invest in a new ergonomic chair...”",
                                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                            fontSize = 13.sp,
                                            color = TextPrimary,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "DRAFTED BY COACH • QUADRANT: INVESTMENTS",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SecondarySlate,
                                            letterSpacing = 0.5.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        TodoCardItem(
                            item = item,
                            isCompleted = if (item.isRecurring) viewModel.isHabitCompletedToday(item.id) else item.isCompleted,
                            onToggleCompletion = { isChecked ->
                                viewModel.toggleTaskCompletion(item.id, isChecked)
                            },
                            onDelete = {
                                viewModel.deleteTask(item.id)
                            }
                        )
                    }
                }
            }
        }
    }

    // Settings Configuration Panel Dialog
    if (showSettingsDialog) {
        var providerState by remember { mutableStateOf(uiState.appSettings.provider) }
        var apiKeyInput by remember { mutableStateOf(uiState.appSettings.apiKey) }
        var modelNameInput by remember { mutableStateOf(uiState.appSettings.modelName) }
        var endpointInput by remember { mutableStateOf(uiState.appSettings.customEndpoint) }
        var systemPromptInput by remember { mutableStateOf(uiState.appSettings.systemPrompt) }
        var coachPromptInput by remember { mutableStateOf(uiState.appSettings.coachSystemPrompt) }
        var maskApiKey by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = {
                Text(
                    text = "API & Provider Settings",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = PrimaryEmerald
                )
            },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            text = "Select Provider",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val providers = listOf("gemini", "openai", "claude", "custom")
                            providers.forEach { prov ->
                                Button(
                                    onClick = {
                                        providerState = prov
                                        // Update default template model based on provider choice
                                        modelNameInput = when (prov) {
                                            "gemini" -> "gemini-3.5-flash"
                                            "openai" -> "gpt-4o-mini"
                                            "claude" -> "claude-3-5-sonnet-20241022"
                                            "custom" -> "your-local-model"
                                            else -> "gemini-3.5-flash"
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (providerState == prov) PrimaryEmerald else DarkElevatedSurface,
                                        contentColor = if (providerState == prov) DarkBackground else TextPrimary
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = prov.uppercase(Locale.US),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            label = { Text("API Key") },
                            placeholder = { Text("Leave blank to use developer default") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryEmerald,
                                unfocusedBorderColor = DividerColor,
                                focusedLabelColor = PrimaryEmerald
                            ),
                            visualTransformation = if (maskApiKey) PasswordVisualTransformation() else VisualTransformation.None,
                            trailingIcon = {
                                IconButton(onClick = { maskApiKey = !maskApiKey }) {
                                    Icon(
                                        imageVector = if (maskApiKey) Icons.Default.Menu else Icons.Default.Check,
                                        contentDescription = "Toggle Key visibility"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Text(
                            text = "If left blank, calls will fallback, leveraging AI Studio platform credentials securely.",
                            fontSize = 10.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 2.dp, start = 4.dp)
                        )
                    }

                    item {
                        OutlinedTextField(
                            value = modelNameInput,
                            onValueChange = { modelNameInput = it },
                            label = { Text("Model ID") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryEmerald,
                                unfocusedBorderColor = DividerColor
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    if (providerState == "custom") {
                        item {
                            OutlinedTextField(
                                value = endpointInput,
                                onValueChange = { endpointInput = it },
                                label = { Text("Base API Endpoint") },
                                placeholder = { Text("e.g. http://10.0.2.2:11434") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PrimaryEmerald,
                                    unfocusedBorderColor = DividerColor
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = systemPromptInput,
                            onValueChange = { systemPromptInput = it },
                            label = { Text("Classification Prompt") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryEmerald,
                                unfocusedBorderColor = DividerColor
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 5
                        )
                    }

                    item {
                        OutlinedTextField(
                            value = coachPromptInput,
                            onValueChange = { coachPromptInput = it },
                            label = { Text("AI Coach System Prompt") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryEmerald,
                                unfocusedBorderColor = DividerColor
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 5
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = {
                                viewModel.resetSystemPrompts()
                                showSettingsDialog = false
                            }) {
                                Text("Reset to default prompts", color = TertiaryGold, fontSize = 11.sp)
                            }
                            TextButton(onClick = {
                                viewModel.clearAllData()
                                viewModel.showNotification("Local task database has been loaded clean.")
                                showSettingsDialog = false
                            }) {
                                Text("WIPE ALL DATA", color = ErrorRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveSettings(
                            provider = providerState,
                            apiKey = apiKeyInput,
                            endpoint = endpointInput,
                            sysPrompt = systemPromptInput,
                            coachPrompt = coachPromptInput,
                            modelName = modelNameInput
                        )
                        showSettingsDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryEmerald)
                ) {
                    Text("SAVE CHANGES", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("CANCEL", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    // Drawer / Overlay sheet for sliding AI coach chat
    if (showCoachDrawer) {
        Dialog(onDismissRequest = { showCoachDrawer = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 12.dp),
                shape = RoundedCornerShape(16.dp),
                color = DarkSurface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Header inside drawer
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Active Coach",
                                tint = PrimaryEmerald
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "AI CLARITY COACH",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 15.sp,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Analyzing ${uiState.todoItems.size} tasks in Suresh's context",
                                    fontSize = 10.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        Row {
                            IconButton(onClick = { viewModel.clearCoachChat() }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Clear Chat",
                                    tint = TextSecondary
                                )
                            }
                            IconButton(onClick = { showCoachDrawer = false }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = TextPrimary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Coach Privacy badge inside drawer
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkElevatedSurface, RoundedCornerShape(8.dp))
                            .border(1.dp, DividerColor, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Shield",
                                    tint = PrimaryEmerald,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Secure local analysis active",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = TextPrimary
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Your full task data resides locally. Context is injected purely inside your user-chosen LLM query call. Zero secondary telemetry.",
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Chat messages area
                    val messages = uiState.chatMessages
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (messages.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(DarkElevatedSurface, RoundedCornerShape(12.dp))
                                        .padding(12.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "Welcome Suresh!",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = PrimaryEmerald
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "I have scanned your quadrants. Ask me anything, or try one of the quick suggestions below:",
                                            fontSize = 12.sp,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        } else {
                            items(messages) { msg ->
                                val isUser = msg.sender == "user"
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                if (isUser) PrimaryEmerald else DarkElevatedSurface,
                                                RoundedCornerShape(
                                                    topStart = 12.dp,
                                                    topEnd = 12.dp,
                                                    bottomStart = if (isUser) 12.dp else 0.dp,
                                                    bottomEnd = if (isUser) 0.dp else 12.dp
                                                )
                                            )
                                            .padding(12.dp)
                                            .widthIn(max = 260.dp)
                                    ) {
                                        Text(
                                            text = msg.text,
                                            color = if (isUser) DarkBackground else TextPrimary,
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Quick Action Helper Prompts Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val prompts = listOf(
                            "Analyze quadrants 📊" to "Analyze my daily quadrants distribution. Do I show a healthy balance?",
                            "Evaluate habits 🔄" to "Evaluate my current habits. Do I have missed targets?",
                            "Strategy 🚀" to "Give me tomorrow's plan of action based on my Investments and Aspirational quadrants."
                        )
                        prompts.forEach { (lbl, promptText) ->
                            Box(
                                modifier = Modifier
                                    .background(DarkElevatedSurface, RoundedCornerShape(8.dp))
                                    .border(1.dp, DividerColor, RoundedCornerShape(8.dp))
                                    .clickable {
                                        viewModel.sendCoachMessage(promptText)
                                    }
                                    .padding(vertical = 6.dp, horizontal = 8.dp)
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = lbl,
                                    fontSize = 9.sp,
                                    color = PrimaryEmerald,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Text Input Bar
                    var coachTextState by remember { mutableStateOf("") }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkElevatedSurface, RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = coachTextState,
                            onValueChange = { coachTextState = it },
                            placeholder = { Text("Ask Coach about your plan...", fontSize = 12.sp) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )

                        IconButton(
                            onClick = {
                                if (coachTextState.isNotBlank()) {
                                    viewModel.sendCoachMessage(coachTextState)
                                    coachTextState = ""
                                }
                            },
                            enabled = coachTextState.isNotBlank()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Send",
                                tint = if (coachTextState.isNotBlank()) PrimaryEmerald else TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }

    // Explanation panel for Privacy Badges
    if (showPrivacyExplanation) {
        AlertDialog(
            onDismissRequest = { showPrivacyExplanation = false },
            title = { Text("🔒 Local-First Privacy Protection") },
            text = {
                Text(
                    text = "IntelligentTodo v2.5 complies strictly with Suresh's strict telemetry-free policy:\n\n" +
                            "• Zero general telemetry, analytics or cloud beacons are compiled or shipped.\n" +
                            "• All logs, custom keys, prompts, and tasks reside 100% locally inside the on-device Room partition.\n" +
                            "• Your credentials are secure. Any API context is sent directly and exclusively to your chosen AI endpoints during explicit classification or coaching queries.",
                    fontSize = 12.sp,
                    color = TextPrimary
                )
            },
            confirmButton = {
                Button(
                    onClick = { showPrivacyExplanation = false },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryEmerald)
                ) {
                    Text("UNDERSTOOD", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
fun TodoCardItem(
    item: TodoItem,
    isCompleted: Boolean,
    onToggleCompletion: (Boolean) -> Unit,
    onDelete: () -> Unit,
    viewModel: TodoViewModel = viewModel()
) {
    // Left-gutter indicator stripe depending on lifecycle quadrant
    val quadrantColor = when (item.quadrant) {
        "Basic Needs" -> SecondarySlate
        "Aspirational" -> AspirationalBlue
        "Luxury" -> LuxuryPink
        "Investments" -> InvestmentPurple
        else -> SecondarySlate
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DividerColor, RoundedCornerShape(28.dp))
            .testTag("task_item_${item.id}"),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min) // Forces children to occupy full visual height
        ) {
            // Colored Quadrant stripe
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(6.dp)
                    .background(quadrantColor)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Checkbox to Toggle Completion
                Checkbox(
                    checked = isCompleted,
                    onCheckedChange = { onToggleCompletion(it) },
                    colors = CheckboxDefaults.colors(
                        checkedColor = PrimaryEmerald,
                        checkmarkColor = DarkBackground,
                        uncheckedColor = TextSecondary
                    ),
                    modifier = Modifier.testTag("task_checkbox_${item.id}")
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Task Information details
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = if (isCompleted) TextSecondary else TextPrimary,
                            textDecoration = if (isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 2,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        // 3-Phase classification indicator badges
                        if (item.aiProvisional) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .background(DarkElevatedSurface, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "⚡ Regex Guess",
                                    color = TextSecondary,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else if (item.isFailedAI) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF4A1F1F), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "⚠️ AI Fail (Local)",
                                    color = ErrorRed,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Secondary Taxonomy dynamic Tag & Quadrant row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Dynamic tag
                        Box(
                            modifier = Modifier
                                .background(DarkElevatedSurface, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "Tag",
                                    tint = PrimaryEmerald,
                                    modifier = Modifier.size(8.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = item.tag,
                                    color = TextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Life quadrant
                        Box(
                            modifier = Modifier
                                .background(quadrantColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                .border(1.dp, quadrantColor.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = item.quadrant,
                                color = quadrantColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        // Recurrence indication
                        if (item.isRecurring) {
                            Box(
                                modifier = Modifier
                                    .background(DarkElevatedSurface, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Recurring",
                                        tint = TertiaryGold,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "Daily Habit",
                                        color = TertiaryGold,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Delete Trash Action
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("task_delete_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete item",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
