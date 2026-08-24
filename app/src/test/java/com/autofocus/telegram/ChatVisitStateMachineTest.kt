package com.autofocus.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatVisitStateMachineTest {

    private lateinit var stateMachine: ChatVisitStateMachine

    @Before
    fun setUp() {
        stateMachine = ChatVisitStateMachine(maxRetryDurationNano = 2_000_000_000L) // 2.0 seconds
    }

    @Test
    fun testInitialState() {
        assertEquals(ChatVisitState.NOT_IN_CHAT, stateMachine.currentState)
    }

    @Test
    fun testColdStartOpeningChat() {
        val t0 = 1000L
        // Transition from non-chat to chat "Alice"
        val res = stateMachine.evaluate(isChatScreen = true, conversationTitle = "Alice", currentTimeNano = t0)

        assertTrue(res is VisitCheckResult.ShouldSearchAndTrigger)
        assertEquals(ChatVisitState.WAITING_FOR_INPUT, stateMachine.currentState)
        assertEquals("Alice", stateMachine.activeConversationTitle)

        // Mark trigger successful
        stateMachine.markActionTriggered()
        assertEquals(ChatVisitState.DONE_FOR_THIS_VISIT, stateMachine.currentState)
    }

    @Test
    fun testManualKeyboardDismissalDoesNotReTrigger() {
        val t0 = 1000L
        // Chat opened and action triggered
        stateMachine.evaluate(isChatScreen = true, conversationTitle = "Alice", currentTimeNano = t0)
        stateMachine.markActionTriggered()
        assertEquals(ChatVisitState.DONE_FOR_THIS_VISIT, stateMachine.currentState)

        // Subsequent content changes / UI updates in the same chat (e.g. user manually dismisses keyboard)
        val t1 = t0 + 500_000_000L // +500ms
        val res1 = stateMachine.evaluate(isChatScreen = true, conversationTitle = "Alice", currentTimeNano = t1)
        assertEquals(VisitCheckResult.DoNothing, res1)
        assertEquals(ChatVisitState.DONE_FOR_THIS_VISIT, stateMachine.currentState)

        val t2 = t0 + 1000_000_000L // +1s
        val res2 = stateMachine.evaluate(isChatScreen = true, conversationTitle = "Alice", currentTimeNano = t2)
        assertEquals(VisitCheckResult.DoNothing, res2)
        assertEquals(ChatVisitState.DONE_FOR_THIS_VISIT, stateMachine.currentState)
    }

    @Test
    fun testBackingOutToChatListAndReEntering() {
        val t0 = 1000L
        // 1. Enter Chat Alice
        stateMachine.evaluate(isChatScreen = true, conversationTitle = "Alice", currentTimeNano = t0)
        stateMachine.markActionTriggered()
        assertEquals(ChatVisitState.DONE_FOR_THIS_VISIT, stateMachine.currentState)

        // 2. Back out to Chat List (isChatScreen = false) with reason
        val resLeave = stateMachine.evaluate(isChatScreen = false, conversationTitle = null, currentTimeNano = t0 + 100, notInChatReason = "dialogs_recycler_detected")
        assertEquals(VisitCheckResult.DoNothing, resLeave)
        assertEquals(ChatVisitState.NOT_IN_CHAT, stateMachine.currentState)

        // 3. Re-enter Chat Alice (same title, fresh visit after NOT_IN_CHAT)
        val resReenter = stateMachine.evaluate(isChatScreen = true, conversationTitle = "Alice", currentTimeNano = t0 + 200)
        assertTrue(resReenter is VisitCheckResult.ShouldSearchAndTrigger)
        assertEquals(ChatVisitState.WAITING_FOR_INPUT, stateMachine.currentState)
    }

    @Test
    fun testNonTelegramPackageReset() {
        val t0 = 1000L
        // 1. Enter Chat Alice and complete action
        stateMachine.evaluate(isChatScreen = true, conversationTitle = "Alice", currentTimeNano = t0)
        stateMachine.markActionTriggered()
        assertEquals(ChatVisitState.DONE_FOR_THIS_VISIT, stateMachine.currentState)

        // 2. User switches to Home Screen or non-Telegram package
        stateMachine.resetToNotInChat(reason = "non_telegram_package (pkg=com.android.launcher)")
        assertEquals(ChatVisitState.NOT_IN_CHAT, stateMachine.currentState)

        // 3. Return to Telegram in the same chat (Alice)
        val resReenter = stateMachine.evaluate(isChatScreen = true, conversationTitle = "Alice", currentTimeNano = t0 + 500)
        assertTrue(resReenter is VisitCheckResult.ShouldSearchAndTrigger)
        assertEquals(ChatVisitState.WAITING_FOR_INPUT, stateMachine.currentState)
    }

    @Test
    fun testInputNodeAbsentCausesNotInChatReset() {
        val t0 = 1000L
        // 1. Enter Chat Alice
        stateMachine.evaluate(isChatScreen = true, conversationTitle = "Alice", currentTimeNano = t0)
        stateMachine.markActionTriggered()

        // 2. User opens Settings or Contact Info screen inside Telegram (no input node)
        val resSettings = stateMachine.evaluate(isChatScreen = false, conversationTitle = null, currentTimeNano = t0 + 100, notInChatReason = "input_node_absent")
        assertEquals(VisitCheckResult.DoNothing, resSettings)
        assertEquals(ChatVisitState.NOT_IN_CHAT, stateMachine.currentState)

        // 3. Return to Chat Alice
        val resReturn = stateMachine.evaluate(isChatScreen = true, conversationTitle = "Alice", currentTimeNano = t0 + 200)
        assertTrue(resReturn is VisitCheckResult.ShouldSearchAndTrigger)
        assertEquals(ChatVisitState.WAITING_FOR_INPUT, stateMachine.currentState)
    }

    @Test
    fun testSwitchingBetweenChatsInQuickSuccession() {
        val t0 = 1000L
        // 1. Open Chat Alice
        stateMachine.evaluate(isChatScreen = true, conversationTitle = "Alice", currentTimeNano = t0)
        stateMachine.markActionTriggered()

        // 2. Switch directly to Chat Bob without explicit non-chat event (title changes to Bob)
        val resBob = stateMachine.evaluate(isChatScreen = true, conversationTitle = "Bob", currentTimeNano = t0 + 50)
        assertTrue(resBob is VisitCheckResult.ShouldSearchAndTrigger)
        assertEquals(ChatVisitState.WAITING_FOR_INPUT, stateMachine.currentState)
        assertEquals("Bob", stateMachine.activeConversationTitle)
    }

    @Test
    fun testExhaustingRetryWindowMovesToAbandoned() {
        val t0 = 1_000_000_000L
        // Enter chat screen where input node is slow to appear
        stateMachine.evaluate(isChatScreen = true, conversationTitle = "SlowChat", currentTimeNano = t0)
        assertEquals(ChatVisitState.WAITING_FOR_INPUT, stateMachine.currentState)

        // Retries within 2.0s
        val t1 = t0 + 1_000_000_000L // +1.0s
        val res1 = stateMachine.evaluate(isChatScreen = true, conversationTitle = "SlowChat", currentTimeNano = t1)
        assertTrue(res1 is VisitCheckResult.ShouldSearchAndTrigger)

        // Retry beyond 2.0s limit (+2.5s)
        val t2 = t0 + 2_500_000_000L
        val res2 = stateMachine.evaluate(isChatScreen = true, conversationTitle = "SlowChat", currentTimeNano = t2)
        assertEquals(VisitCheckResult.DoNothing, res2)
        assertEquals(ChatVisitState.ABANDONED, stateMachine.currentState)

        // Further events in same visit remain ABANDONED
        val res3 = stateMachine.evaluate(isChatScreen = true, conversationTitle = "SlowChat", currentTimeNano = t2 + 100)
        assertEquals(VisitCheckResult.DoNothing, res3)
        assertEquals(ChatVisitState.ABANDONED, stateMachine.currentState)
    }
}
