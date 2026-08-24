package com.autofocus.telegram

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import java.util.Locale

class TelegramFocusAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TelegramFocusService"
        const val RETRY_DURATION_NANO = 2_000_000_000L // 2.0s bounded window for slow devices

        @Volatile
        var lastResponseTimeMs: Double? = null

        private var cachedResourceId: String? = null

        // Likely resource IDs for Telegram message input box
        private val INPUT_RESOURCE_IDS = listOf(
            "org.telegram.messenger:id/chat_text_input",
            "org.telegram.messenger:id/message_edit_text",
            "org.telegram.messenger:id/chat_activity_enter_view",
            "org.telegram.messenger.web:id/chat_text_input",
            "org.telegram.messenger.web:id/message_edit_text",
            "org.telegram.messenger.web:id/chat_activity_enter_view"
        )
    }

    val stateMachine = ChatVisitStateMachine(maxRetryDurationNano = RETRY_DURATION_NANO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRetryRunnableScheduled = false
    private var retryAttemptCounter = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "=== TelegramFocusAccessibilityService CONNECTED ===")
    }

    private val retryRunnable = object : Runnable {
        override fun run() {
            isRetryRunnableScheduled = false
            if (stateMachine.currentState != ChatVisitState.WAITING_FOR_INPUT) {
                return
            }

            val t0 = System.nanoTime()
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                scheduleNextRetryIfNeeded()
                return
            }

            try {
                processAndTrigger(rootNode, t0, triggerSource = "timer", packageName = "org.telegram.messenger")
            } catch (e: Exception) {
                Log.e(TAG, "Error during fallback timer check", e)
            } finally {
                @Suppress("DEPRECATION")
                rootNode.recycle()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventTypeName = AccessibilityEvent.eventTypeToString(event.eventType)
        val rawPackage = event.packageName?.toString() ?: "null"
        Log.d(TAG, "[EVENT RECEIVED] type=$eventTypeName package=$rawPackage")

        val packageName = event.packageName?.toString()
        if (packageName == null || packageName !in MainActivity.TELEGRAM_PACKAGES) {
            if (stateMachine.currentState != ChatVisitState.NOT_IN_CHAT) {
                stateMachine.resetToNotInChat(reason = "non_telegram_package (pkg=$rawPackage)")
                mainHandler.removeCallbacks(retryRunnable)
                isRetryRunnableScheduled = false
            }
            return
        }

        val t0 = System.nanoTime()
        val rootNode = rootInActiveWindow ?: return

        try {
            processAndTrigger(rootNode, t0, triggerSource = "event", packageName = packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing accessibility event safely", e)
        } finally {
            @Suppress("DEPRECATION")
            rootNode.recycle()
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "Service interrupted")
        mainHandler.removeCallbacks(retryRunnable)
        isRetryRunnableScheduled = false
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(retryRunnable)
        isRetryRunnableScheduled = false
    }

    private fun getRetryDelayMs(attempt: Int): Long {
        return when {
            attempt <= 5 -> 8L    // Front-loaded fast retries (~8ms)
            attempt <= 10 -> 16L  // Frame-level retries (~16ms)
            else -> 35L           // Back off to 35ms within 2.0s ceiling
        }
    }

    private fun processAndTrigger(rootNode: AccessibilityNodeInfo, t0: Long, triggerSource: String, packageName: String) {
        val t0Nano = t0

        val hasDialogsRecycler = try {
            isDialogsRecyclerPresent(rootNode, packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Exception checking dialogs recycler", e)
            false
        }

        val t3Nano = System.nanoTime()
        val (inputNode, searchMethod) = if (!hasDialogsRecycler) {
            try {
                findMessageInputNodeFast(rootNode)
            } catch (e: Exception) {
                Log.e(TAG, "Exception in findMessageInputNodeFast", e)
                Pair(null, "error")
            }
        } else {
            Pair(null, "dialogs_recycler_present")
        }
        val t4Nano = System.nanoTime()
        val t1Nano = t4Nano // Chat screen confirmed after recycler check and input node search

        val (isChat, notInChatReason) = when {
            hasDialogsRecycler -> Pair(false, "dialogs_recycler_detected")
            inputNode == null -> Pair(false, "input_node_absent")
            else -> Pair(true, "")
        }

        val title = if (isChat) {
            try {
                extractConversationTitle(rootNode, packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Exception in extractConversationTitle", e)
                null
            }
        } else null

        val prevState = stateMachine.currentState
        val evalResult = try {
            stateMachine.evaluate(isChat, title, t0Nano, notInChatReason = notInChatReason)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in stateMachine.evaluate", e)
            VisitCheckResult.DoNothing
        }
        val t2Nano = System.nanoTime()

        if (prevState != ChatVisitState.WAITING_FOR_INPUT && stateMachine.currentState == ChatVisitState.WAITING_FOR_INPUT) {
            retryAttemptCounter = 0
            Log.i(TAG, "chat screen detected, entering WAITING_FOR_INPUT (title='$title')")
        }

        if (stateMachine.currentState == ChatVisitState.WAITING_FOR_INPUT) {
            retryAttemptCounter++
            Log.d(TAG, "retry attempt $retryAttemptCounter (source=$triggerSource, elapsed=${(t0Nano - stateMachine.visitStartTimeNano) / 1_000_000}ms)")
        }

        if (evalResult is VisitCheckResult.DoNothing) {
            inputNode?.let {
                @Suppress("DEPRECATION")
                it.recycle()
            }
            if (stateMachine.currentState != ChatVisitState.WAITING_FOR_INPUT) {
                mainHandler.removeCallbacks(retryRunnable)
                isRetryRunnableScheduled = false
            }
            return
        }

        if (inputNode == null) {
            Log.i(TAG, "node search result: NOT FOUND (method=$searchMethod)")
            scheduleNextRetryIfNeeded()
            return
        }

        val nodeClassName = inputNode.className?.toString() ?: "unknown"
        Log.i(TAG, "node search result: FOUND ($nodeClassName, method=$searchMethod)")

        // Found input node! Perform actions and mark transition to DONE_FOR_THIS_VISIT
        stateMachine.markActionTriggered()
        mainHandler.removeCallbacks(retryRunnable)
        isRetryRunnableScheduled = false

        val focusSuccess = try {
            inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        } catch (e: Exception) {
            Log.e(TAG, "Exception executing ACTION_FOCUS", e)
            false
        }

        val clickSuccess = try {
            inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) {
            Log.e(TAG, "Exception executing ACTION_CLICK", e)
            false
        }

        triggerSoftKeyboard()

        val inputBounds = Rect()
        try {
            inputNode.getBoundsInScreen(inputBounds)
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting bounds in screen for input node", e)
        }

        val gestureDispatched = dispatchTapGesture(inputBounds)
        val t5Nano = System.nanoTime()

        @Suppress("DEPRECATION")
        inputNode.recycle()

        val t6Nano = System.nanoTime()

        val d10 = (t1Nano - t0Nano) / 1_000_000.0
        val d21 = (t2Nano - t1Nano) / 1_000_000.0
        val d43 = (t4Nano - t3Nano) / 1_000_000.0
        val d52 = (t5Nano - t2Nano) / 1_000_000.0
        val d65 = (t6Nano - t5Nano) / 1_000_000.0
        val totalMs = (t6Nano - t0Nano) / 1_000_000.0

        lastResponseTimeMs = totalMs

        Log.i(
            TAG,
            String.format(
                Locale.US,
                "[TIMING] source=%s tier=%s | T1-T0=%.2fms (chat check), T2-T1=%.2fms (fsm eval), T4-T3=%.2fms (node search), T5-T2=%.2fms (dispatch), T6-T5=%.2fms (completion) | TOTAL=%.2fms | focusOk=%b clickOk=%b gestureSent=%b state=%s title='%s'",
                triggerSource, searchMethod, d10, d21, d43, d52, d65, totalMs,
                focusSuccess, clickSuccess, gestureDispatched, stateMachine.currentState, title
            )
        )
    }

    private fun scheduleNextRetryIfNeeded() {
        if (stateMachine.currentState == ChatVisitState.WAITING_FOR_INPUT && !isRetryRunnableScheduled) {
            isRetryRunnableScheduled = true
            val delayMs = getRetryDelayMs(retryAttemptCounter)
            mainHandler.postDelayed(retryRunnable, delayMs)
        }
    }

    private fun extractConversationTitle(rootNode: AccessibilityNodeInfo, packageName: String?): String? {
        val titleIds = if (packageName == "org.telegram.messenger.web") {
            listOf(
                "org.telegram.messenger.web:id/action_bar_title",
                "org.telegram.messenger.web:id/title"
            )
        } else {
            listOf(
                "org.telegram.messenger:id/action_bar_title",
                "org.telegram.messenger:id/title"
            )
        }
        for (resId in titleIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(resId)
            if (!nodes.isNullOrEmpty()) {
                val titleText = nodes.firstOrNull()?.text?.toString()
                for (node in nodes) {
                    @Suppress("DEPRECATION")
                    node.recycle()
                }
                if (!titleText.isNullOrBlank()) {
                    return titleText
                }
            }
        }
        return null
    }

    /**
     * Fast check whether the dialogs recycler (chat list) is present.
     */
    private fun isDialogsRecyclerPresent(rootNode: AccessibilityNodeInfo, packageName: String?): Boolean {
        val resId = if (packageName == "org.telegram.messenger.web") {
            "org.telegram.messenger.web:id/dialogs_recycler"
        } else {
            "org.telegram.messenger:id/dialogs_recycler"
        }
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(resId)
        if (!nodes.isNullOrEmpty()) {
            for (node in nodes) {
                @Suppress("DEPRECATION")
                node.recycle()
            }
            return true
        }
        return false
    }

    /**
     * Fast-path defensive search that strictly guarantees returning an editable EditText node.
     */
    private fun findMessageInputNodeFast(rootNode: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo?, String> {
        // 1. Cached Resource ID
        cachedResourceId?.let { resId ->
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(resId)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    val editableNode = extractEditableNode(node)
                    if (editableNode != null) {
                        for (other in nodes) {
                            if (other != node) {
                                @Suppress("DEPRECATION")
                                other.recycle()
                            }
                        }
                        return Pair(editableNode, "cached_id")
                    }
                    @Suppress("DEPRECATION")
                    node.recycle()
                }
            }
        }

        // 2. Known Resource IDs
        for (resId in INPUT_RESOURCE_IDS) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(resId)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    val editableNode = extractEditableNode(node)
                    if (editableNode != null) {
                        for (other in nodes) {
                            if (other != node) {
                                @Suppress("DEPRECATION")
                                other.recycle()
                            }
                        }
                        cachedResourceId = editableNode.viewIdResourceName ?: resId
                        return Pair(editableNode, "resource_id")
                    }
                    @Suppress("DEPRECATION")
                    node.recycle()
                }
            }
        }

        // 3. Early-exit tree search
        val displayBounds = Rect()
        rootNode.getBoundsInScreen(displayBounds)
        val screenHeight = displayBounds.height().toFloat().takeIf { it > 0 } ?: 2000f

        val earlyExitNode = searchTreeEarlyExit(rootNode, screenHeight * 0.3f)
        if (earlyExitNode != null) {
            earlyExitNode.viewIdResourceName?.let { resId ->
                cachedResourceId = resId
            }
            return Pair(earlyExitNode, "tree_search")
        }

        return Pair(null, "none")
    }

    private fun extractEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
            @Suppress("DEPRECATION")
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = extractEditableNode(child)
            @Suppress("DEPRECATION")
            child.recycle()
            if (result != null) {
                return result
            }
        }

        return null
    }

    private fun searchTreeEarlyExit(node: AccessibilityNodeInfo, minY: Float): AccessibilityNodeInfo? {
        val className = node.className?.toString() ?: ""
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if ((className.contains("EditText", ignoreCase = true) || node.isEditable) &&
            node.isVisibleToUser &&
            bounds.top > minY
        ) {
            @Suppress("DEPRECATION")
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = searchTreeEarlyExit(child, minY)
            @Suppress("DEPRECATION")
            child.recycle()
            if (result != null) {
                return result
            }
        }

        return null
    }

    private fun triggerSoftKeyboard() {
        try {
            @Suppress("DEPRECATION")
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            Log.i(TAG, "showSoftInput / toggleSoftInput invoked (InputMethodManager available: ${imm != null})")
            @Suppress("DEPRECATION")
            imm?.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during showSoftInput / toggleSoftInput invocation", e)
        }
    }

    private fun dispatchTapGesture(bounds: Rect): Boolean {
        if (bounds.isEmpty) return false

        val centerX = bounds.centerX().toFloat()
        val centerY = bounds.centerY().toFloat()

        val path = Path().apply {
            moveTo(centerX, centerY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return try {
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching synthetic tap gesture", e)
            false
        }
    }
}
