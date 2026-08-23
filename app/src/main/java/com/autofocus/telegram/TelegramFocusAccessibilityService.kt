package com.autofocus.telegram

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager

class TelegramFocusAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TelegramFocusService"

        // Likely resource IDs for Telegram message input box
        private val INPUT_RESOURCE_IDS = listOf(
            "org.telegram.messenger:id/chat_activity_enter_view",
            "org.telegram.messenger:id/chat_text_input",
            "org.telegram.messenger:id/message_edit_text",
            "org.telegram.messenger.web:id/chat_activity_enter_view",
            "org.telegram.messenger.web:id/chat_text_input",
            "org.telegram.messenger.web:id/message_edit_text"
        )
    }

    private var lastHandledChatSignature: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName !in MainActivity.TELEGRAM_PACKAGES) return

        val rootNode = rootInActiveWindow ?: return

        try {
            processWindow(rootNode)
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

    private fun processWindow(rootNode: AccessibilityNodeInfo) {
        val isChatScreen = isChatConversationScreen(rootNode)

        if (!isChatScreen) {
            // User left chat screen, reset transition tracking signature
            if (lastHandledChatSignature != null) {
                Log.d(TAG, "Exited chat screen, resetting transition tracker")
                lastHandledChatSignature = null
            }
            return
        }

        // Generate a signature for the current chat window screen
        val currentSignature = generateChatSignature(rootNode)

        if (currentSignature == lastHandledChatSignature) {
            // Already focused and handled for this chat entry
            return
        }

        val inputNode = findMessageInputNode(rootNode)
        if (inputNode != null) {
            Log.d(TAG, "Chat screen detected. Focusable input found. Executing instant auto-focus...")
            lastHandledChatSignature = currentSignature

            performFocusAndKeyboardTrigger(inputNode)
            @Suppress("DEPRECATION")
            inputNode.recycle()
        } else {
            Log.d(TAG, "Chat screen signature detected, but no EditText input node found.")
        }
    }

    /**
     * Determines whether the current window structure represents a Telegram chat screen.
     * Signature:
     * - Presence of an editable EditText (message box)
     * - Absence of primary chat list RecyclerView (which dominates chat list view)
     */
    private fun isChatConversationScreen(rootNode: AccessibilityNodeInfo): Boolean {
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        flattenTree(rootNode, allNodes)

        var hasEditText = false
        var hasChatListRecyclerView = false

        val displayBounds = Rect()
        rootNode.getBoundsInScreen(displayBounds)
        val screenHeight = displayBounds.height().toFloat().takeIf { it > 0 } ?: 2000f

        for (node in allNodes) {
            val className = node.className?.toString() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // Check if node is an EditText near bottom half
            if ((className.contains("EditText", ignoreCase = true) || node.isEditable) && bounds.top > (screenHeight * 0.3f)) {
                hasEditText = true
            }

            // Check for chat list indicators (e.g. main chat list recycler view)
            if (viewId.contains("chat_list") || viewId.contains("dialogs_recycler")) {
                hasChatListRecyclerView = true
            }

            @Suppress("DEPRECATION")
            node.recycle()
        }

        return hasEditText && !hasChatListRecyclerView
    }

    /**
     * Generates a signature string for tracking chat entry transitions.
     */
    private fun generateChatSignature(rootNode: AccessibilityNodeInfo): String {
        val bounds = Rect()
        rootNode.getBoundsInScreen(bounds)
        return "window_${rootNode.windowId}_${bounds.width()}x${bounds.height()}"
    }

    /**
     * Defensively locates the message input EditText node.
     * Strategy:
     * 1. Search by known/exact resource ID patterns
     * 2. Search by EditText class name / isEditable property near bottom of screen
     */
    private fun findMessageInputNode(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Strategy 1: Known resource IDs
        for (resId in INPUT_RESOURCE_IDS) {
            val matchingNodes = rootNode.findAccessibilityNodeInfosByViewId(resId)
            if (!matchingNodes.isNullOrEmpty()) {
                val node = matchingNodes.first()
                for (i in 1 until matchingNodes.size) {
                    @Suppress("DEPRECATION")
                    matchingNodes[i].recycle()
                }
                return node
            }
        }

        // Strategy 2: Defensive search for focusable/editable EditText near bottom half
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        flattenTree(rootNode, allNodes)

        val displayBounds = Rect()
        rootNode.getBoundsInScreen(displayBounds)
        val screenHeight = displayBounds.height().toFloat().takeIf { it > 0 } ?: 2000f

        var fallbackInputNode: AccessibilityNodeInfo? = null

        for (node in allNodes) {
            val className = node.className?.toString() ?: ""
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if ((className.contains("EditText", ignoreCase = true) || node.isEditable) &&
                node.isVisibleToUser &&
                bounds.top > (screenHeight * 0.3f)
            ) {
                if (fallbackInputNode == null) {
                    fallbackInputNode = node
                    continue
                }
            }
            @Suppress("DEPRECATION")
            node.recycle()
        }

        return fallbackInputNode
    }

    /**
     * Instantly performs click & focus accessibility actions and triggers soft keyboard display immediately.
     */
    private fun performFocusAndKeyboardTrigger(node: AccessibilityNodeInfo) {
        // Execute instant accessibility actions
        val focusSuccess = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val clickSuccess = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.d(TAG, "Instant ACTION_FOCUS result: $focusSuccess, ACTION_CLICK result: $clickSuccess")

        // Immediately trigger keyboard display without delay
        triggerSoftKeyboard()

        // Fast follow-up (30ms) to guarantee keyboard display across all device/launcher configurations
        mainHandler.postDelayed({
            triggerSoftKeyboard()
        }, 30)
    }

    private fun triggerSoftKeyboard() {
        try {
            @Suppress("DEPRECATION")
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            @Suppress("DEPRECATION")
            imm?.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY)
            Log.d(TAG, "InputMethodManager.toggleSoftInput triggered instantly")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing instant soft keyboard trigger", e)
        }
    }

    private fun flattenTree(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        @Suppress("DEPRECATION")
        list.add(AccessibilityNodeInfo.obtain(node))
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            flattenTree(child, list)
            @Suppress("DEPRECATION")
            child.recycle()
        }
    }
}
