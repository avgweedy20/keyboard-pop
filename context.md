# Project Context: Auto Focus for Telegram

This document provides complete onboarding context for the `keyboard-pop` repository (`com.autofocus.telegram`). It covers the application's product behavior, architecture, file organization, data models, system integrations, build setup, development conventions, and known gaps.

---

## 1. Product Overview

### What the App Is
**Auto Focus for Telegram** (package name `com.autofocus.telegram`) is an independent Android background utility application built with Kotlin and AndroidX/Material3. Its core purpose is to eliminate extra manual taps required when opening a chat room in Telegram by automatically finding the message input box, giving it focus, showing the soft keyboard, and dispatching a synthetic tap gesture fallback.

### Who It's For & Core Problem Solved
- **Target Audience:** Android users of standard Telegram (`org.telegram.messenger`) and Telegram X (`org.telegram.messenger.web`).
- **Core Problem:** On Android devices, opening a chat in Telegram often requires an extra tap on the text input box before the soft keyboard appears. Auto Focus for Telegram automates this interaction in real time (~35ms event loop) via Android's `AccessibilityService` APIs.

### Full Feature List
1. **Accessibility Service Status Monitoring:**
   - Detects whether `TelegramFocusAccessibilityService` is enabled in Android System Settings using `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`.
   - Dynamic switch toggle (`switch_service` in `app/src/main/res/layout/activity_main.xml`) reflects enabled/disabled state.
2. **Prominent Disclosure & Permission Flow:**
   - Triggered when enabling the service switch (`showDisclosureDialog()` in `app/src/main/java/com/autofocus/telegram/MainActivity.kt`).
   - Explores accessibility requirements and directs the user to system settings via `Settings.ACTION_ACCESSIBILITY_SETTINGS`.
3. **Telegram Installation Verification:**
   - Queries package manager (`checkTelegramInstalled()` in `MainActivity.kt`) to verify if `org.telegram.messenger` or `org.telegram.messenger.web` is installed on the device.
   - Displays real-time status card (`card_telegram_status`).
4. **Performance Benchmark Telemetry:**
   - Records and displays the last detected execution response time (`lastResponseTimeMs`) in milliseconds on the main dashboard screen (`tv_response_time`).
5. **Automated Telegram Chat Detection:**
   - Listens to `AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED` and `AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED` filtered strictly to Telegram package names (`accessibility_service_config.xml`).
   - Filters out chat list / main screen when `dialogs_recycler` is detected (`isDialogsRecyclerPresent()`).
   - Verifies chat screen presence based on text input availability.
6. **Chat Visit State Machine:**
   - Tracks chat session state using `ChatVisitStateMachine` across `NOT_IN_CHAT`, `WAITING_FOR_INPUT`, `DONE_FOR_THIS_VISIT`, and `ABANDONED` states.
   - Prevents duplicate focus triggers when keyboard is manually dismissed within the same chat visit.
   - Handles fast switching between different chats by comparing conversation title strings (`extractConversationTitle()`).
   - Bounded 2.0-second time-out window (`RETRY_DURATION_NANO = 2_000_000_000L`) with ~35ms scheduled Handler retries for slow-loading devices.
7. **Fast Input Node Discovery:**
   - Multi-tier search strategy:
     1. Cached View ID lookup (`cachedResourceId`).
     2. Known Telegram input View IDs lookup (`chat_text_input`, `message_edit_text`, `chat_activity_enter_view`).
     3. Early-exit tree traversal targeting `EditText` classes positioned below 30% screen height (`searchTreeEarlyExit()`).
8. **Multi-Method Focus & Keyboard Activation:**
   - Issues `AccessibilityNodeInfo.ACTION_FOCUS` and `AccessibilityNodeInfo.ACTION_CLICK`.
   - Forces keyboard display via `InputMethodManager.toggleSoftInput(SHOW_FORCED, HIDE_IMPLICIT_ONLY)`.
   - Dispatches immediate synthetic tap gesture (`dispatchGesture()`) at node center coordinates as fallback.

