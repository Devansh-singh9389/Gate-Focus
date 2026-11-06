package com.gatefocus.app.models

data class GateSubject(
    val id: String,
    val name: String,
    val shortCode: String,
    val description: String,
    val topics: List<GateTopic>
)

data class GateTopic(
    val id: String,
    val subjectId: String,
    val title: String,
    val subtopics: List<String> = emptyList(),
    val recommendedVideoId: String = ""
)

data class TopicProgress(
    val topicId: String,
    var isCompleted: Boolean = false,
    var notesText: String = ""
)
