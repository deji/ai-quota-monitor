package com.aiquotamonitor.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aiquotamonitor.data.local.SeedData
import com.aiquotamonitor.domain.model.QuotaPolarity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Locks the `APP-UX3` layout behavior on a device: upward swipes scroll the bounded
 * bucket list while the `Expanded` sheet anchor and the pinned header stay put.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class ModelFilterSheetScrollTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun upwardSwipesScrollBucketListWithoutMovingSheetOrHeader() {
        val snapshot = SeedData.createSeedSnapshot()
        lateinit var sheetState: SheetState

        composeRule.setContent {
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            MaterialTheme {
                ModelFilterSheet(
                    snapshot = snapshot,
                    hiddenBucketIds = emptySet(),
                    pinnedBucketId = null,
                    polarity = QuotaPolarity.REMAINING,
                    onToggleVisibility = {},
                    onSetHiddenBuckets = {},
                    onSetPinnedBucket = {},
                    onDismiss = {},
                    sheetState = sheetState
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Customize Visible Models").assertIsDisplayed()
        composeRule.onNodeWithText("Show All").assertIsDisplayed()
        assertEquals(SheetValue.Expanded, sheetState.currentValue)
        val headerTopBefore = composeRule
            .onNodeWithText("Customize Visible Models")
            .fetchSemanticsNode()
            .boundsInRoot
            .top

        repeat(4) {
            composeRule.onNodeWithTag(MODEL_FILTER_SCROLL_BODY_TAG).performTouchInput { swipeUp() }
            composeRule.waitForIdle()
            assertEquals(
                "Sheet must stay Expanded while the list scrolls (APP-UX3)",
                SheetValue.Expanded,
                sheetState.currentValue
            )
        }

        composeRule.onNodeWithText("GROK BUILD CLI").assertIsDisplayed()
        composeRule.onNodeWithText("Weekly Unified Credits").assertIsDisplayed()

        val headerTopAfter = composeRule
            .onNodeWithText("Customize Visible Models")
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        assertEquals(
            "Pinned header must not move while the bucket list scrolls (APP-UX3)",
            headerTopBefore,
            headerTopAfter,
            1f
        )
    }
}
