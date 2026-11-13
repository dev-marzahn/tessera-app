package dev.marzahn.sankaku.features.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The action that opened sign-in runs once when it succeeds, and never once the dialog closed another way. */
class SignInControllerTest {
  @Test
  fun theReasonShowsWhileOpen() {
    val controller = SignInController()

    controller.open("to see Following") {}

    assertTrue(controller.isOpen.value)
    assertEquals("to see Following", controller.reason.value)
  }

  @Test
  fun signingInFromTheSidebarHasNoReason() {
    val controller = SignInController()

    controller.open()

    assertTrue(controller.isOpen.value)
    assertNull(controller.reason.value)
  }

  @Test
  fun successRunsThePendingActionOnce() {
    val controller = SignInController()
    var runs = 0
    controller.open("to favourite") { runs++ }

    controller.succeeded()
    controller.succeeded()

    assertEquals(1, runs)
    assertFalse(controller.isOpen.value)
    assertNull(controller.reason.value)
  }

  @Test
  fun closingDropsThePendingAction() {
    val controller = SignInController()
    var runs = 0
    controller.open("to vote") { runs++ }

    controller.close()
    controller.succeeded()

    assertEquals(0, runs)
    assertNull(controller.reason.value)
  }

  @Test
  fun aLaterOpenReplacesTheEarlierAction() {
    val controller = SignInController()
    var first = 0
    controller.open("to vote") { first++ }

    // The session-expired snackbar, say: a plain sign-in that carries nothing.
    controller.open()
    controller.succeeded()

    assertEquals(0, first)
  }
}
