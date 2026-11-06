package com.gatefocus.app.data

import android.content.Context
import android.content.SharedPreferences
import com.gatefocus.app.models.GateQuestion
import com.gatefocus.app.models.MockQuestionState
import com.gatefocus.app.models.MockTestResult
import com.gatefocus.app.models.QuestionType
import com.gatefocus.app.models.QuestionVerificationResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.roundToInt

class QuestionRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("gate_focus_question_prefs", Context.MODE_PRIVATE)

    private val importedFile = File(context.filesDir, "imported_questions.json")
    private var cachedQuestions: List<GateQuestion>? = null

    fun getQuestions(): List<GateQuestion> {
        cachedQuestions?.let { return it }

        val list = mutableListOf<GateQuestion>()

        // 1. Load from bundled asset
        try {
            val stream = context.assets.open("gate_cse_questions.json")
            val reader = BufferedReader(InputStreamReader(stream))
            val jsonStr = reader.use { it.readText() }
            parseQuestions(jsonStr, list)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Load from local imported storage
        try {
            if (importedFile.exists()) {
                val jsonStr = importedFile.readText()
                parseQuestions(jsonStr, list)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        cachedQuestions = list
        return list
    }

    private fun parseQuestions(jsonStr: String, outList: MutableList<GateQuestion>) {
        val jsonArray = JSONArray(jsonStr)
        for (i in 0 until jsonArray.length()) {
            val qObj = jsonArray.getJSONObject(i)
            val id = qObj.optString("id", "q_${System.currentTimeMillis()}_$i")
            val subject = qObj.optString("subject", "General")
            val topic = qObj.optString("topic", "General Topic")
            val year = qObj.optString("year", "GATE Practice")
            val marks = qObj.optInt("marks", 1)
            val typeStr = qObj.optString("type", "MCQ")
            val type = when (typeStr.uppercase()) {
                "MSQ" -> QuestionType.MSQ
                "NAT" -> QuestionType.NAT
                else -> QuestionType.MCQ
            }
            val questionText = qObj.getString("questionText")

            val optionsList = mutableListOf<String>()
            val optArr = qObj.optJSONArray("options")
            if (optArr != null) {
                for (j in 0 until optArr.length()) {
                    optionsList.add(optArr.getString(j))
                }
            }

            val correctAnswers = mutableListOf<Int>()
            val ansArr = qObj.optJSONArray("correctAnswers")
            if (ansArr != null) {
                for (j in 0 until ansArr.length()) {
                    correctAnswers.add(ansArr.getInt(j))
                }
            }

            val natMin = if (qObj.has("natMin") && !qObj.isNull("natMin")) qObj.getDouble("natMin") else null
            val natMax = if (qObj.has("natMax") && !qObj.isNull("natMax")) qObj.getDouble("natMax") else null
            val explanation = qObj.optString("explanation", "No detailed explanation provided.")

            val tags = mutableListOf<String>()
            val tagsArr = qObj.optJSONArray("tags")
            if (tagsArr != null) {
                for (j in 0 until tagsArr.length()) {
                    tags.add(tagsArr.getString(j))
                }
            }

            outList.add(
                GateQuestion(
                    id = id,
                    subject = subject,
                    topic = topic,
                    year = year,
                    marks = marks,
                    type = type,
                    questionText = questionText,
                    options = optionsList,
                    correctAnswers = correctAnswers,
                    natMin = natMin,
                    natMax = natMax,
                    explanation = explanation,
                    tags = tags
                )
            )
        }
    }

    fun getDistinctSubjects(): List<String> {
        return getQuestions().map { it.subject }.distinct().sorted()
    }

    fun getTopicsForSubject(subject: String): List<String> {
        return getQuestions()
            .filter { it.subject.equals(subject, ignoreCase = true) }
            .map { it.topic }
            .distinct()
            .sorted()
    }

    fun getDistinctYears(): List<String> {
        return getQuestions().map { it.year }.distinct().sortedDescending()
    }

    fun getQuestionsByTopic(subject: String, topic: String): List<GateQuestion> {
        return getQuestions().filter {
            it.subject.equals(subject, ignoreCase = true) && it.topic.equals(topic, ignoreCase = true)
        }
    }

    fun getQuestionsByYear(year: String): List<GateQuestion> {
        return getQuestions().filter { it.year.equals(year, ignoreCase = true) }
    }

    fun getShuffledMix(count: Int, subjectFilter: String? = null): List<GateQuestion> {
        val pool = if (subjectFilter.isNullOrEmpty()) getQuestions() else getQuestions().filter { it.subject.equals(subjectFilter, ignoreCase = true) }
        return pool.shuffled().take(count)
    }

    fun isBookmarked(questionId: String): Boolean {
        val bookmarks = prefs.getStringSet("bookmarked_questions", emptySet()) ?: emptySet()
        return bookmarks.contains(questionId)
    }

    fun toggleBookmark(questionId: String): Boolean {
        val bookmarks = (prefs.getStringSet("bookmarked_questions", emptySet()) ?: emptySet()).toMutableSet()
        val newState = if (bookmarks.contains(questionId)) {
            bookmarks.remove(questionId)
            false
        } else {
            bookmarks.add(questionId)
            true
        }
        prefs.edit().putStringSet("bookmarked_questions", bookmarks).apply()
        return newState
    }

    fun getBookmarkedQuestions(): List<GateQuestion> {
        val set = prefs.getStringSet("bookmarked_questions", emptySet()) ?: emptySet()
        return getQuestions().filter { it.id in set }
    }

    fun verifyAnswer(
        question: GateQuestion,
        selectedIndices: Set<Int>,
        natValueText: String?
    ): QuestionVerificationResult {
        when (question.type) {
            QuestionType.MCQ -> {
                val correctIndex = question.correctAnswers.firstOrNull() ?: -1
                val userIndex = selectedIndices.firstOrNull() ?: -1
                val isCorrect = userIndex == correctIndex && userIndex != -1

                val optionLabels = listOf("A", "B", "C", "D", "E", "F")
                val userLabel = if (userIndex in question.options.indices) "Option ${optionLabels.getOrElse(userIndex) { "$userIndex" }}" else "None"
                val correctLabel = if (correctIndex in question.options.indices) "Option ${optionLabels.getOrElse(correctIndex) { "$correctIndex" }}" else "N/A"

                return QuestionVerificationResult(
                    isCorrect = isCorrect,
                    userSummary = "Your Choice: $userLabel",
                    correctSummary = "Correct Answer: $correctLabel",
                    explanation = question.explanation
                )
            }
            QuestionType.MSQ -> {
                val correctSet = question.correctAnswers.toSet()
                val isCorrect = selectedIndices.isNotEmpty() && selectedIndices == correctSet

                val optionLabels = listOf("A", "B", "C", "D", "E", "F")
                val userStr = if (selectedIndices.isEmpty()) "None" else selectedIndices.sorted().joinToString(", ") { optionLabels.getOrElse(it) { "$it" } }
                val correctStr = correctSet.sorted().joinToString(", ") { optionLabels.getOrElse(it) { "$it" } }

                return QuestionVerificationResult(
                    isCorrect = isCorrect,
                    userSummary = "Your Choices: $userStr",
                    correctSummary = "Correct Answers: $correctStr",
                    explanation = question.explanation
                )
            }
            QuestionType.NAT -> {
                val entered = natValueText?.toDoubleOrNull()
                val min = question.natMin ?: 0.0
                val max = question.natMax ?: 0.0
                val isCorrect = entered != null && (entered in min..max || abs(entered - min) < 0.001)

                val correctDisplay = if (min == max) "$min" else "$min to $max"
                val userDisplay = if (entered != null) "$entered" else "Invalid / Empty"

                return QuestionVerificationResult(
                    isCorrect = isCorrect,
                    userSummary = "Your Answer: $userDisplay",
                    correctSummary = "Accepted Range: $correctDisplay",
                    explanation = question.explanation
                )
            }
        }
    }

    /**
     * Evaluates a full Mock Test according to official GATE negative marking rules:
     * - 1-mark MCQ: +1.0 for correct, -0.33 for incorrect
     * - 2-mark MCQ: +2.0 for correct, -0.66 for incorrect
     * - MSQ & NAT: +1.0 or +2.0 for correct, 0 negative for incorrect
     * - Unattempted: 0.0
     */
    fun evaluateMockTest(states: List<MockQuestionState>, timeTakenSeconds: Int): MockTestResult {
        var totalScore = 0.0
        var maxScore = 0.0
        var correctCount = 0
        var incorrectCount = 0
        var unattemptedCount = 0

        for (state in states) {
            val q = state.question
            maxScore += q.marks

            if (!state.isAnswered) {
                unattemptedCount++
                continue
            }

            val verification = verifyAnswer(q, state.selectedOptions, state.natAnswer)
            if (verification.isCorrect) {
                correctCount++
                totalScore += q.marks
            } else {
                incorrectCount++
                if (q.type == QuestionType.MCQ) {
                    val penalty = if (q.marks == 1) (1.0 / 3.0) else (2.0 / 3.0)
                    totalScore -= penalty
                }
            }
        }

        // Round score to 2 decimal places
        val roundedScore = ((totalScore * 100).roundToInt()) / 100.0

        return MockTestResult(
            totalScore = roundedScore,
            maxScore = maxScore,
            correctCount = correctCount,
            incorrectCount = incorrectCount,
            unattemptedCount = unattemptedCount,
            timeTakenSeconds = timeTakenSeconds,
            questionStates = states
        )
    }

    /**
     * Imports questions from a JSON string, saves to internal storage, and invalidates cache.
     */
    fun importQuestionsFromJson(jsonString: String): Pair<Boolean, String> {
        return try {
            val trimmed = jsonString.trim()
            val newArray = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                if (obj.has("questions")) obj.getJSONArray("questions")
                else JSONArray().put(obj)
            } else {
                return Pair(false, "Invalid JSON format: Must start with '[' or '{'")
            }

            if (newArray.length() == 0) {
                return Pair(false, "No questions found in the imported JSON.")
            }

            // Existing imported array
            val existingArray = if (importedFile.exists()) {
                try { JSONArray(importedFile.readText()) } catch (_: Exception) { JSONArray() }
            } else {
                JSONArray()
            }

            // Merge avoiding duplicates by id
            val existingIds = mutableSetOf<String>()
            for (i in 0 until existingArray.length()) {
                val item = existingArray.optJSONObject(i)
                if (item != null && item.has("id")) existingIds.add(item.getString("id"))
            }

            var newlyAdded = 0
            for (i in 0 until newArray.length()) {
                val item = newArray.getJSONObject(i)
                val id = if (item.has("id")) item.getString("id") else "imported_${System.currentTimeMillis()}_$i"
                item.put("id", id)

                if (id !in existingIds) {
                    existingArray.put(item)
                    existingIds.add(id)
                    newlyAdded++
                }
            }

            importedFile.writeText(existingArray.toString(2))
            cachedQuestions = null // Invalidate cache

            Pair(true, "Successfully imported $newlyAdded question${if (newlyAdded == 1) "" else "s"}!")
        } catch (e: Exception) {
            Pair(false, "Import error: ${e.message}")
        }
    }
}
