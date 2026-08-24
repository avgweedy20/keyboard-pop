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
        private const val RETRY_INTERVAL_MS = 35L // Fallback retry check interval

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

        // Known toolbar / title bar resource IDs in Telegram
        private val TITLE_RESOURCE_IDS = listOf(
            "org.telegram.messenger:id/action_bar_title",
            "org.telegram.messenger:id/title",
            "org.telegram.messenger.web:id/action_bar_title",
            "org.telegram.messenger.web:id/title"
        )
    }

    val stateMachine = ChatVisitStateMachine(maxRetryDurationNano = RETRY_DURATION_NANO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRetryRunnableScheduled = false

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
                processAndTrigger(rootNode, t0, triggerSource = "timer")
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

        val packageName = event.packageName?.toString() ?: return
        if (packageName !in MainActivity.TELEGRAM_PACKAGES) return

        val t0 = System.nanoTime()
        val rootNode = rootInActiveWindow ?: return

        try {
            processAndTrigger(rootNode, t0, triggerSource = "event")
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

    private fun processAndTrigger(rootNode: AccessibilityNodeInfo, t0: Long, triggerSource: String) {
        val isChat = isChatConversationScreenFast(rootNode)
        val title = if (isChat) extractConversationTitle(rootNode) else null

        val evalResult = stateMachine.evaluate(isChat, title, t0)

        if (evalResult is VisitCheckResult.DoNothing) {
            if (stateMachine.currentState != ChatVisitState.WAITING_FOR_INPUT) {
                mainHandler.removeCallbacks(retryRunnable)
                isRetryRunnableScheduled = false
            }
            return
        }

        // State is WAITING_FOR_INPUT and attempt permitted
        val t1 = System.nanoTime()
        val (inputNode, searchMethod) = findMessageInputNodeFast(rootNode)
        val t2 = System.nanoTime()

        if (inputNode == null) {
            Log.d(TAG, "[$triggerSource] WAITING_FOR_INPUT: Chat screen detected, but no editable input node found yet. State remains ${stateMachine.currentState}")
            scheduleNextRetryIfNeeded()
            return
        }

        // Found input node! Perform actions and mark transition to DONE_FOR_THIS_VISIT
        stateMachine.markActionTriggered()
        mainHandler.removeCallbacks(retryRunnable)
        isRetryRunnableScheduled = false

        val focusSuccess = inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val clickSuccess = inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val t3 = System.nanoTime()

        triggerSoftKeyboard()
        val t4 = System.nanoTime()

        // Immediate gesture fallback if needed
        val inputBounds = Rect()
        inputNode.getBoundsInScreen(inputBounds)
        val gestureDispatched = dispatchTapGesture(inputBounds)

        @Suppress("DEPRECATION")
        inputNode.recycle()

        val detectionAndDispatchMs = (t4 - t0) / 1_000_000.0
        lastResponseTimeMs = detectionAndDispatchMs

        Log.i(
            TAG,
            String.format(
                Locale.US,
                "[TIMING] source=%s method=%s | OUR_DETECTION_AND_DISPATCH=%.2fms (detect=%.2fms, find=%.2fms, action=%.2fms, kb=%.2fms) | focusOk=%b clickOk=%b gestureSent=%b state=%s title='%s'",
                triggerSource, searchMethod, detectionAndDispatchMs,
                (t1 - t0) / 1_000_000.0, (t2 - t1) / 1_000_000.0, (t3 - t2) / 1_000_000.0, (t4 - t3) / 1_000_000.0,
                focusSuccess, clickSuccess, gestureDispatched, stateMachine.currentState, title
            )
        )
    }

    private fun scheduleNextRetryIfNeeded() {
        if (stateMachine.currentState == ChatVisitState.WAITING_FOR_INPUT && !isRetryRunnableScheduled) {
            isRetryRunnableScheduled = true
            mainHandler.postDelayed(retryRunnable, RETRY_INTERVAL_MS)
        }
    }

    private fun extractConversationTitle(rootNode: AccessibilityNodeInfo): String? {
        for (resId in TITLE_RESOURCE_IDS) {
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
     * Fast check whether the root node represents a chat screen.
     * Rejects chat list view instantly if chat list recycler is found.
     */
    private fun isChatConversationScreenFast(rootNode: AccessibilityNodeInfo): Boolean {
        val chatListNodes = rootNode.findAccessibilityNodeInfosByViewId("org.telegram.messenger:id/dialogs_recycler")
        if (!chatListNodes.isNullOrEmpty()) {
            for (node in chatListNodes) {
                @Suppress("DEPRECATION")
                node.recycle()
            }
            return false
        }

        val chatListNodesWeb = rootNode.findAccessibilityNodeInfosByViewId("org.telegram.messenger.web:id/dialogs_recycler")
        if (!chatListNodesWeb.isNullOrEmpty()) {
            for (node in chatListNodesWeb) {
                @Suppress("DEPRECATION")
                node.recycle()
            }
            return false
        }

        return true
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
            @Suppress("DEPRECATION")
            imm?.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing soft keyboard trigger", e)
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
