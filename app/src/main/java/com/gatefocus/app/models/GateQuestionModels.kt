package com.gatefocus.app.models

enum class QuestionType {
    MCQ, // Multiple Choice Question (Single option correct)
    MSQ, // Multiple Select Question (One or more options correct)
    NAT  // Numerical Answer Type (Decimal or Integer entry)
}

data class GateQuestion(
    val id: String,
    val subject: String,
    val topic: String,
    val year: String,
    val marks: Int,
    val type: QuestionType,
    val questionText: String,
    val options: List<String> = emptyList(),
    val correctAnswers: List<Int> = emptyList(), // 0-based indices for MCQ/MSQ
    val natMin: Double? = null,
    val natMax: Double? = null,
    val explanation: String,
    val tags: List<String> = emptyList()
)

data class QuestionVerificationResult(
    val isCorrect: Boolean,
    val userSummary: String,
    val correctSummary: String,
    val explanation: String
)

data class MockTestConfig(
    val title: String,
    val durationMinutes: Int,
    val questions: List<GateQuestion>
)

data class MockQuestionState(
    val question: GateQuestion,
    var selectedOptions: MutableSet<Int> = mutableSetOf(),
    var natAnswer: String? = null,
    var isMarkedForReview: Boolean = false
) {
    val isAnswered: Boolean
        get() = when (question.type) {
            QuestionType.MCQ, QuestionType.MSQ -> selectedOptions.isNotEmpty()
            QuestionType.NAT -> !natAnswer.isNullOrBlank()
        }
}

data class MockTestResult(
    val totalScore: Double,
    val maxScore: Double,
    val correctCount: Int,
    val incorrectCount: Int,
    val unattemptedCount: Int,
    val timeTakenSeconds: Int,
    val questionStates: List<MockQuestionState>
)