### User Roles / Personas
- **Single User Persona:** End-user operating an Android phone running Android 8.0+ (API level 26+). No multi-user role hierarchy exists within the application.

### Domain / Business Rules & State Logic
- **Package Scope:** Must strictly react to `org.telegram.messenger` and `org.telegram.messenger.web`. Events from any other package reset state to `NOT_IN_CHAT`.
- **Chat vs. Non-Chat Rules:**
  - Presence of `dialogs_recycler` view ID $\Rightarrow$ NOT a chat screen (Chat List).
  - Absence of editable input node $\Rightarrow$ NOT a chat screen (Settings, Profile, etc.).
  - Absence of `dialogs_recycler` AND presence of input node $\Rightarrow$ Chat screen.
- **State Transition Logging Format:** All state changes must log to logcat using format `[STATE CHANGE] <OLD_STATE> -> <NEW_STATE> (reason=<REASON>)`.
- **Single Trigger Per Chat Visit:** Once action triggers, state enters `DONE_FOR_THIS_VISIT`. It remains in this state until navigating back to list (`NOT_IN_CHAT`), changing packages, or opening a chat with a different title.

---

## 2. Architecture Overview

### High-Level Architecture
The app operates strictly as a local, client-side Android background service utility. It does not communicate with any remote backend server, database, or API.

```
+-------------------------------------------------------------------------+
|                              Android OS                                 |
|                                                                         |
|  +---------------------------+       +-------------------------------+  |
|  |     Telegram App          |       |  Auto Focus for Telegram      |  |
|  |  (org.telegram.messenger) |       |  (com.autofocus.telegram)     |  |
|  +-------------+-------------+       +---------------+---------------+  |
|                |                                     |                  |
|                | AccessibilityEvents                 | Settings / UI    |
|                v                                     v                  |
|  +---------------------------------------------------+---------------+  |
|  |              TelegramFocusAccessibilityService                    |  |
|  |  +-------------------------------------------------------------+  |  |
|  |  |                   ChatVisitStateMachine                     |  |  |
|  |  |  (NOT_IN_CHAT -> WAITING_FOR_INPUT -> DONE / ABANDONED)   |  |  |
|  |  +-------------------------------------------------------------+  |  |
|  |  | Fast Node Finder (Cached ID -> Known IDs -> Tree Search)    |  |  |
|  |  +-------------------------------------------------------------+  |  |
|  |  | Action Dispatcher (ACTION_FOCUS + CLICK + IMM + Gesture)    |  |  |
|  |  +-------------------------------------------------------------+  |  |
|  +-------------------------------------------------------------------+  |
+-------------------------------------------------------------------------+
```

### Tech Stack
| Component | Technology / Library | Version | Caching/Notes |
| :--- | :--- | :--- | :--- |
| **Language** | Kotlin | `1.9.22` | Targeted JVM 17 |
| **Build Tool / Gradle** | Gradle (Kotlin DSL) | `8.8` | Android Gradle Plugin `8.3.2` |
| **Min / Target SDK** | Android SDK | Min: `26` (Android 8.0) / Target: `34` (Android 14) | - |
| **UI Framework** | AndroidX AppCompat / Material3 | AppCompat: `1.6.1`, Material: `1.11.0` | ViewBinding enabled |
| **Layout Manager** | AndroidX ConstraintLayout | `2.1.4` | Used in `activity_main.xml` |
| **Core Utilities** | AndroidX Core KTX | `1.12.0` | - |
| **Testing** | JUnit | `4.13.2` | Unit tests in `app/src/test` |

