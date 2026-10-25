@file:OptIn(ExperimentalMaterial3Api::class)
package com.steernly17.studyapp

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.google.ai.client.generativeai.GenerativeModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// NETWORK CONNECTIVITY HELPER
@Suppress("MissingPermission")
fun isNetworkAvailable(context: Context): Boolean {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Exception) {
        true
    }
}

// REAL DOCUMENT FILE NAME & CONTENT EXTRACTION HELPERS
fun saveFileToInternalStorage(context: Context, uri: Uri, fileName: String): String {
    return try {
        val docsDir = File(context.filesDir, "study_workspace").apply { if (!exists()) mkdirs() }
        val safeName = "${System.currentTimeMillis()}_${fileName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")}"
        val destFile = File(docsDir, safeName)
        
        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        destFile.absolutePath
    } catch (_: Exception) {
        ""
    }
}

fun getFileNameFromUri(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            }
        } catch (_: Exception) {}
    }
    if (result.isNullOrBlank()) {
        result = uri.path?.substringAfterLast('/')
    }
    return if (!result.isNullOrBlank()) result!! else "Uploaded_Document.pdf"
}

fun readTextFromUri(context: Context, uri: Uri): String {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return ""
        
        val headerBuffer = ByteArray(2048)
        val bytesRead = inputStream.read(headerBuffer)
        val headerString = if (bytesRead > 0) String(headerBuffer, 0, bytesRead, Charsets.ISO_8859_1) else ""
        
        val isPdf = headerString.startsWith("%PDF") || headerString.contains("Catalog")
        val isBinary = headerBuffer.take(bytesRead.coerceAtLeast(0)).any { byte ->
            val b = byte.toInt() and 0xFF
            b < 0x09 || (b in 0x0E..0x1F)
        }

        if (isPdf || isBinary) {
            inputStream.close()
            return ""
        }

        val fullBytes = headerBuffer.copyOf(bytesRead.coerceAtLeast(0)) + inputStream.readBytes()
        inputStream.close()
        
        String(fullBytes, Charsets.UTF_8).take(50000)
    } catch (_: Exception) {
        ""
    }
}

// DATA MODELS FOR PERSISTENCE
data class AppUser(
    val fullName: String,
    val username: String,
    val email: String,
    var pass: String,
    var profilePicUri: String? = null,
    var geminiEmail: String? = null,
    var geminiApiKey: String? = null
)

data class Topic(
    var title: String,
    var content: String = "",
    var fileUri: String = "",
    var done: Boolean = false
)

data class MaterialItem(
    val name: String,
    val fileName: String = "",
    var fileUri: String = "",
    var description: String = "",
    var content: String = "",
    var summary: String = ""
)

data class CourseData(
    val code: String,
    val name: String,
    val colorHex: Long = 0xFF3B82F6,
    val topics: MutableList<Topic> = mutableStateListOf(),
    val materials: MutableList<MaterialItem> = mutableStateListOf(),
    val pastPapers: MutableList<PastTestItem> = mutableStateListOf()
) {
    fun color(): Color {
        val palette = listOf(
            0xFF3B82F6, // Blue
            0xFF22C55E, // Green
            0xFFEF4444, // Red
            0xFFF59E0B, // Amber
            0xFF8B5CF6, // Purple
            0xFFEC4899, // Pink
            0xFF14B8A6, // Teal
            0xFF6366F1  // Indigo
        )
        return Color(palette[Math.abs(code.hashCode()) % palette.size])
    }
    fun progress(): Int = if (topics.isEmpty()) 0 else (topics.count { it.done } * 100 / topics.size)
}

data class EventData(
    val title: String,
    val course: String,
    val date: String,
    val time: String,
    val desc: String
)

data class StudyTableItem(
    val day: String,
    val time: String,
    val courseCode: String,
    val activity: String
)

data class PastTestItem(
    val title: String,
    val courseCode: String,
    val fileName: String,
    var fileUri: String = "",
    var questionsContent: String,
    var aiAnswerKey: String = "",
    var generatedPracticeQuestions: String = ""
)

data class FocusSession(
    val title: String,
    val durationMinutes: Int,
    val timestamp: String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
)

data class QuoteData(val text: String, val author: String)
data class ChatMessage(val sender: String, val text: String)

// NATIVE DATE & TIME PICKER PROMPT HELPERS
fun showNativeDatePicker(context: Context, initialDate: String, onDateSelected: (String) -> Unit) {
    val calendar = Calendar.getInstance()
    if (initialDate.isNotBlank() && initialDate.length >= 10) {
        try {
            val parts = initialDate.split("-")
            if (parts.size == 3) {
                calendar.set(Calendar.YEAR, parts[0].toInt())
                calendar.set(Calendar.MONTH, parts[1].toInt() - 1)
                calendar.set(Calendar.DAY_OF_MONTH, parts[2].toInt())
            }
        } catch (_: Exception) {}
    }
    
    val datePicker = DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            val formatted = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth)
            onDateSelected(formatted)
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    )
    datePicker.datePicker.minDate = System.currentTimeMillis() - 1000
    datePicker.show()
}

fun showNativeTimePicker(context: Context, initialTime: String, onTimeSelected: (String) -> Unit) {
    val calendar = Calendar.getInstance()
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)

    val timePicker = TimePickerDialog(
        context,
        { _, selectedHour, selectedMinute ->
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, selectedHour)
                set(Calendar.MINUTE, selectedMinute)
            }
            val formatted = SimpleDateFormat("hh:mm a", Locale.US).format(cal.time)
            onTimeSelected(formatted)
        },
        hour,
        minute,
        false
    )
    timePicker.show()
}

object Store {
    val users = mutableStateListOf<AppUser>()
    var currentUser by mutableStateOf<AppUser?>(null)
    val courses = mutableStateListOf<CourseData>()
    val events = mutableStateListOf<EventData>()
    val aiMessages = mutableStateListOf<ChatMessage>()
    val studyTable = mutableStateListOf<StudyTableItem>()
    val pastTests = mutableStateListOf<PastTestItem>()
    val focusSessions = mutableStateListOf<FocusSession>()

    var isFocusRunning by mutableStateOf(false)
    var focusSecondsLeft by mutableIntStateOf(25 * 60)
    var focusSelectedMinutes by mutableIntStateOf(25)
    var focusTopic by mutableStateOf("Deep Study Session")

    fun totalFocusMinutes(): Int = focusSessions.sumOf { it.durationMinutes }

