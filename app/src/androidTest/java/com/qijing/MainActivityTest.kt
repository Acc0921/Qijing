package com.qijing

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
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
        composeRule.onNodeWithTag("home").performScrollToIndex(6)
        composeRule.onNodeWithTag("module-M8").performClick()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            composeRule.onNodeWithTag("fps-unsupported").assertIsDisplayed()
        } else {
            composeRule.onNodeWithTag("fps-start").assertIsDisplayed()
            composeRule.onNodeWithTag("fps-stop").assertIsDisplayed()
        }
    }

    @Test
    fun moduleNavigationOpensSceneEditor() {
        composeRule.onNodeWithTag("home").performScrollToIndex(3)
        composeRule.onNodeWithTag("module-M3").performClick()
        composeRule.onNodeWithTag("scene-save").performScrollTo().assertIsDisplayed()
    }
}
