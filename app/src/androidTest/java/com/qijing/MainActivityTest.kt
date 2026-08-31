package com.qijing

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstOpenShowsHomeAndServiceControl() {
        composeRule.onNodeWithTag("home").assertIsDisplayed()
        composeRule.onNodeWithTag("service-toggle").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun moduleNavigationOpensFpsPage() {
        composeRule.onNodeWithTag("module-M8").performScrollTo().performClick()
        composeRule.onNodeWithTag("fps-start").assertIsDisplayed()
        composeRule.onNodeWithTag("fps-stop").assertIsDisplayed()
    }

    @Test
    fun moduleNavigationOpensSceneEditor() {
        composeRule.onNodeWithTag("module-M3").performScrollTo().performClick()
        composeRule.onNodeWithTag("scene-save").assertIsDisplayed()
    }
}