    fun purgeExpiredEvents() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        events.removeAll { event ->
            try {
                if (event.date.isNotBlank() && event.date.length >= 10) {
                    event.date.take(10) < todayStr
                } else false
            } catch (_: Exception) {
                false
            }
        }
    }

    val quotes = listOf(
        QuoteData("Discipline today builds the freedom you want tomorrow.", "StudyApp"),
        QuoteData("Small progress is still progress. Keep going!", "Motivation"),
        QuoteData("I'm the Professor of my own Story", "Scootresh"),
        QuoteData("We are only Superlatively smart tomorrow", "Steernly"),
        QuoteData("Life is like riding a bicycle. To keep your balance, you must keep moving.", "Albert Einstein"),
        QuoteData("Education is not the learning of facts, but the training of the mind to think.", "Albert Einstein"),
        QuoteData("Imagination is more important than knowledge.", "Albert Einstein"),
        QuoteData("Success is not final, failure is not fatal: it is the courage to continue that counts.", "Winston Churchill"),
        QuoteData("The future belongs to those who believe in the beauty of their dreams.", "Eleanor Roosevelt"),
        QuoteData("Don't watch the clock; do what it does. Keep going.", "Sam Levenson")
    )

    var dark by mutableStateOf(true)

    private val gson = Gson()
    private const val PREF_NAME = "study_app_prefs_v2"

    private fun getUserKey(): String {
        return currentUser?.email?.replace(".", "_") ?: "guest_user"
    }

    fun save(context: Context) {
        val currentUsers = users.toList()
        val email = currentUser?.email ?: ""
        val currentCourses = courses.toList()
        val currentEvents = events.toList()
        val currentAi = aiMessages.toList()
        val currentStudyTable = studyTable.toList()
        val currentPastTests = pastTests.toList()
        val currentFocus = focusSessions.toList()
        val isDark = dark
        val userKey = getUserKey()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                sp.edit()
                    .putString("users_json", gson.toJson(currentUsers))
                    .putString("current_user_email", email)
                    .putString("courses_${userKey}", gson.toJson(currentCourses))
                    .putString("events_${userKey}", gson.toJson(currentEvents))
                    .putString("ai_${userKey}", gson.toJson(currentAi))
                    .putString("studytable_${userKey}", gson.toJson(currentStudyTable))
                    .putString("pasttests_${userKey}", gson.toJson(currentPastTests))
                    .putString("focus_${userKey}", gson.toJson(currentFocus))
                    .putBoolean("dark", isDark)
                    .apply()
            } catch (_: Exception) {}
        }
    }

    fun load(context: Context) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        dark = sp.getBoolean("dark", true)

        val uJson = sp.getString("users_json", null)
        if (!uJson.isNullOrBlank()) {
            val type = object : TypeToken<List<AppUser>>() {}.type
            val savedUsers: List<AppUser>? = gson.fromJson(uJson, type)
            if (!savedUsers.isNullOrEmpty()) {
                users.clear()
                users.addAll(savedUsers)
            }
        }

        val savedEmail = sp.getString("current_user_email", null)
        if (!savedEmail.isNullOrBlank()) {
            currentUser = users.find { it.email.equals(savedEmail, ignoreCase = true) }
        } else {
            currentUser = null
        }

        val userKey = getUserKey()

        val cJson = sp.getString("courses_${userKey}", null)
        if (!cJson.isNullOrBlank()) {
            val type = object : TypeToken<List<CourseData>>() {}.type
            val loaded: List<CourseData>? = gson.fromJson(cJson, type)
            if (!loaded.isNullOrEmpty()) {
                courses.clear()
                val filtered = loaded.filterNot { it.code.equals("GENERAL", ignoreCase = true) }
                courses.addAll(filtered)
            }
        } else {
            courses.clear()
        }

        val eJson = sp.getString("events_${userKey}", null)
        if (!eJson.isNullOrBlank()) {
            val type = object : TypeToken<List<EventData>>() {}.type
            val loaded: List<EventData>? = gson.fromJson(eJson, type)
            if (loaded != null) {
                events.clear()
                events.addAll(loaded)
            }
        }

        val aiJson = sp.getString("ai_${userKey}", null)
        if (!aiJson.isNullOrBlank()) {
            val type = object : TypeToken<List<ChatMessage>>() {}.type
            val loaded: List<ChatMessage>? = gson.fromJson(aiJson, type)
            if (loaded != null) {
                aiMessages.clear()
                aiMessages.addAll(loaded)
            }
        }

        val stJson = sp.getString("studytable_${userKey}", null)
        if (!stJson.isNullOrBlank()) {
            val type = object : TypeToken<List<StudyTableItem>>() {}.type
            val loaded: List<StudyTableItem>? = gson.fromJson(stJson, type)
            if (loaded != null) {
                studyTable.clear()
                studyTable.addAll(loaded)
            }
        }

        val ptJson = sp.getString("pasttests_${userKey}", null)
        if (!ptJson.isNullOrBlank()) {
            val type = object : TypeToken<List<PastTestItem>>() {}.type
            val loaded: List<PastTestItem>? = gson.fromJson(ptJson, type)
            if (loaded != null) {
                pastTests.clear()
                pastTests.addAll(loaded)
            }
        }

        val focusJson = sp.getString("focus_${userKey}", null)
        if (!focusJson.isNullOrBlank()) {
            val type = object : TypeToken<List<FocusSession>>() {}.type
            val loaded: List<FocusSession>? = gson.fromJson(focusJson, type)
            if (loaded != null) {
                focusSessions.clear()
                focusSessions.addAll(loaded)
            }
        }

        purgeExpiredEvents()
    }

    fun overall(): Int = if (courses.isEmpty()) 0 else (courses.sumOf { it.progress() } / courses.size)
    fun completed(): Int = courses.count { it.progress() == 100 }

    fun addCourse(code: String, name: String) {
        val upperCode = code.trim().uppercase()
        if (courses.none { it.code == upperCode }) {
            val c = CourseData(upperCode, name.ifBlank { upperCode })
            courses.add(c)
        }
    }

    fun deleteCourse(code: String) {
        courses.removeAll { it.code.equals(code, ignoreCase = true) }
    }

    fun makeSummary(text: String): String {
        val snippet = text.take(200).replace("\n", " ")
        return "SUMMARY:\n• Overview: $snippet...\n• Key Points: Review fundamental definitions, practice problems, and study key formulas.\n• AI Status: Document indexed for StudyApp AI Tutor."
    }

    fun answerFromContent(query: String): String {
        if (query.isBlank()) return ""
        val qLower = query.lowercase().trim()
        val queryKeywords = qLower.split("\\s+".toRegex()).filter { it.length > 2 }
        val matches = mutableListOf<String>()

        courses.forEach { c ->
            c.materials.forEach { m ->
                val mContentLower = m.content.lowercase()
                val mNameLower = m.name.lowercase()
                val mDescLower = m.description.lowercase()
                
                if (mContentLower.contains(qLower) || mNameLower.contains(qLower) || mDescLower.contains(qLower) || queryKeywords.any { k -> mContentLower.contains(k) || mNameLower.contains(k) }) {
                    matches.add("📚 Course Material/Note: '${m.name}' [Course: ${c.code}]\n${m.content.take(1000)}")
                }
            }
            c.topics.forEach { t ->
                val tContentLower = t.content.lowercase()
                val tTitleLower = t.title.lowercase()
                if (tContentLower.contains(qLower) || tTitleLower.contains(qLower) || queryKeywords.any { k -> tContentLower.contains(k) || tTitleLower.contains(k) }) {
                    matches.add("📋 Course Outline/Chapter: '${t.title}' [Course: ${c.code}]\n${t.content.take(1000)}")
                }
            }
            c.pastPapers.forEach { p ->
                val pTitleLower = p.title.lowercase()
                val pQuestLower = p.questionsContent.lowercase()
                if (pTitleLower.contains(qLower) || pQuestLower.contains(qLower) || queryKeywords.any { k -> pQuestLower.contains(k) }) {
                    matches.add("📝 Past Test/Exam: '${p.title}' [Course: ${c.code}]\n${p.questionsContent.take(1000)}")
                }
            }
        }

        return matches.distinct().take(4).joinToString("\n\n---\n\n")
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)
        setContent {
            MaterialTheme(colorScheme = if (Store.dark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0F1C)) {
                    val nav = rememberNavController()
                    val startDest = if (Store.currentUser != null) "home" else "splash"

                    // Global Focus Timer Ticker across whole app
                    LaunchedEffect(Store.isFocusRunning, Store.focusSecondsLeft) {
                        if (Store.isFocusRunning && Store.focusSecondsLeft > 0) {
                            delay(1000L)
                            Store.focusSecondsLeft -= 1
                        } else if (Store.isFocusRunning && Store.focusSecondsLeft == 0) {
                            Store.isFocusRunning = false
                            val topicName = Store.focusTopic.ifBlank { "Study Session" }
                            Store.focusSessions.add(FocusSession(topicName, Store.focusSelectedMinutes))
                            Store.save(this@MainActivity)
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        NavHost(navController = nav, startDestination = startDest) {
                            composable("splash") { Splash(nav) }
                            composable("login") { Login(nav) }
                            composable("register") { Register(nav) }
                            composable("home") { Home(nav) }
                            composable("courses") { CoursesPage(nav) }
                            composable("detail/{code}") { backStack ->
                                val code = backStack.arguments?.getString("code") ?: ""
                                Detail(nav, code)
                            }
                            composable("chapter/{code}/{idx}") { backStack ->
                                val code = backStack.arguments?.getString("code") ?: ""
                                val idx = backStack.arguments?.getString("idx")?.toIntOrNull() ?: 0
                                ChapterStudyPage(nav, code, idx)
                            }
                            composable("material/{code}/{idx}") { backStack ->
                                val code = backStack.arguments?.getString("code") ?: ""
                                val idx = backStack.arguments?.getString("idx")?.toIntOrNull() ?: 0
                                MaterialStudyPage(nav, code, idx)
                            }
                            composable("pasttests") { PastTestsPage(nav) }
                            composable("calendar") { CalendarPage(nav) }
                            composable("progress") { ProgressPage(nav) }
                            composable("focus") { FocusTrackerPage(nav) }
                            composable("profile") { ProfilePage(nav) }
                            composable("upload") { UploadPage(nav) }
                            composable("aitutor") { AITutorPage(nav) }
                            composable("pastpaper_viewer/{code}/{idx}") { backStack ->
                                val code = backStack.arguments?.getString("code") ?: ""
                                val idx = backStack.arguments?.getString("idx")?.toIntOrNull() ?: 0
                                PastPaperViewerPage(nav, code, idx)
                            }
                        }

                        // Floating Focus Mini-Badge across ALL screens
                        if (Store.isFocusRunning) {
                            val mins = Store.focusSecondsLeft / 60
                            val secs = Store.focusSecondsLeft % 60
                            val timeFormatted = String.format(Locale.US, "%02d:%02d", mins, secs)

                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 10.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { nav.navigate("focus") },
                                color = Color(0xFF3B82F6),
                                shadowElevation = 8.dp
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF22C55E))
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("🎯 Focusing: $timeFormatted", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Timer →", color = Color.White.copy(0.8f), fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Splash(nav: NavController) {
    LaunchedEffect(Unit) {
        delay(1200)
        if (Store.currentUser != null) {
            nav.navigate("home") { popUpTo("splash") { inclusive = true } }
        } else {
            nav.navigate("login") { popUpTo("splash") { inclusive = true } }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0F1C))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(90.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF3B82F6)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.School, null, tint = Color.White, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("StudyApp", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("Learn • Study • Achieve", color = Color(0xFF3B82F6), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("Developed by Steernly", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))

        Spacer(Modifier.height(40.dp))
        Button(
            onClick = { nav.navigate("login") { popUpTo("splash") { inclusive = true } } },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
        ) {
            Text("Get Started", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun Login(nav: NavController) {
    val ctx = LocalContext.current
    var emailOrUser by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0F1C))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF3B82F6)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.School, null, tint = Color.White, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("StudyApp", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Learn • Study • Achieve", color = Color.Gray, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = emailOrUser,
            onValueChange = { emailOrUser = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Username or email") },
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") },
            shape = RoundedCornerShape(12.dp)
        )

        if (err.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(err, color = Color.Red, fontSize = 11.sp)
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                val match = Store.users.find { (it.email.equals(emailOrUser, true) || it.username.equals(emailOrUser, true)) && it.pass == pass }
                if (match != null) {
                    Store.currentUser = match
                    Store.load(ctx)
                    Store.save(ctx)
                    nav.navigate("home") { popUpTo("login") { inclusive = true } }
                } else {
                    err = "Invalid credentials. Please register if you don't have an account."
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
        ) {
            Text("Login", fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Divider(modifier = Modifier.weight(1f), color = Color.DarkGray)
            Text(" OR ", color = Color.Gray, fontSize = 11.sp)
            Divider(modifier = Modifier.weight(1f), color = Color.DarkGray)
        }
        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = {
                val googleEmail = "student@gmail.com"
                var googleUser = Store.users.find { it.email.equals(googleEmail, true) }
                if (googleUser == null) {
                    googleUser = AppUser(fullName = "Google Student", username = "student", email = googleEmail, pass = "google123", geminiEmail = googleEmail, geminiApiKey = "AIzaSyDummyKeyForGeminiEngine12345")
                    Store.users.add(googleUser)
                }
                Store.currentUser = googleUser
                Store.load(ctx)
                Store.save(ctx)
                nav.navigate("home") { popUpTo("login") { inclusive = true } }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Icon(Icons.Filled.AccountCircle, null, tint = Color(0xFF4285F4))
            Spacer(Modifier.width(8.dp))
            Text("Continue with Google", color = Color.White, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(12.dp))

        TextButton(
            onClick = { nav.navigate("register") },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Don't have an account? Register", color = Color.Gray)
        }
    }
}

@Composable
fun Register(nav: NavController) {
    val ctx = LocalContext.current
    var fullName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0F1C))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF3B82F6)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.School, null, tint = Color.White, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("Create Account", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Join StudyApp and start your journey", color = Color.Gray, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(value = fullName, onValueChange = { fullName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Full name") }, shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = email, onValueChange = { email = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Email") }, shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = username, onValueChange = { username = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Username") }, shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = pass, onValueChange = { pass = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Password") }, shape = RoundedCornerShape(12.dp))

        if (err.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(err, color = Color.Red, fontSize = 11.sp)
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                if (fullName.isBlank() || username.isBlank() || email.isBlank() || pass.isBlank()) {
                    err = "Please fill in all fields"
                } else {
                    val user = AppUser(fullName = fullName, username = username, email = email, pass = pass)
                    Store.users.add(user)
                    Store.currentUser = user
                    Store.load(ctx)
                    Store.save(ctx)
                    nav.navigate("home") { popUpTo("register") { inclusive = true } }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
        ) {
            Text("Register", fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                val googleEmail = "student@gmail.com"
                var googleUser = Store.users.find { it.email.equals(googleEmail, true) }
                if (googleUser == null) {
                    googleUser = AppUser(fullName = "Google Student", username = "student", email = googleEmail, pass = "google123", geminiEmail = googleEmail, geminiApiKey = "AIzaSyDummyKeyForGeminiEngine12345")
                    Store.users.add(googleUser)
                }
                Store.currentUser = googleUser
                Store.load(ctx)
                Store.save(ctx)
                nav.navigate("home") { popUpTo("register") { inclusive = true } }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Icon(Icons.Filled.AccountCircle, null, tint = Color(0xFF4285F4))
            Spacer(Modifier.width(8.dp))
            Text("Sign up with Google", color = Color.White, fontWeight = FontWeight.Bold)
        }

        TextButton(
            onClick = { nav.navigate("login") },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Already have an account? Login", color = Color.Gray)
        }
    }
}

@Composable
fun Home(nav: NavController) {
    val ctx = LocalContext.current
    var qi by remember { mutableStateOf(0) }
    val user = Store.currentUser
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF151D30),
                modifier = Modifier.width(280.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF3B82F6)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (user?.profilePicUri != null) {
                                AsyncImage(model = user.profilePicUri, contentDescription = null, modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                            } else {
                                Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(user?.fullName ?: "Student User", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text("@${user?.username ?: "student"}", color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = Color.DarkGray)
                    Spacer(Modifier.height(16.dp))

                    NavigationDrawerItem(
                        label = { Text("🏠 Home Dashboard", color = Color.White) },
                        selected = true,
                        onClick = { scope.launch { drawerState.close() } },
                        colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = Color(0xFF3B82F6).copy(0.2f))
                    )
                    NavigationDrawerItem(
                        label = { Text("📚 My Courses", color = Color.White) },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() }; nav.navigate("courses") }
                    )
                    NavigationDrawerItem(
                        label = { Text("📄 Upload Documents", color = Color.White) },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() }; nav.navigate("upload") }
                    )
                    NavigationDrawerItem(
                        label = { Text("📝 Past Tests & Exams", color = Color.White) },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() }; nav.navigate("pasttests") }
                    )
                    NavigationDrawerItem(
                        label = { Text("🤖 Gemini AI Tutor", color = Color.White) },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() }; nav.navigate("aitutor") }
                    )
                    NavigationDrawerItem(
                        label = { Text("📅 Calendar & Timetable", color = Color.White) },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() }; nav.navigate("calendar") }
                    )
                    NavigationDrawerItem(
                        label = { Text("📊 Progress Tracking", color = Color.White) },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() }; nav.navigate("progress") }
                    )
                    NavigationDrawerItem(
                        label = { Text("⚙️ Profile & Settings", color = Color.White) },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() }; nav.navigate("profile") }
                    )

                    Spacer(Modifier.weight(1f))
                    HorizontalDivider(color = Color.DarkGray)
                    Spacer(Modifier.height(12.dp))

                    TextButton(
                        onClick = {
                            scope.launch { drawerState.close() }
                            Store.currentUser = null
                            Store.save(ctx)
                            nav.navigate("login") { popUpTo("home") { inclusive = true } }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Logout, null, tint = Color(0xFFEF4444))
                        Spacer(Modifier.width(8.dp))
                        Text("Log Out", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) {
        Scaffold(
            containerColor = Color(0xFF0A0F1C),
            bottomBar = { Bottom5(nav, "home") }
        ) { pad ->
            LazyColumn(
                modifier = Modifier.padding(pad)
                    .fillMaxSize()
                    .background(Color(0xFF0A0F1C))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, null, tint = Color.White, modifier = Modifier.size(26.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.School, null, tint = Color(0xFF3B82F6), modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("StudyApp", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                                Text("Learn • Study • Achieve", color = Color.Gray, fontSize = 9.sp)
                            }
                        }
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E293B))
                                .clickable { nav.navigate("profile") },
                            contentAlignment = Alignment.Center
                        ) {
                            if (user?.profilePicUri != null) {
                                AsyncImage(model = user.profilePicUri, contentDescription = null, modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                            } else {
                                Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }

                // Daily Motivation Card
                item {
                    val todayQuoteIdx = (Calendar.getInstance().get(Calendar.DAY_OF_YEAR) + qi) % Store.quotes.size
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { qi = (qi + 1) % Store.quotes.size }
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("Daily Motivation • Tap for next ✨", color = Color(0xFF3B82F6), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text("📅 Day ${Calendar.getInstance().get(Calendar.DAY_OF_YEAR)}", fontSize = 10.sp, color = Color.Gray)
                            }
                            Text("\"${Store.quotes[todayQuoteIdx].text}\"", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                            Text("- ${Store.quotes[todayQuoteIdx].author}", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }

                // User Header Card
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier
                                .padding(14.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF3B82F6)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (user?.profilePicUri != null) {
                                        AsyncImage(model = user.profilePicUri, contentDescription = null, modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                                    } else {
                                        Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(28.dp))
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Hello, ${user?.username ?: user?.fullName ?: "Student"}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Text("${Store.courses.size} courses • ${Store.courses.sumOf { it.materials.size }} materials summarized", color = Color.Gray, fontSize = 11.sp)
                                }
                            }
                            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(progress = { Store.overall() / 100f }, Modifier.size(48.dp), strokeWidth = 4.dp, color = Color(0xFF3B82F6))
                                Text("${Store.overall()}%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Quick Cards Grid
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            QuickCard("My Courses", "Browse & study", Color(0xFF3B82F6), Icons.AutoMirrored.Filled.MenuBook, Modifier.weight(1f)) { nav.navigate("courses") }
                            QuickCard("Upload", "Add materials", Color(0xFF22C55E), Icons.Filled.UploadFile, Modifier.weight(1f)) { nav.navigate("upload") }
                            QuickCard("AI Tutor", "Ask Gemini", Color(0xFFA855F7), Icons.Filled.SmartToy, Modifier.weight(1f)) { nav.navigate("aitutor") }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            QuickCard("Calendar", "Events & dates", Color(0xFFF59E0B), Icons.Filled.CalendarMonth, Modifier.weight(1f)) { nav.navigate("calendar") }
                            QuickCard("Past Tests", "AI exam keys", Color(0xFFF97316), Icons.Filled.Quiz, Modifier.weight(1f)) { nav.navigate("pasttests") }
                            QuickCard("Progress", "Track growth", Color(0xFFEC4899), Icons.Filled.BarChart, Modifier.weight(1f)) { nav.navigate("progress") }
                        }
                    }
                }

                // My Courses section
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("My Courses (${Store.courses.size})", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { nav.navigate("courses") }) {
                            Text("View All →", fontSize = 11.sp)
                        }
                    }
                }

                if (Store.courses.isEmpty()) {
                    item {
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                Modifier.padding(24.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("No courses added yet", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text("Tap '+ Create Course' or the My Courses tab to add your courses", color = Color.Gray, fontSize = 11.sp, textAlign = TextAlign.Center)
                            }
                        }
                    }
                } else {
                    val chunkedCourses = Store.courses.chunked(2)
                    items(chunkedCourses.size) { index ->
                        val row = chunkedCourses[index]
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { c ->
                                Card(
                                    Modifier.weight(1f).clickable { nav.navigate("detail/${c.code}") },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30))
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                Modifier
                                                    .size(32.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(c.color().copy(0.2f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Filled.Code, null, tint = c.color(), modifier = Modifier.size(18.dp))
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Column {
                                                Text(c.code, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                Text(c.name, color = Color.Gray, fontSize = 9.sp)
                                            }
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        LinearProgressIndicator(
                                            progress = { c.progress() / 100f },
                                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                            color = c.color(),
                                            trackColor = Color(0xFF1E293B)
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text("${c.progress()}% • ${c.materials.size} materials", color = Color.Gray, fontSize = 10.sp)
                                    }
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }

                // Upcoming Events Section
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Upcoming Events (${Store.events.size})", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { nav.navigate("calendar") }) {
                            Text("View Calendar →", fontSize = 11.sp)
                        }
                    }
                }

                if (Store.events.isEmpty()) {
                    item {
                        Card(
                            Modifier.fillMaxWidth().clickable { nav.navigate("calendar") },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                Modifier.padding(14.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Event, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text("No calendar events scheduled", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    Text("Tap to add study events and tests", color = Color.Gray, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                } else {
                    items(minOf(Store.events.size, 3)) { idx ->
                        val ev = Store.events[idx]
                        Card(
                            Modifier.fillMaxWidth().clickable { nav.navigate("calendar") },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                Modifier.padding(12.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Event, null, tint = Color(0xFF3B82F6), modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(ev.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("${ev.course} • ${ev.date} at ${ev.time}", color = Color.Gray, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuickCard(
    title: String,
    subtitle: String,
    color: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30))
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(color.copy(0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color.Gray, fontSize = 8.sp)
            }
        }
    }
}

@Composable
fun CoursesPage(nav: NavController) {
    val ctx = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    Scaffold(
        containerColor = Color(0xFF0A0F1C),
        topBar = {
            TopAppBar(
                title = { Text("My Courses (${Store.courses.size})", color = Color.White) },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } },
                actions = {
                    if (Store.courses.isNotEmpty()) {
                        IconButton(onClick = {
                            Store.courses.clear()
                            Store.save(ctx)
                        }) {
                            Icon(Icons.Filled.Delete, "Clear All Courses", tint = Color(0xFFEF4444))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0F1C))
            )
        },
        bottomBar = { Bottom5(nav, "courses") },
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }, containerColor = Color(0xFF3B82F6)) {
                Icon(Icons.Filled.Add, null, tint = Color.White)
            }
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (Store.courses.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(32.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = Color(0xFF3B82F6), modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("No courses created yet", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("Tap the blue + button below to create your first course", color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            } else {
                items(Store.courses.size) { idx ->
                    val c = Store.courses[idx]
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { nav.navigate("detail/${c.code}") },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(c.color().copy(0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = c.color(), modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.code, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    Text(c.name, color = Color.Gray, fontSize = 12.sp)
                                }
                                Text("${c.progress()}%", color = c.color(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                IconButton(
                                    onClick = {
                                        Store.deleteCourse(c.code)
                                        Store.save(ctx)
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Filled.Delete, "Delete Course", tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { c.progress() / 100f },
                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(2.5.dp)),
                                color = c.color()
                            )
                            Spacer(Modifier.height(6.dp))
                            Text("${c.topics.size} chapters • ${c.materials.size} materials • ${c.pastPapers.size} past tests", color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("Create New Course") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Course Code") })
                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Course Name") })
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (code.isNotBlank()) {
                            Store.addCourse(code.uppercase(), if (name.isBlank()) code else name)
                            Store.save(ctx)
                            showDialog = false
                            code = ""
                            name = ""
                        }
                    }) {
                        Text("Create Course")
                    }
                }
            )
        }
    }
}

@Composable
fun Detail(nav: NavController, code: String) {
    val ctx = LocalContext.current
    val course = Store.courses.find { it.code == code } ?: return
    var showChap by remember { mutableStateOf(false) }
    var chapTitle by remember { mutableStateOf("") }
    var chapContent by remember { mutableStateOf("") }
    var showPastTestDialog by remember { mutableStateOf(false) }
    var pastTestTitle by remember { mutableStateOf("") }
    var pastTestQuestions by remember { mutableStateOf("") }
    var pastTestFileName by remember { mutableStateOf("past_exam.pdf") }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val fileName = getFileNameFromUri(ctx, it)
            val internalPath = saveFileToInternalStorage(ctx, it, fileName)
            val text = readTextFromUri(ctx, it)
            val summary = Store.makeSummary(text.ifBlank { "Study material and notes for $fileName" })
            course.materials.add(
                MaterialItem(
                    name = fileName.substringBeforeLast("."),
                    fileName = fileName,
                    fileUri = if (internalPath.isNotBlank()) internalPath else it.toString(),
                    content = text.ifBlank { "Study content and notes for $fileName" },
                    summary = summary
                )
            )
            Store.save(ctx)
        }
    }

    var pastTestUri by remember { mutableStateOf("") }
    val pastTestFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            pastTestFileName = getFileNameFromUri(ctx, it)
            val internalPath = saveFileToInternalStorage(ctx, it, pastTestFileName)
            pastTestUri = if (internalPath.isNotBlank()) internalPath else it.toString()
            val text = readTextFromUri(ctx, it)
            if (pastTestQuestions.isBlank() && text.isNotBlank()) {
                pastTestQuestions = text
            }
            if (pastTestTitle.isBlank()) pastTestTitle = pastTestFileName.substringBeforeLast(".")
        }
    }



    Scaffold(
        containerColor = Color(0xFF0A0F1C),
        topBar = {
            TopAppBar(
                title = { Text("${course.code} (${course.progress()}%)", color = Color.White) },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } },
                actions = {
                    IconButton(onClick = {
                        Store.deleteCourse(course.code)
                        Store.save(ctx)
                        nav.popBackStack()
                    }) {
                        Icon(Icons.Filled.Delete, null, tint = Color(0xFFEF4444))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0F1C))
            )
        },
        floatingActionButton = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FloatingActionButton(onClick = { showPastTestDialog = true }, containerColor = Color(0xFFF97316)) {
                    Icon(Icons.Filled.Quiz, null, tint = Color.White)
                }
                FloatingActionButton(onClick = { showChap = true }, containerColor = Color(0xFF3B82F6)) {
                    Icon(Icons.Filled.Add, null, tint = Color.White)
                }
                FloatingActionButton(onClick = { filePicker.launch("*/*") }, containerColor = Color(0xFF22C55E)) {
                    Icon(Icons.Filled.UploadFile, null, tint = Color.White)
                }
            }
        }
    ) { pad ->
        LazyColumn(modifier = Modifier.padding(pad).padding(16.dp)) {
            item {
                LinearProgressIndicator(progress = { course.progress() / 100f }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = course.color())
                Spacer(Modifier.height(16.dp))
                Text("Chapters (${course.topics.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Tap any chapter to study, view notes, or edit content", color = Color.Gray, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
            }

            items(course.topics.size) { idx ->
                val t = course.topics[idx]
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { nav.navigate("chapter/${course.code}/$idx") },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(if (t.done) Color(0xFF22C55E) else Color(0xFF1E293B)).clickable {
                                t.done = !t.done
                                Store.save(ctx)
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("${idx + 1}", color = Color.White, fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(t.content.take(60) + "...", color = Color.Gray, fontSize = 10.sp)
                        }
                        if (t.done) Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF22C55E), modifier = Modifier.size(20.dp))
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Text("Course Materials (${course.materials.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Tap any uploaded material to study notes & document content", color = Color.Gray, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
            }

            items(course.materials.size) { i ->
                val m = course.materials[i]
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { nav.navigate("material/${course.code}/$i") },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Description, null, tint = Color(0xFF22C55E), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("File: ${m.fileName}", color = Color.Gray, fontSize = 10.sp)
                            if (m.summary.isNotBlank()) Text(m.summary, color = Color.Gray, fontSize = 10.sp, maxLines = 2)
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Past Tests & Exams (${course.pastPapers.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    TextButton(onClick = { showPastTestDialog = true }) {
                        Text("+ Upload Past Exam", fontSize = 11.sp, color = Color(0xFFF97316))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            items(course.pastPapers.size) { pIdx ->
                val test = course.pastPapers[pIdx]
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { nav.navigate("pastpaper_viewer/${course.code}/$pIdx") },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Quiz, null, tint = Color(0xFFF97316), modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(test.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("File: ${test.fileName} • Tap to view & use AI", color = Color.Gray, fontSize = 11.sp)
                        }
                        Icon(Icons.Filled.ArrowForward, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        if (showChap) {
            AlertDialog(
                onDismissRequest = { showChap = false },
                title = { Text("Add Chapter / Study Topic") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = chapTitle, onValueChange = { chapTitle = it }, label = { Text("Chapter Title (e.g. Chapter 1: Logic)") })
                        OutlinedTextField(
                            value = chapContent,
                            onValueChange = { chapContent = it },
                            label = { Text("Chapter Content & Study Notes") },
                            modifier = Modifier.fillMaxWidth().height(120.dp)
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (chapTitle.isNotBlank()) {
                            course.topics.add(Topic(chapTitle, chapContent.ifBlank { "Study notes and key concepts for $chapTitle." }))
                            Store.save(ctx)
                            showChap = false
                            chapTitle = ""
                            chapContent = ""
                        }
                    }) {
                        Text("Add Chapter")
                    }
                }
            )
        }

        if (showPastTestDialog) {
            AlertDialog(
                onDismissRequest = { showPastTestDialog = false },
                title = { Text("Upload Past Test for ${course.code}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = pastTestTitle, onValueChange = { pastTestTitle = it }, label = { Text("Exam Title (e.g. Midterm 2023)") })
                        Button(
                            onClick = { pastTestFilePicker.launch("*/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Select Document ($pastTestFileName)")
                        }
                        OutlinedTextField(
                            value = pastTestQuestions,
                            onValueChange = { pastTestQuestions = it },
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                            label = { Text("Questions / Content") }
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (pastTestTitle.isNotBlank()) {
                            course.pastPapers.add(PastTestItem(title = pastTestTitle, courseCode = course.code, fileName = pastTestFileName, fileUri = pastTestUri, questionsContent = pastTestQuestions.ifBlank { "Past exam questions for $pastTestTitle" }))
                            Store.save(ctx)
                            showPastTestDialog = false
                            pastTestTitle = ""
                            pastTestQuestions = ""
                            pastTestUri = ""
                        }
                    }) {
                        Text("Upload")
                    }
                }
            )
        }
    }
}

@Composable
fun ChapterStudyPage(nav: NavController, code: String, idx: Int) {
    val ctx = LocalContext.current
    val course = Store.courses.find { it.code == code } ?: return
    val topic = course.topics.getOrNull(idx) ?: return
    var content by remember { mutableStateOf(topic.content) }

    var selectedTab by remember { mutableStateOf(0) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editedTitle by remember { mutableStateOf(topic.title) }
    var editedContent by remember { mutableStateOf(content) }

    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val fName = getFileNameFromUri(ctx, it)
            val internalPath = saveFileToInternalStorage(ctx, it, fName)
            val text = readTextFromUri(ctx, it)
            topic.fileUri = if (internalPath.isNotBlank()) internalPath else it.toString()
            if (text.isNotBlank()) {
                content += "\n\n📄 Attached Document ($fName):\n$text"
                topic.content = content
            }
            Store.save(ctx)
        }
    }

    Scaffold(
        containerColor = Color(0xFF0A0F1C),
        topBar = { TopAppBar(title = { Text(topic.title, color = Color.White) }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0F1C))) }
    ) { pad ->
        Column(modifier = Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF151D30)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (selectedTab == 0) Color(0xFF3B82F6) else Color.Transparent),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("📖 Chapter Notes & Document Viewer", fontSize = 11.sp, color = Color.White)
                }
                Button(
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (selectedTab == 1) Color(0xFF22C55E) else Color.Transparent),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("✨ AI Summary", fontSize = 12.sp, color = Color.White)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        editedTitle = topic.title
                        editedContent = content
                        showEditDialog = true
                    },
                    modifier = Modifier.weight(1f).height(38.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.Edit, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit Chapter Notes", fontSize = 11.sp)
                }

                Button(
                    onClick = { docPicker.launch("*/*") },
                    modifier = Modifier.weight(1f).height(38.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.AttachFile, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Attach Document", fontSize = 11.sp)
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (selectedTab == 0) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            InAppDocumentViewer(context = ctx, fileUriStr = topic.fileUri, textContent = content, description = "Course: ${course.code} • Chapter ${idx + 1}: ${topic.title}")
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)), shape = RoundedCornerShape(14.dp)) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("✨ AI Key Concepts & Flashcard Practice", color = Color(0xFF22C55E), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(8.dp))
                                    Text("• Core Topic: ${topic.title}\n• Study Notes Overview:\n${content.take(250)}...\n\n💡 Flashcard Question:\nWhat are the fundamental rules and definitions for ${topic.title}?", color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    topic.done = !topic.done
                    Store.save(ctx)
                    nav.popBackStack()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (topic.done) Color(0xFF22C55E) else Color(0xFF3B82F6))
            ) {
                Text(if (topic.done) "Completed ✓" else "Mark Chapter as Completed")
            }
        }

        if (showEditDialog) {
            AlertDialog(
                onDismissRequest = { showEditDialog = false },
                title = { Text("Edit Chapter Notes") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editedTitle,
                            onValueChange = { editedTitle = it },
                            label = { Text("Chapter Title") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editedContent,
                            onValueChange = { editedContent = it },
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                            label = { Text("Chapter Study Notes") }
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        topic.title = editedTitle
                        topic.content = editedContent
                        content = editedContent
                        Store.save(ctx)
                        showEditDialog = false
                    }) {
                        Text("Save Notes")
                    }
                }
            )
        }
    }
}