### Communication Mechanics
- **OS Inter-Process Communication:** Intersects UI layout trees across processes using Android `AccessibilityService` APIs (`AccessibilityNodeInfo`, `AccessibilityEvent`).
- **Input Control:** Uses `InputMethodManager` and `dispatchGesture` for synthetic touch events.
- **Internal Storage / IPC:** Shared memory static field `lastResponseTimeMs` transfers timing metrics from service process to `MainActivity`.

---

## 3. Directory & File Map

```
keyboard-pop/
├── .github/
│   └── workflows/
│       └── release.yml                 # GitHub Actions pipeline for tagged & dispatch release builds
├── app/
│   ├── proguard-rules.pro              # Proguard optimization rules (currently default empty)
│   ├── build.gradle.kts                # Application module Gradle configuration & release signing logic
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml     # Application manifest declaring activity, accessibility service & queries
│       │   ├── java/com/autofocus/telegram/
│       │   │   ├── ChatVisitStateMachine.kt                 # Pure Kotlin finite state machine for chat visits
│       │   │   ├── MainActivity.kt                          # Configuration activity UI, switch logic & disclosure
│       │   │   └── TelegramFocusAccessibilityService.kt     # Core AccessibilityService node finder & trigger engine
│       │   └── res/
│       │       ├── layout/
│       │       │   └── activity_main.xml                # Dashboard screen layout XML with Material3 components
│       │       ├── values/
│       │       │   ├── strings.xml                      # Localized UI string resources
│       │       │   └── themes.xml                       # Material3 DayNight application theme
│       │       └── xml/
│       │           └── accessibility_service_config.xml # OS accessibility configuration parameters
│       └── test/
│           └── java/com/autofocus/telegram/
│               └── ChatVisitStateMachineTest.kt         # Comprehensive JUnit unit tests for ChatVisitStateMachine
├── build.gradle.kts                    # Root build script applying plugins
├── gradle/
│   ├── libs.versions.toml              # Version catalog for dependencies and plugins
│   └── wrapper/
│       ├── gradle-wrapper.jar          # Gradle wrapper executable archive
│       └── gradle-wrapper.properties   # Gradle version specification (8.8)
├── gradle.properties                       # Build JVM options (-Xmx2048m) and AndroidX flags
├── gradlew                             # Unix build script wrapper
├── gradlew.bat                         # Windows build script wrapper
├── settings.gradle.kts                 # Project repository settings and module inclusions
├── README.md                           # User setup, permission granting, Play Protect & CI/CD documentation
└── context.md                          # Comprehensive repository context file
```

### Key Entry-Point Files
- **App Entry Point / UI:** `app/src/main/java/com/autofocus/telegram/MainActivity.kt`
- **Background Engine:** `app/src/main/java/com/autofocus/telegram/TelegramFocusAccessibilityService.kt`
- **State Machine Core:** `app/src/main/java/com/autofocus/telegram/ChatVisitStateMachine.kt`
- **Manifest Configuration:** `app/src/main/AndroidManifest.xml`
- **Dependencies Version Catalog:** `gradle/libs.versions.toml`
- **CI/CD Build Pipeline:** `.github/workflows/release.yml`

---

## 4. Data Model

The application operates without an explicit persistent database engine (such as SQLite or Room). Instead, data models exist in-memory as Kotlin Enums, Sealed Classes, and State Objects within `app/src/main/java/com/autofocus/telegram/ChatVisitStateMachine.kt`.

### In-Memory State Models

#### 1. `ChatVisitState` (Enum)
Represents the exact phase of a user's visit to a Telegram chat screen.
| State | Meaning / Trigger |
| :--- | :--- |
| `NOT_IN_CHAT` | User is outside Telegram, on the chat list (`dialogs_recycler`), or viewing non-chat screens (Settings/Profile). |
| `WAITING_FOR_INPUT` | User has entered a chat screen, but message input field has not yet been focused/triggered. |
| `DONE_FOR_THIS_VISIT` | Message input box has been located and focus/keyboard/gesture actions were dispatched. |
| `ABANDONED` | Search retry limit exceeded 2.0s bounded window without locating input node. |

