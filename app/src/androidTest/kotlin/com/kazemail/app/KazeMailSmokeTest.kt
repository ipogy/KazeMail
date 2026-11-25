package com.kazemail.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class KazeMailSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun emptyInboxRendersAddAccountCallToAction() {
        composeRule.onNodeWithText("KazeMail へようこそ").assertExists()
        composeRule.onNodeWithText("アカウントを追加（パスワード / OAuth2）").assertExists()
    }
}
