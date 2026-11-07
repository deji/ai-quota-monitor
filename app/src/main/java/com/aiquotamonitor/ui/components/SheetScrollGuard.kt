package com.aiquotamonitor.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * Nested-scroll guard for content inside an already settled [SheetValue.Expanded]
 * `ModalBottomSheet` (`APP-UX3`).
 *
 * Material 3 attaches `ConsumeSwipeWithinBottomSheetBoundsNestedScrollConnection` to the
 * sheet surface. Its ancestor pre-scroll phase clamps upward deltas to `0` at `Expanded`
 * (so the inner scroller receives them), but its post-scroll and post-fling phases always
 * start an anchored drag. When the inner `verticalScroll` reaches its end, the leftover
 * upward delta/velocity reaches that ancestor and the sheet jitters against the finger.
 *
 * The guard sits between the sheet surface and the inner scroller:
 * - [onPreScroll] never consumes, so the list always receives upward movement first.
 * - [onPostScroll] swallows only leftover upward delta while the sheet is `Expanded`.
 * - [onPostFling] swallows only leftover upward velocity while the sheet is `Expanded`.
 * - Downward deltas and velocity always pass through to the sheet so swipe-to-dismiss
 *   keeps working when the list is at scroll offset `0`.
 */
@OptIn(ExperimentalMaterial3Api::class)
class SheetScrollGuard(
    private val sheetState: SheetState,
    private val scrollState: ScrollState
) : NestedScrollConnection {

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = Offset.Zero

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset {
        if (sheetState.currentValue != SheetValue.Expanded) return Offset.Zero
        return if (available.y < 0f) Offset(0f, available.y) else Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (sheetState.currentValue != SheetValue.Expanded) return Velocity.Zero
        // Downward leftover velocity (available.y > 0f) is intentionally not consumed:
        // at scroll offset 0 (`!scrollState.canScrollBackward`) the sheet must dismiss.
        return if (available.y < 0f) Velocity(0f, available.y) else Velocity.Zero
    }

    /**
     * Exposed for the layout-level invariant: downward fling may only reach the sheet
     * once the inner list can no longer scroll backward.
     */
    internal fun isListAtTop(): Boolean = !scrollState.canScrollBackward
}

/**
 * Applies [SheetScrollGuard] immediately *before* a `verticalScroll`/`LazyColumn` modifier
 * on the scrolling body child of a `ModalBottomSheet` content `ColumnScope`. Never attach
 * this to `ModalBottomSheet`'s own `modifier` parameter, because that sits outside the
 * sheet surface connection and would steal deltas before the inner scroller sees them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Modifier.sheetContentNestedScroll(
    sheetState: SheetState,
    scrollState: ScrollState
): Modifier {
    val guard = remember(sheetState, scrollState) { SheetScrollGuard(sheetState, scrollState) }
    return this.nestedScroll(guard)
}