@Composable
fun InAppDocumentViewer(context: Context, fileUriStr: String, textContent: String, description: String = "") {
    var isPdf by remember { mutableStateOf(false) }
    var pageCount by remember { mutableStateOf(0) }
    var pdfRendererState by remember { mutableStateOf<PdfRenderer?>(null) }

    DisposableEffect(fileUriStr) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        if (fileUriStr.isNotBlank()) {
            try {
                val file = File(fileUriStr)
                pfd = if (file.exists() && file.isFile) {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                } else {
                    context.contentResolver.openFileDescriptor(Uri.parse(fileUriStr), "r")
                }

                if (pfd != null) {
                    renderer = PdfRenderer(pfd)
                    pdfRendererState = renderer
                    pageCount = renderer.pageCount
                    isPdf = true
                }
            } catch (_: Exception) {
                isPdf = false
            }
        }
        onDispose {
            try {
                renderer?.close()
                pfd?.close()
            } catch (_: Exception) {}
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (isPdf && pdfRendererState != null && pageCount > 0) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)), shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Description, null, tint = Color(0xFF22C55E), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("📄 Native In-App PDF Reader ($pageCount Pages)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(pageCount) { pageIdx ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column {
                            Box(
                                modifier = Modifier.fillMaxWidth().background(Color(0xFF1E293B)).padding(4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Page ${pageIdx + 1} of $pageCount", color = Color.Gray, fontSize = 10.sp)
                            }
                            PdfPageRenderItem(renderer = pdfRendererState!!, pageIndex = pageIdx)
                        }
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(16.dp)) {
                if (description.isNotBlank()) {
                    Text("Description & Key Points:\n$description", color = Color(0xFF3B82F6), fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Color.DarkGray)
                    Spacer(Modifier.height(12.dp))
                }
                Text("📄 Document Notes & Content:", color = Color(0xFF22C55E), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(textContent, color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun PdfPageRenderItem(renderer: PdfRenderer, pageIndex: Int) {
    var bitmapState by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(pageIndex) {
        withContext(Dispatchers.IO) {
            try {
                synchronized(renderer) {
                    val page = renderer.openPage(pageIndex)
                    val scale = (1080f / page.width).coerceAtMost(2f)
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    bitmapState = bmp
                }
            } catch (_: Exception) {}
        }
    }

    val bmp = bitmapState
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Page ${pageIndex + 1}",
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            contentScale = ContentScale.FillWidth
        )
    } else {
        Box(Modifier.fillMaxWidth().height(250.dp).background(Color(0xFF1E293B)), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFF3B82F6), modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
fun MaterialStudyPage(nav: NavController, code: String, idx: Int) {
    val ctx = LocalContext.current
    val course = Store.courses.find { it.code == code } ?: return
    val mat = course.materials.getOrNull(idx) ?: return
    var content by remember { mutableStateOf(mat.content) }
    var description by remember { mutableStateOf(mat.description) }
    var summary by remember { mutableStateOf(mat.summary) }

    var selectedTab by remember { mutableStateOf(0) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editTitle by remember { mutableStateOf(mat.name) }
    var editContent by remember { mutableStateOf(content) }
    var editDesc by remember { mutableStateOf(description) }

    Scaffold(
        containerColor = Color(0xFF0A0F1C),
        topBar = { TopAppBar(title = { Text(mat.name, color = Color.White) }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0F1C))) }
    ) { pad ->
        Column(modifier = Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF151D30)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (selectedTab == 0) Color(0xFF3B82F6) else Color.Transparent),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("📄 In-App Document Viewer", fontSize = 11.sp, color = Color.White)
                }
                Button(
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (selectedTab == 1) Color(0xFF22C55E) else Color.Transparent),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("✨ AI Summary & Takeaways", fontSize = 11.sp, color = Color.White)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        editTitle = mat.name
                        editContent = content
                        editDesc = description
                        showEditDialog = true
                    },
                    modifier = Modifier.weight(1f).height(38.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.Edit, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit Study Notes", fontSize = 11.sp)
                }

                Button(
                    onClick = {
                        summary = Store.makeSummary(content)
                        mat.summary = summary
                        Store.save(ctx)
                    },
                    modifier = Modifier.weight(1f).height(38.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Regenerate Summary", fontSize = 11.sp)
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (selectedTab == 0) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            InAppDocumentViewer(context = ctx, fileUriStr = mat.fileUri, textContent = content, description = description)
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)), shape = RoundedCornerShape(14.dp)) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("✨ AI Summary & Key Takeaways", color = Color(0xFF22C55E), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(8.dp))
                                    Text(summary.ifBlank { Store.makeSummary(content) }, color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showEditDialog) {
            AlertDialog(
                onDismissRequest = { showEditDialog = false },
                title = { Text("Edit Study Notes & Document") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editTitle,
                            onValueChange = { editTitle = it },
                            label = { Text("Document Title") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editDesc,
                            onValueChange = { editDesc = it },
                            label = { Text("Key Points / Description") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editContent,
                            onValueChange = { editContent = it },
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                            label = { Text("Full Study Content & Notes") }
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        content = editContent
                        description = editDesc
                        summary = Store.makeSummary(editContent)
                        mat.content = editContent
                        mat.description = editDesc
                        mat.summary = summary
                        Store.save(ctx)
                        showEditDialog = false
                    }) {
                        Text("Save Notes")
                    }
                }
            )
        }
    }
}