#### 2. `VisitCheckResult` (Sealed Class)
ReturnValue of state machine evaluation instructing service action.
| Result Subtype | Purpose |
| :--- | :--- |
| `VisitCheckResult.DoNothing` | Service should take no action and stop retries. |
| `VisitCheckResult.ShouldSearchAndTrigger` | Service should search UI hierarchy for input box and attempt focus dispatch. |

#### 3. `ChatVisitStateMachine` (Class Fields)
| Field Name | Type | Purpose |
| :--- | :--- | :--- |
| `currentState` | `ChatVisitState` | Read-only current state. Updated internally via `transitionTo()`. |
| `activeConversationTitle` | `String?` | Title of current chat (e.g. contact/group name) used to detect quick switches. |
| `visitStartTimeNano` | `Long` | `System.nanoTime()` timestamp when `WAITING_FOR_INPUT` was entered. |
| `maxRetryDurationNano` | `Long` | Maximum allowed nanoseconds before moving to `ABANDONED` (default: 2s / `2_000_000_000L`). |

---

## 5. APIs & Integrations

### Internal APIs / Inter-Process Callbacks
Because this is a native Android client application, internal "APIs" consist of system event handlers and Android Service callbacks:

| Component / Callback | Source / Path | Purpose |
| :--- | :--- | :--- |
| `onAccessibilityEvent(event)` | `TelegramFocusAccessibilityService.kt` | Intercepts `typeWindowStateChanged` and `typeWindowContentChanged` events from Telegram. |
| `onServiceConnected()` | `TelegramFocusAccessibilityService.kt` | Configures service lifecycle upon Android system binding. |
| `isAccessibilityServiceEnabled()` | `MainActivity.kt` | Queries `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` to check permission status. |
| `checkTelegramInstalled()` | `MainActivity.kt` | Queries `PackageManager.getPackageInfo` for Telegram installation status. |

### External Services & Third-Party SDK Integrations
| Integration / Package | Target Scope | Purpose |
| :--- | :--- | :--- |
| **Standard Telegram** | `org.telegram.messenger` | Primary target app observed for chat input focus automation. |
| **Telegram X** | `org.telegram.messenger.web` | Alternate target app observed for chat input focus automation. |
| **Android System Accessibility Framework** | `android.accessibilityservice` | Reads screen UI elements and dispatches gestures. |
| **Android Input Method Manager** | `android.view.inputmethod.InputMethodManager` | Toggles soft keyboard (`toggleSoftInput`). |
| **GitHub Actions** | `.github/workflows/release.yml` | Automated CI pipeline for building, signing, and uploading release APK assets. |

---

## 6. Setup & Environment

### Required Environment Variables / Secrets
The application requires build environment variables for release signing during production builds (`app/build.gradle.kts` & `.github/workflows/release.yml`):

| Variable / Secret Name | Purpose | Required For Local Build? |
| :--- | :--- | :--- |
| `KEYSTORE_BASE64` | Base64-encoded string of the `.keystore` release signing file. | No (Defaults to debug keystore if omitted). |
| `KEYSTORE_PASSWORD` | Password for the release keystore store. | No |
| `KEY_ALIAS` | Key alias name inside the keystore. | No |
| `KEY_PASSWORD` | Password for the key alias. | No |

### Local Development Prerequisites
- **JDK:** Version 17 (Java 17)
- **Android SDK:** API Level 34 (Build Tools 34.0.0)
- **Gradle:** Version 8.8 (provided via `./gradlew` wrapper)

### Local Build & Test Commands
- **Run Unit Tests:**
  ```bash
  ./gradlew test
  ```
- **Build Debug APK:**
  ```bash
  ./gradlew assembleDebug
  ```
  *Output location:* `app/build/outputs/apk/debug/app-debug.apk`
