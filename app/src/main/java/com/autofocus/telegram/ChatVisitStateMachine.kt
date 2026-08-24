package com.autofocus.telegram

enum class ChatVisitState {
    NOT_IN_CHAT,
    WAITING_FOR_INPUT,
    DONE_FOR_THIS_VISIT,
    ABANDONED
}

sealed class VisitCheckResult {
    object DoNothing : VisitCheckResult()
    object ShouldSearchAndTrigger : VisitCheckResult()
}

class ChatVisitStateMachine(
    val maxRetryDurationNano: Long = DEFAULT_MAX_RETRY_DURATION_NANO
) {
    companion object {
        const val DEFAULT_MAX_RETRY_DURATION_NANO = 2_000_000_000L // 2 seconds
    }

    var currentState: ChatVisitState = ChatVisitState.NOT_IN_CHAT
        private set

    var activeConversationTitle: String? = null
        private set

    var visitStartTimeNano: Long = 0L
        private set

    /**
     * Called on accessibility events or scheduled retries.
     * Evaluates screen state and transitions state machine.
     *
     * @param isChatScreen whether the root node represents a chat screen
     * @param conversationTitle optional extracted title (e.g. contact/group name)
     * @param currentTimeNano current timestamp in nanoseconds
     * @return VisitCheckResult indicating whether to search for input and trigger action
     */
    fun evaluate(
        isChatScreen: Boolean,
        conversationTitle: String?,
        currentTimeNano: Long
    ): VisitCheckResult {
        if (!isChatScreen) {
            if (currentState != ChatVisitState.NOT_IN_CHAT) {
                resetToNotInChat()
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
        }

        if (isNewVisit) {
            currentState = ChatVisitState.WAITING_FOR_INPUT
            activeConversationTitle = conversationTitle
            visitStartTimeNano = currentTimeNano
            return VisitCheckResult.ShouldSearchAndTrigger
        }

        // Same visit handling based on current state
        return when (currentState) {
            ChatVisitState.DONE_FOR_THIS_VISIT -> VisitCheckResult.DoNothing
            ChatVisitState.ABANDONED -> VisitCheckResult.DoNothing
            ChatVisitState.NOT_IN_CHAT -> VisitCheckResult.DoNothing // Handled above
            ChatVisitState.WAITING_FOR_INPUT -> {
                if (currentTimeNano - visitStartTimeNano > maxRetryDurationNano) {
                    currentState = ChatVisitState.ABANDONED
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
        if (currentState == ChatVisitState.WAITING_FOR_INPUT) {
            currentState = ChatVisitState.DONE_FOR_THIS_VISIT
        }
    }

    /**
     * Force timeout check for scheduled fallback timers.
     */
    fun checkTimeout(currentTimeNano: Long): Boolean {
        if (currentState == ChatVisitState.WAITING_FOR_INPUT) {
            if (currentTimeNano - visitStartTimeNano > maxRetryDurationNano) {
                currentState = ChatVisitState.ABANDONED
                return true
            }
        }
        return false
    }

    fun resetToNotInChat() {
        currentState = ChatVisitState.NOT_IN_CHAT
        activeConversationTitle = null
        visitStartTimeNano = 0L
    }
}
