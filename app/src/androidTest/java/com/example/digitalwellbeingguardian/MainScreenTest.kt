package com.example.digitalwellbeingguardian

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun showsMainControlsAndTabs() {
        composeRule.onNodeWithText("Start Monitoring").assertIsDisplayed()
        composeRule.onNodeWithText("Stop").assertIsDisplayed()
        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithText("Tracked Apps").assertIsDisplayed()
    }

    @Test
    fun canSwitchToTrackedAppsTab() {
        composeRule.onNodeWithText("Tracked Apps").performClick()
        composeRule.onNodeWithText("Add custom app").assertIsDisplayed()
        composeRule.onNodeWithText("Tracked apps").assertIsDisplayed()
    }
}
