package dev.marzahn.sankaku.features.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.marzahn.sankaku.NoAppForLink
import dev.marzahn.sankaku.OpenUrlResult
import kotlin.test.Test
import kotlin.test.assertEquals

/** The sign-in form's three states from UX review #126, finding 11. */
@OptIn(ExperimentalTestApi::class)
class SignInFormTest {
  // The semantics tree carries the raw text either way, so whether it is masked is checked on screen, not here.
  @Test
  fun theEyeTogglesItsLabelAndStartsHidden() = runComposeUiTest {
    setContent { Column { SignInFields(TextFieldState(), TextFieldState("hunter22"), enabled = true) } }

    onNodeWithTag(ShowPasswordTag).assertContentDescriptionEquals("Show password")
    onNodeWithTag(ShowPasswordTag).performClick()
    onNodeWithTag(ShowPasswordTag).assertContentDescriptionEquals("Hide password")
    onNodeWithTag(ShowPasswordTag).performClick()
    onNodeWithTag(ShowPasswordTag).assertContentDescriptionEquals("Show password")
  }

  @Test
  fun aRejectionShowsUnderPasswordOnly() = runComposeUiTest {
    var rejection: String? by mutableStateOf(null)
    setContent { Column { SignInFields(TextFieldState(), TextFieldState(), enabled = true, rejection = rejection) } }

    onAllNodesWithText("Wrong username or password.").assertCountEquals(0)

    rejection = "Wrong username or password."
    onNodeWithText("Wrong username or password.").assertIsDisplayed()
  }

  @Test
  fun signInIsDisabledOnlyUntilBothFieldsHaveText() = runComposeUiTest {
    var enabled by mutableStateOf(false)
    setContent { SignInButton(enabled = enabled, submitting = false, onClick = {}) }

    onNodeWithTag(SignInButtonTag).assertIsNotEnabled()
    enabled = true
    onNodeWithTag(SignInButtonTag).assertIsEnabled()
  }

  // Decision 15 in #196: sign-up is Sankaku's own page in the browser; the app registers nothing itself.
  @Test
  fun createAnAccountOpensTheSignUpPageOnly() = runComposeUiTest {
    val opened = mutableListOf<String>()
    setContent { CreateAccountLink(open = { opened += it; OpenUrlResult.Opened }) }

    onNodeWithText("Create an account").assertIsDisplayed()
    assertEquals(emptyList(), opened)
    onNodeWithTag(CreateAccountTag).performClick()
    assertEquals(listOf("https://login.sankakucomplex.com/registration"), opened)
    onAllNodesWithText(NoAppForLink).assertCountEquals(0)
  }

  // #358: the dialog covers the snackbar, so the link says it under itself.
  @Test
  fun createAnAccountSaysWhenNoAppCanOpenIt() = runComposeUiTest {
    setContent { CreateAccountLink(open = { OpenUrlResult.Failed }) }

    onAllNodesWithText(NoAppForLink).assertCountEquals(0)
    onNodeWithTag(CreateAccountTag).performClick()
    onNodeWithText(NoAppForLink).assertIsDisplayed()
  }
}
