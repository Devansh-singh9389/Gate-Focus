# GATE FOCUS — Complete GATE CSE Preparation & Focus Suite

Native Kotlin Android application built for **GATE Computer Science & Engineering (CSE)** aspirants. Combines distraction-free focus enforcement with an interactive **Syllabus Progress Tracker** (inspired by [`PritishMishraa/gatecsetracker`](https://github.com/PritishMishraa/gatecsetracker)) and a multi-portal **GATE CSE Practice & Mock Test Engine**.

---

## Key Features

### 1. GATE CSE Syllabus & Milestone Tracker
- **All 13 Core Subjects**:
  1. Operating Systems (OS)
  2. Database Management Systems (DBMS)
  3. Computer Networks (CN)
  4. Theory of Computation (TOC)
  5. Compiler Design (CD)
  6. Data Structures & C Programming (DSA)
  7. Algorithms (ALGO)
  8. Computer Organization & Architecture (COA)
  9. Digital Logic (DL)
  10. Discrete Mathematics (DM)
  11. Engineering Mathematics (EM)
  12. General Aptitude (GA)
- **Granular Progress Checklists**: Each subject breaks into chapters and topics with 3 persistent checkboxes:
  - `[✓] Theory / Lecture Watched`
  - `[✓] Revision / Notes Completed`
  - `[✓] PYQs Practiced`
- **Dynamic Progress Analytics**: Real-time progress percentage bar for each subject and overall syllabus meter.
- **One-Tap Lecture Access**: Direct shortcut from each syllabus topic to play curated lectures without opening the algorithmic YouTube home feed.

### 2. Practice Tab: 3-Portal Architecture

#### Portal 1: GATE PYQs (Previous Year Questions)
- **Topic-wise Practice**: Select Subject (e.g. Operating Systems) $\rightarrow$ select Topic (e.g. CPU Scheduling) $\rightarrow$ practice targeted questions with immediate solutions.
- **Year-wise Practice**: Select Exam Year (e.g. GATE 2024, GATE 2023, GATE 2022...) $\rightarrow$ solve the official paper.
- **Combined Mix**: Customizable randomized questions across subjects for comprehensive revision.
- **Question Formats**: Full support for **MCQ** (Single choice), **MSQ** (Multiple select), and **NAT** (Numerical answer type).
- **Instant Detailed Solutions**: Mathematical derivations, formulas, and step-by-step explanations.

#### Portal 2: Mock Tests (Simulated Exam Environment)
- **Real Exam Conditions**:
  - **Live Countdown Timer** with alert colors under 1 minute.
  - **Question Palette**: Live status indicators (Default / Unattempted, Answered in green, Marked for Review in amber).
  - **Official GATE Marking Scheme**:
    - 1-mark MCQ: $+1.0$ for correct, $-0.33$ for incorrect
    - 2-mark MCQ: $+2.0$ for correct, $-0.66$ for incorrect
    - MSQ & NAT: $+1.0$ or $+2.0$ for correct, $0.0$ penalty for incorrect
- **Sub-Modes**:
  - **Topic Mock**: 10-15 questions focused on a specific chapter (15 min).
  - **Year Mock**: Complete examination paper simulation.
  - **Mixed Mock**: Shuffled across all 13 subjects.
- **Scorecard & Analytics Report**:
  - Total Score earned out of Max Marks.
  - Accuracy percentage, Correct, Incorrect, and Skipped breakdown.
  - Time taken.
  - **"Review Answers & Step-by-Step Solutions"** button that walks through all questions with explanations.

#### Portal 3: Import the PYQs (Extensible Question Bank)
- **Select Import Source**:
  - 📁 **Local JSON File**: Pick a `.json` file from your device storage.
  - 📝 **Paste Questions JSON**: Directly paste or edit question JSON.
  - 🌐 **Web URL / GitHub Source**: Download and import remote raw JSON question repositories.
  - 📋 **JSON Schema Guide**: View the sample format to prepare new question sets.
- Dynamically merges imported questions with existing bundled questions into local storage.

### 3. Focus Timer & Study Session Integration
- Focus timer presets (**25m / 45m / 60m**) and precision stopwatch.
- **Post-Session Quick Quiz Prompt**: Automatically prompts the student to reinforce focus with a quick question practice drill right after completing a study session.

### 4. Distraction Guard & Telegram Filter
- **App Blocker**: Accessibility-based blocking for selected distracting apps (Instagram, Threads, Facebook, Snapchat).
- **YouTube Shorts Guard**: Intercepts YouTube Shorts feeds to prevent infinite scrolling.
- **Telegram Notification Listener**: Silences non-study Telegram notifications and keeps only allowlisted GATE study channels.
- **Telegram Screen Guard**: Best-effort screen monitor ensuring students stay within allowed study groups.

### 5. Curated In-App Video Library
- Distraction-free embedded YouTube player by video ID.
- Blocks home feed, recommendations, and algorithmic traps.

---

## Project Structure

```
GateFocus/
├── app/
│   ├── src/main/
│   │   ├── assets/
│   │   │   ├── gate_cse_syllabus.json       # 13 GATE CSE subjects, chapters & topics
│   │   │   └── gate_cse_questions.json      # Curated GATE CSE PYQs, answers & solutions
│   │   ├── java/com/gatefocus/app/
│   │   │   ├── data/
│   │   │   │   ├── SyllabusRepository.kt    # Loads syllabus & handles persistent progress
│   │   │   │   └── QuestionRepository.kt    # Loads & imports questions, evaluates mock tests
│   │   │   ├── models/
│   │   │   │   ├── GateSyllabusModels.kt    # Subject, Topic, TopicProgress
│   │   │   │   └── GateQuestionModels.kt    # GateQuestion, MockTestConfig, MockTestResult
│   │   │   ├── MainActivity.kt              # 5-Tab controller & Practice Hub coordinator
│   │   │   ├── PlayerActivity.kt            # Embedded distraction-free lecture player
│   │   │   ├── BlockScreenActivity.kt       # Study mode blocking overlay screen
│   │   │   ├── AppBlockerService.kt         # Accessibility service for app & shorts blocking
│   │   │   └── TelegramNotificationFilterService.kt # Notification listener service
│   │   └── res/
│   │       ├── layout/
│   │       │   ├── tab_practice.xml         # Practice Hub + Session + Mock Test layout
│   │       │   ├── dialog_mock_result.xml   # Scorecard report dialog
│   │       │   ├── dialog_import_source.xml # Source selector dialog for importing PYQs
│   │       │   ├── item_mock_palette.xml    # Palette bubble layout
│   │       │   ├── tab_tracker.xml          # Syllabus tracker screen
│   │       │   ├── tab_focus.xml            # Timer & stopwatch UI
│   │       │   ├── tab_library.xml          # Curated video lectures
│   │       │   └── tab_guard.xml            # Distraction controls & Telegram filter
│   │       └── menu/
│   │           └── bottom_nav_menu.xml      # 5 bottom navigation tabs
```

---

## Build & Run

### Prerequisites
- Android Studio Ladybug / Meerkat (or newer)
- Android SDK Platform 35 (compileSdk 35, minSdk 26)
- JDK 17+

### Build APK via Command Line
```powershell
.\gradlew.bat assembleDebug
```
The resulting debug APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`
