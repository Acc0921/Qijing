package com.qijing

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
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
        composeRule.onNodeWithTag("module-M3").performClick()
        composeRule.onNodeWithTag("scene-list").assertIsDisplayed()
    }

    @Test
    fun selectingAppCarriesObjectIntoSceneChain() {
        composeRule.onNodeWithTag("module-M2").performClick()
        composeRule.onNodeWithTag("apps-all-apps").performClick()
        composeRule.onNodeWithTag("app-list").performScrollToNode(hasTestTag("app-row-com.android.settings"))
        composeRule.onNodeWithTag("app-row-com.android.settings").performClick()
        composeRule.onNodeWithTag("scene-chain-app").assertExists()
        composeRule.onNodeWithTag("scene-list").performScrollToNode(hasTestTag("scene-chain"))
        composeRule.onNodeWithTag("scene-chain").assertIsDisplayed()
    }

    @Test
    fun dryRunSceneRehearsalRequiresIntentAndEnablesOnlyPreview() {
        composeRule.onNodeWithTag("module-M2").performClick()
        composeRule.onNodeWithTag("apps-all-apps").performClick()
        composeRule.onNodeWithTag("app-list").performScrollToNode(hasTestTag("app-row-com.android.settings"))
        composeRule.onNodeWithTag("app-row-com.android.settings").performClick()
        composeRule.onNodeWithTag("scene-list").performScrollToNode(hasTestTag("scene-intent-custom"))
        composeRule.onNodeWithTag("scene-intent-custom").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("scene-swappiness").performScrollTo().performTextInput("60")
        composeRule.onNodeWithTag("scene-list").performScrollToNode(hasTestTag("scene-rehearse"))
        composeRule.onNodeWithTag("scene-rehearse").performClick()
        composeRule.onNodeWithTag("scene-list").performScrollToNode(hasTestTag("scene-preflight-ready"))
        composeRule.onNodeWithTag("scene-preflight-ready").assertIsDisplayed()
        composeRule.onNodeWithTag("scene-list").performScrollToNode(hasTestTag("scene-enable"))
        composeRule.onNodeWithTag("scene-enable").performClick()
        composeRule.onNodeWithTag("scene-save-result").assertExists()
    }
}
