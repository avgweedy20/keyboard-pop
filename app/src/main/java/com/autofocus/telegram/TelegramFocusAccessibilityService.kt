package com.autofocus.telegram

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import java.util.Locale

class TelegramFocusAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TelegramFocusService"
        private const val MAX_RETRY_DURATION_NANO = 500_000_000L // 500 ms retry window

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

    private var lastHandledChatSignature: String? = null
    private var activeChatSignature: String? = null
    private var activeChatStartTimeNano: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName !in MainActivity.TELEGRAM_PACKAGES) return

        val t0 = System.nanoTime()

        val rootNode = rootInActiveWindow ?: return

        try {
            processWindowEvent(rootNode, t0)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing accessibility event safely", e)
        } finally {
            @Suppress("DEPRECATION")
            rootNode.recycle()
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "Service interrupted")
    }

    private fun processWindowEvent(rootNode: AccessibilityNodeInfo, t0: Long) {
        // Fast check if current window is chat screen
        val isChat = isChatConversationScreenFast(rootNode)
        val t1 = System.nanoTime()

        if (!isChat) {
            if (lastHandledChatSignature != null || activeChatSignature != null) {
                lastHandledChatSignature = null
                activeChatSignature = null
                activeChatStartTimeNano = 0L
            }
            return
        }

        // Search for editable input node using fast-path caching
        val (inputNode, searchMethod) = findMessageInputNodeFast(rootNode)
        val t2 = System.nanoTime()

        if (inputNode == null) {
            Log.d(TAG, "Chat screen signature detected, but no editable input node found.")
            return
        }

        // Generate dynamic chat signature incorporating the input node & layout identity
        val currentSignature = generateDynamicChatSignature(rootNode, inputNode)

        // Check if this chat signature is already handled
        if (currentSignature == lastHandledChatSignature) {
            @Suppress("DEPRECATION")
            inputNode.recycle()
            return
        }

        // Check or initialize window retry state
        if (activeChatSignature != currentSignature) {
            activeChatSignature = currentSignature
            activeChatStartTimeNano = t0
        } else {
            if (t0 - activeChatStartTimeNano > MAX_RETRY_DURATION_NANO) {
                @Suppress("DEPRECATION")
                inputNode.recycle()
                return
            }
        }

        lastHandledChatSignature = currentSignature

        // Fire click and focus actions on the actual editable node
        val focusSuccess = inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val clickSuccess = inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val t3 = System.nanoTime()

        // Fire soft keyboard trigger immediately without artificial delays
        triggerSoftKeyboard()
        val t4 = System.nanoTime()

        @Suppress("DEPRECATION")
        inputNode.recycle()

        val totalElapsedMs = (t4 - t0) / 1_000_000.0
        lastResponseTimeMs = totalElapsedMs

        if (BuildConfig.DEBUG) {
            val detectMs = (t1 - t0) / 1_000_000.0
            val findMs = (t2 - t1) / 1_000_000.0
            val focusMs = (t3 - t2) / 1_000_000.0
            val keyboardMs = (t4 - t3) / 1_000_000.0

            Log.d(
                TAG,
                String.format(
                    Locale.US,
                    "[TIMING] method=%s | total=%.2fms (detect=%.2fms, find=%.2fms, focus=%.2fms, kb=%.2fms) | focusOk=%b clickOk=%b",
                    searchMethod, totalElapsedMs, detectMs, findMs, focusMs, keyboardMs, focusSuccess, clickSuccess
                )
            )
        }
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

        // 3. Early-exit tree search (stops traversal immediately on first matching bottom EditText)
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

    /**
     * Verifies if node is an editable EditText; if node is a container (e.g. ChatActivityEnterView),
     * searches inside child nodes for the actual editable EditText child.
     */
    private fun extractEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
            @Suppress("DEPRECATION")
            return AccessibilityNodeInfo.obtain(node)
        }

        // Search children inside container node
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

    /**
     * Recursive DFS that returns immediately upon finding the first editable bottom-screen node.
     */
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

    /**
     * Generates a dynamic chat signature incorporating the input node's bounds and window structure,
     * ensuring unique signature detection across chat screen transitions.
     */
    private fun generateDynamicChatSignature(rootNode: AccessibilityNodeInfo, inputNode: AccessibilityNodeInfo): String {
        val rootBounds = Rect()
        rootNode.getBoundsInScreen(rootBounds)

        val inputBounds = Rect()
        inputNode.getBoundsInScreen(inputBounds)

        val nodeText = inputNode.text?.toString() ?: ""

        return "chat_${rootNode.windowId}_${inputBounds.left}_${inputBounds.top}_${inputNode.childCount}_${nodeText.hashCode()}"
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
}