- **Build Release APK:**
  ```bash
  ./gradlew assembleRelease
  ```
  *Output location:* `app/build/outputs/apk/release/app-release.apk`
- **Run Full Verification Suite:**
  ```bash
  ./gradlew test assembleDebug assembleRelease
  ```

### CI/CD Build & Deployment Process
The workflow in `.github/workflows/release.yml` executes on pushed tags matching `v*.*.*` or manual `workflow_dispatch`:
1. Sets up JDK 17 (Temurin) and Gradle action cache.
2. Evaluates tag name:
   - Uses `github.event.inputs.tag_name` if provided manually.
   - Uses `github.ref_name` if tag push.
   - Auto-generates `v1.0.<github.run_number>` if run on `workflow_dispatch` without input.
3. Decodes `KEYSTORE_BASE64` secret into `app/build/release.keystore` and compiles release APK via `./gradlew assembleRelease`.
4. Creates a GitHub Release using `softprops/action-gh-release@v2` and attaches `app-release.apk`.

---

## 7. Conventions & Patterns

### Code Conventions & Naming
- **Kotlin Standard Style:** Strict idiomatic Kotlin code using standard camelCase for variables/functions and PascalCase for classes/enums.
- **Explicit Logging Tags:** `private const val TAG = "TelegramFocusService"` in companion object.
- **State Machine Logging Convention:** All FSM transitions must call `transitionTo()` which logs:
  `[STATE CHANGE] <OLD_STATE> -> <NEW_STATE> (reason=<REASON>)`
- **View ID Naming:** XML View IDs follow snake_case (e.g., `@+id/switch_service`, `@+id/tv_response_time`, `@+id/btn_open_settings`).

### Architecture & State Management Patterns
- **Pure FSM Isolation:** State transition rules are isolated in `ChatVisitStateMachine.kt` with 0 Android framework UI dependencies, enabling unit testing on standard JVM (`ChatVisitStateMachineTest.kt`).
- **Defensive Resource Management:** All `AccessibilityNodeInfo` instances are wrapped in `try/finally` blocks and recycled explicitly via `node.recycle()` to avoid memory leaks across high-frequency accessibility events.
- **Tiered Fallback Search Pattern:**
  1. Check cached View ID (`cachedResourceId`).
  2. Query known resource ID lists (`INPUT_RESOURCE_IDS`).
  3. Perform recursive tree search (`searchTreeEarlyExit`) checking editable `EditText` views below top 30% screen boundary.

### Testing Setup
- Unit tests reside in `app/src/test/java/com/autofocus/telegram/ChatVisitStateMachineTest.kt`.
- Tests verify initial state, cold start, keyboard dismissal persistence, navigation back to chat list, package switches, non-chat UI navigation, and timeout limits.
- Run tests via `./gradlew test`.

---

## 8. Known Gaps / TODOs

1. **Obsolete Input Method Manager Calls:**
   - `TelegramFocusAccessibilityService.kt` calls `InputMethodManager.toggleSoftInput()`, which is deprecated starting in Android 12 (API 31+). It works as a best-effort fallback alongside `ACTION_FOCUS`, `ACTION_CLICK`, and synthetic tap gestures.
2. **Empty Proguard Rules:**
   - `app/proguard-rules.pro` contains no custom rules. While minification is currently disabled (`isMinifyEnabled = false` in `app/build.gradle.kts`), enable/shrinking rules will need to be defined if code obfuscation is enabled in the future.
3. **Hardcoded View IDs:**
   - View ID strings (`org.telegram.messenger:id/chat_text_input`, etc.) are hardcoded in `TelegramFocusAccessibilityService.kt`. If Telegram updates its layout resource names in future client releases, these lists will need updating.
4. **Play Store Accessibility Policy:**
   - As noted in `README.md`, publishing on Google Play Store requires explicit policy exemption for non-accessibility accessibility services. The app is primarily structured for sideloading/personal use.