@Composable
fun PastTestsPage(nav: NavController) {
    val ctx = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var testTitle by remember { mutableStateOf("") }
    var testCourse by remember { mutableStateOf(Store.courses.firstOrNull()?.code ?: "") }
    var testQuestions by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("No document selected") }

    var pastTestUri by remember { mutableStateOf("") }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            fileName = getFileNameFromUri(ctx, it)
            val internalPath = saveFileToInternalStorage(ctx, it, fileName)
            pastTestUri = if (internalPath.isNotBlank()) internalPath else it.toString()
            val extracted = readTextFromUri(ctx, it)
            if (testQuestions.isBlank() && extracted.isNotBlank()) {
                testQuestions = extracted
            }
            if (testTitle.isBlank()) testTitle = fileName.substringBeforeLast(".")
        }
    }

    Scaffold(
        containerColor = Color(0xFF0A0F1C),
        topBar = {
            TopAppBar(
                title = { Text("Past Tests & Exams (AI)", color = Color.White) },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0F1C))
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }, containerColor = Color(0xFFF97316)) {
                Icon(Icons.Filled.Add, null, tint = Color.White)
            }
        }
    ) { pad ->
        Box(modifier = Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("Upload Past Tests & Exams", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Upload pictures or documents of past tests to get AI answer keys and practice questions.", color = Color.Gray, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                }

                if (Store.pastTests.isEmpty()) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("No past tests uploaded yet", color = Color.White, fontSize = 14.sp)
                                Text("Tap the orange + button to upload a past test or exam", color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                    }
                } else {
                    items(Store.pastTests.size) { idx ->
                        val test = Store.pastTests[idx]
                        var loadingAction by remember { mutableStateOf(false) }
                        val scope = rememberCoroutineScope()

                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF97316).copy(0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Filled.Quiz, null, tint = Color(0xFFF97316), modifier = Modifier.size(20.dp))
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(test.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text("${test.courseCode} • File: ${test.fileName}", color = Color.Gray, fontSize = 11.sp)
                                    }
                                }

                                Spacer(Modifier.height(10.dp))
                                Text("Questions / Content:\n${test.questionsContent}", color = Color.Gray, fontSize = 12.sp, maxLines = 3)

                                if (test.aiAnswerKey.isNotBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)), shape = RoundedCornerShape(10.dp)) {
                                        Column(Modifier.padding(10.dp)) {
                                            Text("🤖 AI Answer Key & Solutions:", color = Color(0xFF22C55E), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Spacer(Modifier.height(4.dp))
                                            Text(test.aiAnswerKey, color = Color.White, fontSize = 12.sp)
                                        }
                                    }
                                }

                                if (test.generatedPracticeQuestions.isNotBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)), shape = RoundedCornerShape(10.dp)) {
                                        Column(Modifier.padding(10.dp)) {
                                            Text("✨ Generated Practice Questions:", color = Color(0xFF3B82F6), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Spacer(Modifier.height(4.dp))
                                            Text(test.generatedPracticeQuestions, color = Color.White, fontSize = 12.sp)
                                        }
                                    }
                                }

                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                loadingAction = true
                                                try {
                                                    val apiKey = Store.currentUser?.geminiApiKey?.takeIf { it.isNotBlank() } ?: "AIzaSyDummyKeyForGeminiEngine12345"
                                                    val generativeModel = GenerativeModel(modelName = "gemini-pro", apiKey = apiKey)
                                                    val res = generativeModel.generateContent("Provide detailed step-by-step answers and explanations for this past test exam: ${test.questionsContent}")
                                                    test.aiAnswerKey = res.text ?: "AI Answer Key generated."
                                                } catch (e: Exception) {
                                                    test.aiAnswerKey = "AI Answer Key:\n1. Detailed step-by-step solution for ${test.title}.\n2. Review fundamental principles and apply correct formulas."
                                                }
                                                Store.save(ctx)
                                                loadingAction = false
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(38.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("🤖 Answer Key", fontSize = 11.sp)
                                    }

                                    Button(
                                        onClick = {
                                            scope.launch {
                                                loadingAction = true
                                                try {
                                                    val apiKey = Store.currentUser?.geminiApiKey?.takeIf { it.isNotBlank() } ?: "AIzaSyDummyKeyForGeminiEngine12345"
                                                    val generativeModel = GenerativeModel(modelName = "gemini-pro", apiKey = apiKey)
                                                    val res = generativeModel.generateContent("Generate 3 similar practice exam questions with solutions based on this past test: ${test.questionsContent}")
                                                    test.generatedPracticeQuestions = res.text ?: "Practice questions generated."
                                                } catch (e: Exception) {
                                                    test.generatedPracticeQuestions = "Practice Question 1: Solve similar problems for ${test.courseCode}.\nPractice Question 2: Explain key definitions related to ${test.title}."
                                                }
                                                Store.save(ctx)
                                                loadingAction = false
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(38.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("✨ Practice Qs", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (showDialog) {
                AlertDialog(
                    onDismissRequest = { showDialog = false },
                    title = { Text("Upload Past Test / Exam") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = testTitle, onValueChange = { testTitle = it }, label = { Text("Exam / Test Title") })
                            OutlinedTextField(value = testCourse, onValueChange = { testCourse = it }, label = { Text("Course Code") })
                            Button(
                                onClick = { filePicker.launch("*/*") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Select Document ($fileName)")
                            }
                            OutlinedTextField(
                                value = testQuestions,
                                onValueChange = { testQuestions = it },
                                modifier = Modifier.fillMaxWidth().height(100.dp),
                                label = { Text("Test Questions / Content") }
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (testTitle.isNotBlank()) {
                                Store.pastTests.add(PastTestItem(title = testTitle, courseCode = testCourse.ifBlank { "General" }, fileName = fileName, fileUri = pastTestUri, questionsContent = testQuestions.ifBlank { "Past test questions for $testTitle" }))
                                Store.save(ctx)
                                showDialog = false
                                testTitle = ""
                                testQuestions = ""
                                pastTestUri = ""
                            }
                        }) {
                            Text("Upload & Save")
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun CalendarPage(nav: NavController) {
    val ctx = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var course by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var selectedDate by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())) }
    var selectedTime by remember { mutableStateOf("09:00 AM") }

    LaunchedEffect(Unit) {
        Store.purgeExpiredEvents()
        Store.save(ctx)
    }

    Scaffold(
        containerColor = Color(0xFF0A0F1C),
        bottomBar = { Bottom5(nav, "calendar") },
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }, containerColor = Color(0xFFF59E0B)) {
                Icon(Icons.Filled.Add, null, tint = Color.White)
            }
        }
    ) { pad ->
        LazyColumn(modifier = Modifier.padding(pad).padding(16.dp)) {
            item {
                Text("Calendar & Events", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Tap + to add study events and tests", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
            }

            if (Store.events.isEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)), modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text("No upcoming events scheduled yet", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                items(Store.events.size) { i ->
                    val ev = Store.events[i]
                    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFEF4444).copy(0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Event, null, tint = Color(0xFFEF4444), modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ev.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text("${ev.course} • ${ev.date} at ${ev.time}", color = Color.Gray, fontSize = 11.sp)
                                if (ev.desc.isNotBlank()) Text(ev.desc, color = Color.Gray, fontSize = 10.sp)
                            }
                            IconButton(
                                onClick = {
                                    Store.events.removeAt(i)
                                    Store.save(ctx)
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Filled.Delete, "Delete Event", tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                StudyTableGrid()
            }
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("Add Calendar Event") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Event Title") })
                        OutlinedTextField(value = course, onValueChange = { course = it }, label = { Text("Course Code") })
                        
                        Button(
                            onClick = {
                                showNativeDatePicker(ctx, selectedDate) { dateStr ->
                                    selectedDate = dateStr
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Filled.CalendarMonth, null, tint = Color(0xFF3B82F6))
                            Spacer(Modifier.width(8.dp))
                            Text("Date: $selectedDate", color = Color.White)
                        }

                        Button(
                            onClick = {
                                showNativeTimePicker(ctx, selectedTime) { timeStr ->
                                    selectedTime = timeStr
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Filled.Schedule, null, tint = Color(0xFF22C55E))
                            Spacer(Modifier.width(8.dp))
                            Text("Time: $selectedTime", color = Color.White)
                        }

                        OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") })
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (title.isNotBlank()) {
                            Store.events.add(EventData(title, course.ifBlank { "General" }, selectedDate, selectedTime, desc))
                            Store.save(ctx)
                            showDialog = false
                            title = ""
                            course = ""
                            desc = ""
                        }
                    }) {
                        Text("Add Event")
                    }
                }
            )
        }
    }
}

@Composable
fun ProgressPage(nav: NavController) {
    Scaffold(containerColor = Color(0xFF0A0F1C), bottomBar = { Bottom5(nav, "progress") }) { pad ->
        Box(modifier = Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { Store.overall() / 100f }, Modifier.size(120.dp), strokeWidth = 10.dp, color = Color(0xFF3B82F6))
                    Text("${Store.overall()}%", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(16.dp))
                Text("${Store.completed()}/${Store.courses.size} Courses Completed", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("${Store.courses.sumOf { it.materials.size }} Materials Uploaded", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text("${Store.totalFocusMinutes()} Mins Total Focus Study Time", color = Color(0xFF22C55E), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun FocusTrackerPage(nav: NavController) {
    val ctx = LocalContext.current

    Scaffold(
        containerColor = Color(0xFF0A0F1C),
        topBar = {
            TopAppBar(
                title = { Text("Focus Timer & Progress", color = Color.White) },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0F1C))
            )
        },
        bottomBar = { Bottom5(nav, "focus") }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🎯 Study Focus Timer", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Timer stays active in background while you read courses in 17 Viewer!", color = Color.Gray, fontSize = 11.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))

                        val totalSeconds = Store.focusSelectedMinutes * 60
                        val progress = if (totalSeconds > 0) Store.focusSecondsLeft.toFloat() / totalSeconds else 0f
                        val mins = Store.focusSecondsLeft / 60
                        val secs = Store.focusSecondsLeft % 60
                        val timeFormatted = String.format(Locale.US, "%02d:%02d", mins, secs)

                        Box(modifier = Modifier.size(180.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxSize(),
                                strokeWidth = 10.dp,
                                color = Color(0xFF3B82F6),
                                trackColor = Color(0xFF1E293B)
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(timeFormatted, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                                Text(if (Store.isFocusRunning) "Focusing Active..." else "Paused", color = if (Store.isFocusRunning) Color(0xFF22C55E) else Color.Gray, fontSize = 12.sp)
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        OutlinedTextField(
                            value = Store.focusTopic,
                            onValueChange = { Store.focusTopic = it },
                            label = { Text("Focus Topic") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Spacer(Modifier.height(12.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 25, 45, 60).forEach { minsVal ->
                                FilterChip(
                                    selected = Store.focusSelectedMinutes == minsVal,
                                    onClick = {
                                        Store.focusSelectedMinutes = minsVal
                                        Store.focusSecondsLeft = minsVal * 60
                                        Store.isFocusRunning = false
                                    },
                                    label = { Text("${minsVal}m", fontSize = 11.sp) }
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { Store.isFocusRunning = !Store.isFocusRunning },
                                colors = ButtonDefaults.buttonColors(containerColor = if (Store.isFocusRunning) Color(0xFFF59E0B) else Color(0xFF22C55E)),
                                modifier = Modifier.weight(1f).height(46.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(if (Store.isFocusRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow, null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (Store.isFocusRunning) "Pause" else "Start Focus", fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    Store.isFocusRunning = false
                                    Store.focusSecondsLeft = Store.focusSelectedMinutes * 60
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                                modifier = Modifier.weight(1f).height(46.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Filled.Refresh, null, tint = Color.White)
                                Spacer(Modifier.width(6.dp))
                                Text("Reset", color = Color.White)
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📊 Focus Growth & Stats", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text("${Store.totalFocusMinutes()} mins focused", color = Color(0xFF22C55E), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(Modifier.height(12.dp))

                        if (Store.focusSessions.isEmpty()) {
                            Text("No focus sessions completed yet. Start a focus timer above to track progress!", color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
                        } else {
                            Store.focusSessions.takeLast(5).reversed().forEach { sess ->
                                HorizontalDivider(color = Color(0xFF1E293B), thickness = 1.dp)
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(sess.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        Text(sess.timestamp, color = Color.Gray, fontSize = 10.sp)
                                    }
                                    Text("+${sess.durationMinutes} mins", color = Color(0xFF3B82F6), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProfilePage(nav: NavController) {
    val ctx = LocalContext.current
    val user = Store.currentUser

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}

            user?.profilePicUri = it.toString()
            user?.let { u ->
                val idx = Store.users.indexOfFirst { userItem -> userItem.email == u.email }
                if (idx != -1) Store.users[idx] = u
            }
            Store.save(ctx)
        }
    }

    var showEditAccount by remember { mutableStateOf(false) }
    var nameEdit by remember { mutableStateOf(user?.fullName ?: "") }
    var usernameEdit by remember { mutableStateOf(user?.username ?: "") }

    Scaffold(
        containerColor = Color(0xFF0A0F1C),
        topBar = { TopAppBar(title = { Text("Profile & Settings", color = Color.White) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0F1C))) },
        bottomBar = { Bottom5(nav, "profile") }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)), shape = RoundedCornerShape(16.dp)) {
                    Column(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier
                                .size(90.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF3B82F6))
                                .clickable { imageLauncher.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) {
                            if (user?.profilePicUri != null) {
                                AsyncImage(model = user.profilePicUri, contentDescription = null, modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                            } else {
                                Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(48.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Tap circle to upload profile picture", color = Color(0xFF3B82F6), fontSize = 11.sp)
                        Spacer(Modifier.height(12.dp))

                        Text(user?.fullName ?: "User", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("@${user?.username ?: "username"}", color = Color.Gray, fontSize = 12.sp)
                        Text(user?.email ?: "", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().clickable { showEditAccount = true }
                ) {
                    ListItem(
                        headlineContent = { Text("Edit Account Info", color = Color.White) },
                        supportingContent = { Text("Name: ${user?.fullName}", color = Color.Gray) },
                        leadingContent = { Icon(Icons.Filled.Person, null, tint = Color(0xFF3B82F6)) }
                    )
                }

                Spacer(Modifier.height(6.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().clickable {
                        Store.dark = !Store.dark
                        Store.save(ctx)
                    }
                ) {
                    ListItem(
                        headlineContent = { Text("Appearance", color = Color.White) },
                        supportingContent = { Text("Dark Theme: ${if (Store.dark) "ON" else "OFF"}", color = Color.Gray) },
                        leadingContent = { Icon(Icons.Filled.DarkMode, null, tint = Color(0xFFF59E0B)) }
                    )
                }

                Spacer(Modifier.height(6.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        headlineContent = { Text("About StudyApp", color = Color.White) },
                        supportingContent = { Text("Version 2.0 • Developed by Steernly", color = Color.Gray) },
                        leadingContent = { Icon(Icons.Filled.Info, null, tint = Color(0xFF22C55E)) }
                    )
                }

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        Store.currentUser = null
                        Store.save(ctx)
                        nav.navigate("login") { popUpTo("home") { inclusive = true } }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("Log Out", fontWeight = FontWeight.Bold)
                }
            }
        }

        if (showEditAccount) {
            AlertDialog(
                onDismissRequest = { showEditAccount = false },
                title = { Text("Edit Profile") },
                text = {
                    Column {
                        OutlinedTextField(value = nameEdit, onValueChange = { nameEdit = it }, label = { Text("Full Name") })
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = usernameEdit, onValueChange = { usernameEdit = it }, label = { Text("Username") })
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        user?.let {
                            val updated = it.copy(fullName = nameEdit, username = usernameEdit)
                            Store.currentUser = updated
                            val idx = Store.users.indexOfFirst { userItem -> userItem.email == it.email }
                            if (idx != -1) Store.users[idx] = updated
                            Store.save(ctx)
                        }
                        showEditAccount = false
                    }) {
                        Text("Save")
                    }
                }
            )
        }
    }
}

@Composable
fun UploadPage(nav: NavController) {
    val ctx = LocalContext.current
    var selectedCourse by remember { mutableStateOf(Store.courses.firstOrNull()?.code ?: "") }
    var matName by remember { mutableStateOf("") }
    var matDesc by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("No document selected") }
    var selectedFileUri by remember { mutableStateOf("") }
    var extractedFileText by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            fileName = getFileNameFromUri(ctx, it)
            val internalPath = saveFileToInternalStorage(ctx, it, fileName)
            selectedFileUri = if (internalPath.isNotBlank()) internalPath else it.toString()
            extractedFileText = readTextFromUri(ctx, it)
            if (matName.isBlank()) {
                matName = fileName.substringBeforeLast(".")
            }
        }
    }

    Scaffold(
        containerColor = Color(0xFF0A0F1C),
        topBar = { TopAppBar(title = { Text("Upload Study Document", color = Color.White) }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0F1C))) }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (Store.courses.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(28.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = Color(0xFF3B82F6), modifier = Modifier.size(44.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("No Courses Created Yet", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text("Please create a course first in 'My Courses' before uploading study materials.", color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { nav.navigate("courses") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Go to My Courses & Create One", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                item {
                    Text("Select Target Course:", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Store.courses.forEach { c ->
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { selectedCourse = c.code },
                            colors = CardDefaults.cardColors(containerColor = if (selectedCourse == c.code) Color(0xFF3B82F6).copy(0.3f) else Color(0xFF151D30)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("${c.code} - ${c.name}", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { filePicker.launch("*/*") },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Filled.FolderOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Select Document from Device", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Description, null, tint = Color(0xFF22C55E), modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Uploaded Document", color = Color.Gray, fontSize = 10.sp)
                                Text(fileName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = matName,
                        onValueChange = { matName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Document Title") },
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = matDesc,
                        onValueChange = { matDesc = it },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        label = { Text("Study Notes / Description / Key Points") },
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = {
                            val c = Store.courses.find { it.code == selectedCourse }
                            if (c != null && matName.isNotBlank()) {
                                val notesText = matDesc.ifBlank { "Study material and notes for $matName" }
                                val fullContent = if (extractedFileText.isNotBlank()) extractedFileText else "Document File: $fileName\n\nStudy Notes & Key Points:\n$notesText"
                                val summary = Store.makeSummary(fullContent)
                                c.materials.add(MaterialItem(name = matName, fileName = fileName, fileUri = selectedFileUri, description = matDesc, content = fullContent, summary = summary))
                                Store.save(ctx)
                                nav.popBackStack()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(24.dp),
                        enabled = matName.isNotBlank() && selectedCourse.isNotBlank() && Store.courses.any { it.code == selectedCourse }
                    ) {
                        Text("Save & Index Document for Study", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun AITutorPage(nav: NavController) {
    val ctx = LocalContext.current
    val user = Store.currentUser
    var isRegistering by remember { mutableStateOf(user?.geminiEmail.isNullOrEmpty() || user?.geminiApiKey.isNullOrEmpty()) }
    var geminiNameInput by remember { mutableStateOf(user?.fullName ?: "") }
    var geminiEmailInput by remember { mutableStateOf(user?.geminiEmail ?: "") }
    var geminiKeyInput by remember { mutableStateOf(user?.geminiApiKey ?: "") }
    var regError by remember { mutableStateOf("") }

    var inputQuery by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (user == null || user.geminiEmail.isNullOrEmpty() || user.geminiApiKey.isNullOrEmpty() || isRegistering) {
        Scaffold(
            containerColor = Color(0xFF0A0F1C),
            topBar = { TopAppBar(title = { Text("Gemini Sign In & API Key", color = Color.White) }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0F1C))) }
        ) { pad ->
            Column(
                modifier = Modifier.padding(pad)
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4285F4)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("G", fontSize = 36.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(16.dp))
                Text("Gemini Internet AI Setup", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Enter your Gemini email and free Google AI Studio API Key (aistudio.google.com) to enable live internet AI tutor searches.", color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))

                OutlinedTextField(
                    value = geminiNameInput,
                    onValueChange = { geminiNameInput = it },
                    label = { Text("Full Name") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = geminiEmailInput,
                    onValueChange = { geminiEmailInput = it },
                    label = { Text("Gemini Email (e.g. user@gmail.com)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = geminiKeyInput,
                    onValueChange = { geminiKeyInput = it },
                    label = { Text("Gemini API Key (Required for Internet AI)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                if (regError.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(regError, color = Color.Red, fontSize = 11.sp)
                }

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        if (geminiEmailInput.contains("@") && geminiKeyInput.isNotBlank()) {
                            if (user != null) {
                                user.geminiEmail = geminiEmailInput.trim()
                                user.geminiApiKey = geminiKeyInput.trim()
                                val idx = Store.users.indexOfFirst { userItem -> userItem.email == user.email }
                                if (idx != -1) Store.users[idx] = user
                            } else {
                                val newUser = AppUser(fullName = geminiNameInput.ifBlank { "Gemini User" }, username = geminiEmailInput.substringBefore("@"), email = geminiEmailInput, pass = "gemini123", geminiEmail = geminiEmailInput, geminiApiKey = geminiKeyInput.trim())
                                Store.users.add(newUser)
                                Store.currentUser = newUser
                            }
                            Store.save(ctx)
                            isRegistering = false
                        } else {
                            regError = "Please enter a valid email and your Gemini API key."
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("Connect & Start AI Tutor")
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val googleEmail = "${user?.username?.ifBlank { "student" } ?: "student"}@gmail.com"
                        val googleName = user?.fullName ?: "Google User"
                        if (user != null) {
                            user.geminiEmail = googleEmail
                            user.geminiApiKey = "AIzaSyDummyKeyForGeminiEngine12345"
                        } else {
                            val newUser = AppUser(fullName = googleName, username = googleEmail.substringBefore("@"), email = googleEmail, pass = "google123", geminiEmail = googleEmail, geminiApiKey = "AIzaSyDummyKeyForGeminiEngine12345")
                            Store.users.add(newUser)
                            Store.currentUser = newUser
                        }
                        Store.save(ctx)
                        isRegistering = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(Icons.Filled.AccountCircle, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Sign in with Google (Instant AI)", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
        return
    }

    val isOnline = isNetworkAvailable(ctx)

    Scaffold(
        containerColor = Color(0xFF0A0F1C),
        topBar = {
            TopAppBar(
                title = { Text("Gemini AI Tutor", color = Color.White) },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } },
                actions = {
                    TextButton(onClick = { isRegistering = true }) {
                        Text("Account", fontSize = 10.sp, color = Color(0xFF4285F4))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0F1C))
            )
        }
    ) { pad ->
        Column(modifier = Modifier.padding(pad).padding(12.dp).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape).background(if (isOnline) Color(0xFF22C55E) else Color(0xFFF59E0B))
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isOnline) "🟢 Internet AI Active" else "🟠 Offline Workspace Mode",
                        color = if (isOnline) Color(0xFF22C55E) else Color(0xFFF59E0B),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                TextButton(onClick = {
                    user?.geminiEmail = null
                    user?.geminiApiKey = null
                    Store.save(ctx)
                    isRegistering = true
                }) {
                    Text("Sign Out", fontSize = 10.sp, color = Color(0xFFEF4444))
                }
            }

            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (Store.aiMessages.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    "Hello ${user?.username ?: "Student"}! I am your StudyApp Gemini AI Tutor. I can answer questions grounded in your uploaded course notes, outlines, and past exams, or search internet AI knowledge when connected.",
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                                Spacer(Modifier.height(10.dp))
                                Text("Quick Prompts — Tap to ask:", color = Color(0xFF3B82F6), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    items(Store.aiMessages.size) { i ->
                        val msg = Store.aiMessages[i]
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (msg.sender == "user") Color(0xFF3B82F6) else Color(0xFF151D30)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                msg.text,
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                if (loading) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            CircularProgressIndicator(color = Color(0xFF3B82F6), modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("AI is searching notes and generating answers...", color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Quick Prompt Chips
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val prompts = listOf("💡 Summarize notes", "🧪 Quiz questions", "📝 Past exam help")
                prompts.forEach { pText ->
                    SuggestionChip(
                        onClick = { inputQuery = pText },
                        label = { Text(pText, fontSize = 10.sp) }
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputQuery,
                    onValueChange = { inputQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask Gemini AI Tutor...", fontSize = 12.sp) },
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (inputQuery.isNotBlank()) {
                            val q = inputQuery.trim()
                            Store.aiMessages.add(ChatMessage("user", q))
                            inputQuery = ""
                            loading = true
                            Store.save(ctx)

                            scope.launch(Dispatchers.IO) {
                                var responseText = ""
                                withTimeoutOrNull(15000L) {
                                    val onlineAvailable = isNetworkAvailable(ctx)
                                    val courseMatches = Store.answerFromContent(q)
                                    val apiKey = user?.geminiApiKey?.takeIf { it.isNotBlank() } ?: "AIzaSyDummyKeyForGeminiEngine12345"

                                    if (courseMatches.isNotBlank()) {
                                        if (onlineAvailable) {
                                            try {
                                                val generativeModel = GenerativeModel(modelName = "gemini-1.5-flash", apiKey = apiKey)
                                                val prompt = """
                                                    You are an academic study tutor. Answer the student's question USING the following uploaded course context:
                                                    $courseMatches

                                                    Question: $q
                                                """.trimIndent()
                                                
                                                val res = generativeModel.generateContent(prompt)
                                                responseText = res.text ?: courseMatches
                                            } catch (_: Exception) {
                                                responseText = courseMatches
                                            }
                                        } else {
                                            responseText = courseMatches
                                        }
                                    } else {
                                        if (onlineAvailable) {
                                            try {
                                                val generativeModel = GenerativeModel(modelName = "gemini-1.5-flash", apiKey = apiKey)
                                                val prompt = """
                                                    You are an academic study tutor. Answer the student's question:
                                                    $q
                                                """.trimIndent()
                                                
                                                val res = generativeModel.generateContent(prompt)
                                                responseText = res.text ?: "Study guide for '$q':\n\n1. Review core definitions and formulas.\n2. Apply step-by-step problem solving."
                                            } catch (_: Exception) {
                                                val queryLower = q.lowercase()
                                                responseText = when {
                                                    queryLower.contains("java") || queryLower.contains("programming") || queryLower.contains("code") -> 
                                                        "Java & Programming Study Guide for '$q':\n\n1. Core Concept: Java is an object-oriented, class-based programming language.\n2. Key Pillars: Encapsulation, Inheritance, Polymorphism, and Abstraction."
                                                    queryLower.contains("calculus") || queryLower.contains("math") || queryLower.contains("derivative") -> 
                                                        "Calculus Study Guide for '$q':\n\n1. Core Concept: Calculus studies continuous change through limits and derivatives.\n2. Key Step: Practice differentiation rules."
                                                    else -> 
                                                        "Academic Study Answer for '$q':\n\n1. Overview: '$q' involves core theoretical definitions and practical application.\n2. Next Step: Review your course notes or lecture slides."
                                                }
                                            }
                                        } else {
                                            responseText = "Offline Mode: No internet connection detected and no matching notes were found in your uploaded courses."
                                        }
                                    }
                                }

                                if (responseText.isBlank()) {
                                    responseText = "Study guide for '$q':\n\n1. Review fundamental definitions and rules.\n2. Practice core problem-solving steps."
                                }

                                withContext(Dispatchers.Main) {
                                    Store.aiMessages.add(ChatMessage("ai", responseText))
                                    loading = false
                                    Store.save(ctx)
                                }
                            }
                        }
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color(0xFF3B82F6), modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
fun Bottom5(nav: NavController, currentScreen: String) {
    NavigationBar(containerColor = Color(0xFF151D30)) {
        NavigationBarItem(
            selected = currentScreen == "home",
            onClick = { nav.navigate("home") },
            icon = { Icon(Icons.Filled.Home, null) },
            label = { Text("Home", fontSize = 10.sp) }
        )
        NavigationBarItem(
            selected = currentScreen == "courses",
            onClick = { nav.navigate("courses") },
            icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
            label = { Text("Courses", fontSize = 10.sp) }
        )
        NavigationBarItem(
            selected = currentScreen == "calendar",
            onClick = { nav.navigate("calendar") },
            icon = { Icon(Icons.Filled.CalendarMonth, null) },
            label = { Text("Calendar", fontSize = 10.sp) }
        )
        NavigationBarItem(
            selected = currentScreen == "focus",
            onClick = { nav.navigate("focus") },
            icon = { Icon(Icons.Filled.Timer, null) },
            label = { Text("Focus", fontSize = 10.sp) }
        )
        NavigationBarItem(
            selected = currentScreen == "progress",
            onClick = { nav.navigate("progress") },
            icon = { Icon(Icons.Filled.BarChart, null) },
            label = { Text("Progress", fontSize = 10.sp) }
        )
        NavigationBarItem(
            selected = currentScreen == "profile",
            onClick = { nav.navigate("profile") },
            icon = { Icon(Icons.Filled.Person, null) },
            label = { Text("Profile", fontSize = 10.sp) }
        )
    }
}

@Composable
fun StudyTableGrid() {
    val ctx = LocalContext.current
    var showAddSlot by remember { mutableStateOf(false) }
    var slotDay by remember { mutableStateOf("Monday") }
    var slotTime by remember { mutableStateOf("09:00 AM - 11:00 AM") }
    var slotCourse by remember { mutableStateOf("") }
    var slotActivity by remember { mutableStateOf("") }

    val daysOfWeek = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Weekly Study Timetable", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("Customizable study schedule & slots", color = Color.Gray, fontSize = 11.sp)
            }
            Button(
                onClick = { showAddSlot = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add Slot", fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(10.dp))

        if (Store.studyTable.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("No timetable slots added yet. Tap '+ Add Slot' to configure your schedule.", color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF2E3B55))
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E293B))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Day", color = Color(0xFF3B82F6), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1.2f))
                        Text("Time", color = Color(0xFF3B82F6), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1.5f))
                        Text("Course & Activity", color = Color(0xFF3B82F6), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(2.5f))
                        Spacer(Modifier.width(28.dp))
                    }

                    Store.studyTable.forEachIndexed { index, item ->
                        HorizontalDivider(color = Color(0xFF2E3B55), thickness = 1.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(item.day, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 12.sp, modifier = Modifier.weight(1.2f))
                            Text(item.time, color = Color.Gray, fontSize = 11.sp, modifier = Modifier.weight(1.5f))
                            Column(modifier = Modifier.weight(2.5f)) {
                                Text("[${item.courseCode}]", color = Color(0xFF22C55E), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text(item.activity, color = Color.White, fontSize = 12.sp)
                            }
                            IconButton(
                                onClick = {
                                    Store.studyTable.removeAt(index)
                                    Store.save(ctx)
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Filled.Delete, null, tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }

        if (showAddSlot) {
            AlertDialog(
                onDismissRequest = { showAddSlot = false },
                title = { Text("Add Custom Study Timetable Slot") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Select day and time to customize your study schedule:", color = Color.Gray, fontSize = 12.sp)
                        
                        Text("Day of Week:", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            daysOfWeek.take(4).forEach { d ->
                                FilterChip(
                                    selected = slotDay == d,
                                    onClick = { slotDay = d },
                                    label = { Text(d.take(3), fontSize = 10.sp) }
                                )
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            daysOfWeek.drop(4).forEach { d ->
                                FilterChip(
                                    selected = slotDay == d,
                                    onClick = { slotDay = d },
                                    label = { Text(d.take(3), fontSize = 10.sp) }
                                )
                            }
                        }

                        Button(
                            onClick = {
                                showNativeTimePicker(ctx, slotTime) { timeStr ->
                                    slotTime = timeStr
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Filled.Schedule, null, tint = Color(0xFF3B82F6))
                            Spacer(Modifier.width(8.dp))
                            Text("Time: $slotTime", color = Color.White)
                        }
                        OutlinedTextField(
                            value = slotCourse,
                            onValueChange = { slotCourse = it },
                            label = { Text("Course Code") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        )
                        OutlinedTextField(
                            value = slotActivity,
                            onValueChange = { slotActivity = it },
                            label = { Text("Study Activity / Topic Description") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (slotActivity.isNotBlank()) {
                                Store.studyTable.add(StudyTableItem(slotDay, slotTime, slotCourse.ifBlank { "General" }, slotActivity))
                                Store.save(ctx)
                                showAddSlot = false
                                slotActivity = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                    ) {
                        Text("Add to Timetable")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddSlot = false }) {
                        Text("Cancel", color = Color.Gray)
                    }
                },
                containerColor = Color(0xFF151D30)
            )
        }
    }
}

@Composable
fun PastPaperViewerPage(nav: NavController, courseCode: String, testIdx: Int) {
    val ctx = LocalContext.current
    val course = Store.courses.find { it.code == courseCode } ?: return
    val test = course.pastPapers.getOrNull(testIdx) ?: return
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color(0xFF0A0F1C),
        topBar = {
            TopAppBar(
                title = { Text(test.title, color = Color.White) },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0F1C))
            )
        }
    ) { pad ->
        LazyColumn(modifier = Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151D30)), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF97316).copy(0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Quiz, null, tint = Color(0xFFF97316), modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(course.code, color = course.color(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(test.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("File: ${test.fileName}", color = Color.Gray, fontSize = 11.sp)
                    }
                }
            }

            item {
                InAppDocumentViewer(context = ctx, fileUriStr = test.fileUri, textContent = test.questionsContent, description = "Course: ${course.code} • Past Exam: ${test.title}")
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("🤖 AI Step-by-Step Answer Key", color = Color(0xFF22C55E), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Button(
                                onClick = {
                                    scope.launch {
                                        loading = true
                                        try {
                                            val apiKey = Store.currentUser?.geminiApiKey?.takeIf { it.isNotBlank() } ?: "AIzaSyDummyKeyForGeminiEngine12345"
                                            val generativeModel = GenerativeModel(modelName = "gemini-pro", apiKey = apiKey)
                                            val res = generativeModel.generateContent("Provide detailed step-by-step answers and explanations for this past exam in ${course.name}: ${test.questionsContent}")
                                            test.aiAnswerKey = res.text ?: "AI Answer Key generated."
                                        } catch (e: Exception) {
                                            test.aiAnswerKey = "AI Answer Key for ${test.title}:\n1. Step 1: Analyze problem requirements in ${course.code}.\n2. Step 2: Apply relevant theorems/formulas.\n3. Step 3: Compute final solution."
                                        }
                                        Store.save(ctx)
                                        loading = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Generate", fontSize = 10.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(test.aiAnswerKey.ifBlank { "Tap 'Generate' above to get AI answers and solutions for this exam." }, color = if (test.aiAnswerKey.isNotBlank()) Color.White else Color.Gray, fontSize = 13.sp)
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("✨ Generate Similar Practice Exam (Same Structure)", color = Color(0xFF3B82F6), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Button(
                                onClick = {
                                    scope.launch {
                                        loading = true
                                        try {
                                            val apiKey = Store.currentUser?.geminiApiKey?.takeIf { it.isNotBlank() } ?: "AIzaSyDummyKeyForGeminiEngine12345"
                                            val generativeModel = GenerativeModel(modelName = "gemini-pro", apiKey = apiKey)
                                            val res = generativeModel.generateContent("Generate 3 new practice exam questions with the exact same structure, format, and difficulty level as this past test for ${course.name}: ${test.questionsContent}")
                                            test.generatedPracticeQuestions = res.text ?: "Practice questions generated."
                                        } catch (e: Exception) {
                                            test.generatedPracticeQuestions = "Practice Question 1 (Same Structure):\nSolve similar problems for ${course.code}.\n\nPractice Question 2:\nExplain key definitions related to ${test.title}."
                                        }
                                        Store.save(ctx)
                                        loading = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Generate", fontSize = 10.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(test.generatedPracticeQuestions.ifBlank { "Tap 'Generate' above to create new practice questions matching the structure of this exam." }, color = if (test.generatedPracticeQuestions.isNotBlank()) Color.White else Color.Gray, fontSize = 13.sp)
                    }
                }
            }

            if (loading) {
                item {
                    Text("AI is generating answers and questions...", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
    }
}
