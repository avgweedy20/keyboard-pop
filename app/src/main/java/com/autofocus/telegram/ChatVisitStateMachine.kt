package com.autofocus.telegram

enum class ChatVisitState {
    NOT_IN_CHAT,
    PENDING_CHAT_OPEN,
    WAITING_FOR_INPUT,
    DONE_FOR_THIS_VISIT,
    ABANDONED
}

sealed class VisitCheckResult {
    object DoNothing : VisitCheckResult()
    object ShouldSearchAndTrigger : VisitCheckResult()
}

class ChatVisitStateMachine(
    val maxRetryDurationNano: Long = DEFAULT_MAX_RETRY_DURATION_NANO,
    val pendingTimeoutNano: Long = DEFAULT_PENDING_TIMEOUT_NANO
) {
    companion object {
        const val DEFAULT_MAX_RETRY_DURATION_NANO = 2_000_000_000L // 2 seconds
        const val DEFAULT_PENDING_TIMEOUT_NANO = 400_000_000L // 400 ms
    }

    var currentState: ChatVisitState = ChatVisitState.NOT_IN_CHAT
        private set

    var activeConversationTitle: String? = null
        private set

    var visitStartTimeNano: Long = 0L
        private set

    /**
     * Called when a chat-list click event is detected in dialogs_recycler.
     * Immediately enters PENDING_CHAT_OPEN state.
     */
    fun onChatListClicked(currentTimeNano: Long) {
        val prevTitle = activeConversationTitle
        activeConversationTitle = null
        visitStartTimeNano = currentTimeNano
        if (currentState == ChatVisitState.PENDING_CHAT_OPEN) {
            android.util.Log.i(
                "ChatVisitStateMachine",
                "[STATE CHANGE] PENDING_CHAT_OPEN -> PENDING_CHAT_OPEN (reason=rapid_retap_click)"
            )
        } else {
            transitionTo(ChatVisitState.PENDING_CHAT_OPEN, reason = "chat_list_clicked (prevTitle='$prevTitle')")
        }
    }

    /**
     * Called on accessibility events or scheduled retries.
     * Evaluates screen state and transitions state machine.
     *
     * @param isChatScreen whether the root node represents a chat screen
     * @param conversationTitle optional extracted title (e.g. contact/group name)
     * @param currentTimeNano current timestamp in nanoseconds
     * @param notInChatReason reason string if isChatScreen is false
     * @return VisitCheckResult indicating whether to search for input and trigger action
     */
    fun evaluate(
        isChatScreen: Boolean,
        conversationTitle: String?,
        currentTimeNano: Long,
        notInChatReason: String = "not_chat_screen"
    ): VisitCheckResult {
        if (currentState == ChatVisitState.PENDING_CHAT_OPEN) {
            if (currentTimeNano - visitStartTimeNano > pendingTimeoutNano) {
                resetToNotInChat("pending_timeout_exceeded")
                return VisitCheckResult.DoNothing
            }
            if (isChatScreen) {
                activeConversationTitle = conversationTitle
                transitionTo(
                    ChatVisitState.WAITING_FOR_INPUT,
                    reason = "chat_screen_confirmed (title='$conversationTitle')"
                )
                return VisitCheckResult.ShouldSearchAndTrigger
            }
            return VisitCheckResult.DoNothing
        }

        if (!isChatScreen) {
            if (currentState != ChatVisitState.NOT_IN_CHAT) {
                resetToNotInChat(notInChatReason)
            }
            return VisitCheckResult.DoNothing
        }

        // We are on a chat screen. Determine if this is a new visit or same visit.
        val isNewVisit = when (currentState) {
            ChatVisitState.NOT_IN_CHAT -> true
            ChatVisitState.WAITING_FOR_INPUT,
            ChatVisitState.DONE_FOR_THIS_VISIT,
            ChatVisitState.ABANDONED -> {
                // If conversation title is present and differs from active title, it's a new visit
                conversationTitle != null &&
                        activeConversationTitle != null &&
                        conversationTitle != activeConversationTitle
            }
            ChatVisitState.PENDING_CHAT_OPEN -> false // Handled above
        }

        if (isNewVisit) {
            val prevTitle = activeConversationTitle
            activeConversationTitle = conversationTitle
            visitStartTimeNano = currentTimeNano
            transitionTo(ChatVisitState.WAITING_FOR_INPUT, reason = "new_visit (prevTitle='$prevTitle', newTitle='$conversationTitle')")
            return VisitCheckResult.ShouldSearchAndTrigger
        }

        // Same visit handling based on current state
        return when (currentState) {
            ChatVisitState.DONE_FOR_THIS_VISIT -> VisitCheckResult.DoNothing
            ChatVisitState.ABANDONED -> VisitCheckResult.DoNothing
            ChatVisitState.NOT_IN_CHAT -> VisitCheckResult.DoNothing // Handled above
            ChatVisitState.PENDING_CHAT_OPEN -> VisitCheckResult.DoNothing // Handled above
            ChatVisitState.WAITING_FOR_INPUT -> {
                if (currentTimeNano - visitStartTimeNano > maxRetryDurationNano) {
                    transitionTo(ChatVisitState.ABANDONED, reason = "retry_timeout_exceeded")
                    VisitCheckResult.DoNothing
                } else {
                    VisitCheckResult.ShouldSearchAndTrigger
                }
            }
        }
    }

    /**
     * Call when the input node is successfully found and the focus/keyboard action has been sent.
     */
    fun markActionTriggered() {
        if (currentState == ChatVisitState.WAITING_FOR_INPUT || currentState == ChatVisitState.PENDING_CHAT_OPEN) {
            transitionTo(ChatVisitState.DONE_FOR_THIS_VISIT, reason = "action_triggered")
        }
    }

    /**
     * Force timeout check for scheduled fallback timers.
     */
    fun checkTimeout(currentTimeNano: Long): Boolean {
        if (currentState == ChatVisitState.PENDING_CHAT_OPEN) {
            if (currentTimeNano - visitStartTimeNano > pendingTimeoutNano) {
                resetToNotInChat("pending_timeout_exceeded")
                return true
            }
        } else if (currentState == ChatVisitState.WAITING_FOR_INPUT) {
            if (currentTimeNano - visitStartTimeNano > maxRetryDurationNano) {
                transitionTo(ChatVisitState.ABANDONED, reason = "retry_timeout_exceeded")
                return true
            }
        }
        return false
    }

    fun resetToNotInChat(reason: String = "manual_reset") {
        val prevTitle = activeConversationTitle
        activeConversationTitle = null
        visitStartTimeNano = 0L
        if (currentState != ChatVisitState.NOT_IN_CHAT) {
            transitionTo(ChatVisitState.NOT_IN_CHAT, reason = "$reason (prevTitle='$prevTitle')")
        }
    }

    private fun transitionTo(newState: ChatVisitState, reason: String) {
        val oldState = currentState
        if (oldState != newState) {
            currentState = newState
            android.util.Log.i(
                "ChatVisitStateMachine",
                "[STATE CHANGE] $oldState -> $newState (reason=$reason)"
            )
        }
    }
}
