package com.gatefocus.app.data

import android.content.Context
import android.content.SharedPreferences
import com.gatefocus.app.models.GateSubject
import com.gatefocus.app.models.GateTopic
import com.gatefocus.app.models.TopicProgress
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader

class SyllabusRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("gate_focus_syllabus_prefs", Context.MODE_PRIVATE)

    private var cachedSubjects: List<GateSubject>? = null

    fun getSubjects(): List<GateSubject> {
        cachedSubjects?.let { return it }

        val list = mutableListOf<GateSubject>()
        try {
            val stream = context.assets.open("gate_cse_syllabus.json")
            val reader = BufferedReader(InputStreamReader(stream))
            val jsonStr = reader.use { it.readText() }
            val jsonArray = JSONArray(jsonStr)

            for (i in 0 until jsonArray.length()) {
                val subObj = jsonArray.getJSONObject(i)
                val subjectId = subObj.getString("id")
                val name = subObj.getString("name")
                val shortCode = subObj.optString("shortCode", "")
                val description = subObj.optString("description", "")

                val topicsArray = subObj.getJSONArray("topics")
                val topics = mutableListOf<GateTopic>()
                for (j in 0 until topicsArray.length()) {
                    val tObj = topicsArray.getJSONObject(j)
                    val subtopicsJson = tObj.optJSONArray("subtopics")
                    val subtopicsList = mutableListOf<String>()
                    if (subtopicsJson != null) {
                        for (k in 0 until subtopicsJson.length()) {
                            subtopicsList.add(subtopicsJson.getString(k))
                        }
                    }
                    topics.add(
                        GateTopic(
                            id = tObj.getString("id"),
                            subjectId = subjectId,
                            title = tObj.getString("title"),
                            subtopics = subtopicsList,
                            recommendedVideoId = tObj.optString("recommendedVideoId", "")
                        )
                    )
                }

                list.add(
                    GateSubject(
                        id = subjectId,
                        name = name,
                        shortCode = shortCode,
                        description = description,
                        topics = topics
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        cachedSubjects = list
        return list
    }

    fun getTopicProgress(topicId: String): TopicProgress {
        val isCompleted = if (prefs.contains("${topicId}_completed")) {
            prefs.getBoolean("${topicId}_completed", false)
        } else {
            prefs.getBoolean("${topicId}_theory", false) ||
            prefs.getBoolean("${topicId}_notes", false) ||
            prefs.getBoolean("${topicId}_pyq", false)
        }
        val notesText = prefs.getString("${topicId}_notes_text", "").orEmpty()
        return TopicProgress(topicId = topicId, isCompleted = isCompleted, notesText = notesText)
    }

    fun setTopicProgress(topicId: String, isCompleted: Boolean) {
        prefs.edit()
            .putBoolean("${topicId}_completed", isCompleted)
            .apply()
    }

    fun getTopicNotes(topicId: String): String {
        return prefs.getString("${topicId}_notes_text", "").orEmpty()
    }

    fun setTopicNotes(topicId: String, notesText: String) {
        prefs.edit()
            .putString("${topicId}_notes_text", notesText)
            .apply()
    }

    fun getSubjectCompletedCount(subject: GateSubject): Int {
        var count = 0
        for (topic in subject.topics) {
            if (getTopicProgress(topic.id).isCompleted) count++
        }
        return count
    }

    fun getSubjectCompletionPercentage(subject: GateSubject): Int {
        if (subject.topics.isEmpty()) return 0
        val completed = getSubjectCompletedCount(subject)
        return (completed * 100) / subject.topics.size
    }

    fun getOverallProgress(): Pair<Int, Int> {
        val subjects = getSubjects()
        var totalTopics = 0
        var completedTopics = 0
        for (sub in subjects) {
            totalTopics += sub.topics.size
            for (topic in sub.topics) {
                if (getTopicProgress(topic.id).isCompleted) {
                    completedTopics++
                }
            }
        }
        return Pair(completedTopics, totalTopics)
    }
}
