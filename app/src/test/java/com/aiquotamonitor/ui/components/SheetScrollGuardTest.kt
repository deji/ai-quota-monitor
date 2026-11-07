package com.aiquotamonitor.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Locks the `APP-UX3` nested-scroll contract for [SheetScrollGuard].
 *
 * The guard must leave ancestor pre-scroll untouched (otherwise the sheet steals
 * upward deltas and the list freezes), consume only leftover upward motion once the
 * sheet is `Expanded`, and always let downward motion reach the sheet to dismiss it.
 */
@OptIn(ExperimentalMaterial3Api::class)
class SheetScrollGuardTest {

    private lateinit var sheetState: SheetState
    private lateinit var scrollState: ScrollState
    private lateinit var guard: SheetScrollGuard

    @Before
    fun setUp() {
        sheetState = SheetState(
            true,
            { 0f },
            { 0f },
            SheetValue.Expanded,
            { true },
            false
        )
        // A detached ScrollState starts at offset 0 with no scroll range.
        scrollState = ScrollState(initial = 0)
        guard = SheetScrollGuard(sheetState, scrollState)
    }

    @Test
    fun `sheet starts expanded for the contract under test`() {
        assertEquals(SheetValue.Expanded, sheetState.currentValue)
        assertTrue(guard.isListAtTop())
    }

    @Test
    fun `onPreScroll never consumes upward delta`() {
        val consumed = guard.onPreScroll(
            available = Offset(0f, -120f),
            source = NestedScrollSource.UserInput
        )
        assertEquals(Offset.Zero, consumed)
    }

    @Test
    fun `onPreScroll never consumes downward delta`() {
        val consumed = guard.onPreScroll(
            available = Offset(0f, 120f),
            source = NestedScrollSource.UserInput
        )
        assertEquals(Offset.Zero, consumed)
    }

    @Test
    fun `onPostScroll consumes leftover upward delta while expanded`() {
        val consumed = guard.onPostScroll(
            consumed = Offset(0f, 80f),
            available = Offset(0f, -48f),
            source = NestedScrollSource.UserInput
        )
        assertEquals(Offset(0f, -48f), consumed)
    }

    @Test
    fun `onPostScroll passes downward delta through to the sheet`() {
        val consumed = guard.onPostScroll(
            consumed = Offset.Zero,
            available = Offset(0f, 48f),
            source = NestedScrollSource.UserInput
        )
        assertEquals(Offset.Zero, consumed)
    }

    @Test
    fun `onPostFling consumes leftover upward velocity while expanded`() = runBlocking {
        val consumed = guard.onPostFling(
            consumed = Velocity.Zero,
            available = Velocity(0f, -900f)
        )
        assertEquals(Velocity(0f, -900f), consumed)
    }

    @Test
    fun `onPostFling leaves downward velocity for swipe to dismiss`() = runBlocking {
        val consumed = guard.onPostFling(
            consumed = Velocity.Zero,
            available = Velocity(0f, 900f)
        )
        assertEquals(Velocity.Zero, consumed)
    }

    @Test
    fun `guard ignores upward motion once the sheet is not expanded`() {
        // A fresh state that starts collapsed represents a sheet that is still being
        // expanded; upward deltas must reach the sheet so it can settle at Expanded.
        val collapsedState = SheetState(
            false,
            { 0f },
            { 0f },
            SheetValue.PartiallyExpanded,
            { true },
            false
        )
        val collapsedGuard = SheetScrollGuard(collapsedState, ScrollState(initial = 0))
        val consumed = collapsedGuard.onPostScroll(
            consumed = Offset.Zero,
            available = Offset(0f, -48f),
            source = NestedScrollSource.UserInput
        )
        assertEquals(Offset.Zero, consumed)
    }
}
