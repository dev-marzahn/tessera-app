package dev.marzahn.sankaku.features.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/** The fields both platforms share carry the content types Android turns into autofill hints. */
@OptIn(ExperimentalTestApi::class)
class SignInAutofillTest {
  private fun hasContentType(expected: ContentType) =
    SemanticsMatcher("has content type $expected") {
      it.config.getOrNull(SemanticsProperties.ContentType) == expected
    }

  @Test
  fun loginFieldIsAUsernameAndPasswordFieldIsAPassword() = runComposeUiTest {
    setContent {
      Column { SignInFields(TextFieldState(), TextFieldState(), enabled = true) }
    }

    onNodeWithTag(LoginFieldTag).assert(hasContentType(ContentType.Username + ContentType.EmailAddress))
    onNodeWithTag(PasswordFieldTag).assert(hasContentType(ContentType.Password))
  }
}
