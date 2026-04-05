# Feature 2: ScrubMarkerController

## Overview

iOS Health-like marker scrubbing on scrollable charts inside LazyColumn.
Tap to show marker, hold+drag to scrub along data points, scroll to dismiss.
Consumer controls marker positioning via callback (same pattern as Feature 1).

## Gesture Behavior

| Gesture | Result |
|---------|--------|
| **Tap** (< 5px movement) | Toggle marker at nearest data point |
| **Hold 200ms + drag** | Scrub marker along data, all scroll locked |
| **Horizontal swipe** (> 20px movement before 200ms) | Chart scrolls, no marker |
| **Release after scrub** | Marker stays at last position |
| **Scroll when marker visible** | Marker dismisses immediately |

## State Machine (in Modifier.kt)

```
NONE
  └─ Press → DECIDING (start 200ms delay timer)
       ├─ Movement > 20px before timer → SCROLLING
       │    ├─ If marker was active → onDismiss() + emit Release
       │    └─ Release → NONE
       ├─ Timer fires (still holding) → MARKER_SELECTION
       │    ├─ Movement > 20px → MARKER_SCRUBBING (consume all events)
       │    │    └─ Release → NONE (marker stays)
       │    └─ Release → NONE (marker stays)
       └─ Release < 5px movement (quick tap) → emit Tap → toggle marker → NONE
```

## Architecture

### Files Changed

| File | Change | Upstream Conflict Risk |
|------|--------|----------------------|
| `Modifier.kt` | DECIDING state machine before scrollable, InteractionMode enum | **CRITICAL** — upstream frequently modifies |
| `CartesianChartHost.kt` | Scroll-dismiss via consumedXDeltas, callback wiring in onInteraction, hasActiveMarker sync | **MEDIUM** |
| `CartesianChart.kt` | `allMarkerTargetXValues` cached accessor | **LOW** |
| `ScrubMarkerController.kt` | NEW — controller + rememberScrubMarkerController composable | **NEW** |

### State Ownership (Single Source of Truth)

```
markerX (CartesianChartHostImpl)  ← SINGLE SOURCE OF TRUTH for marker visibility
    │
    ├─ scrubController.hasActiveMarker = (markerX != null)
    │   Synced on every recomposition. Read by shouldShowMarker().
    │
    ├─ scrubController.isScrubbing
    │   Set by Modifier.kt delay timer. Read by scrollable(enabled=...).
    │
    └─ lastAcceptedInteraction
        Cleared on scroll-dismiss to prevent onViewportChange replay.
```

### Scroll Disable During Scrub

```kotlin
scrollable(
  enabled = scrollState.scrollEnabled,  // ALWAYS enabled — nestedScroll blocks parent
)
```

Scrollable stays **enabled** during scrubbing. `isScrollFrozen` on `VicoScrollState`
freezes the scroll position — `ScrollableState` claims deltas (nestedScroll sees them
as consumed → parent LazyColumn blocked) but position doesn't move. Move events are
still consumed in MARKER_SCRUBBING for marker position tracking.

Better than disabling scrollable: no race condition window, nestedScroll stays active.

### Scroll-Dismiss Flow

```
1. User has marker visible (markerX != null)
2. User starts new scroll gesture → Modifier.kt enters SCROLLING
3. If hasActiveMarker → onDismiss() + emit Release → markerX = null
4. ALSO: consumedXDeltas LaunchedEffect fires → markerX = null + lastAcceptedInteraction = null
5. onDismiss() is idempotent — safe if both paths fire
```

### Marker Index Callback

```kotlin
// Consumer provides custom logic (replaces onChartClick):
val scrubController = rememberScrubMarkerController(
  scrollState = scrollState,
  onMarkerIndexChanged = { clickX, allTargetXValues ->
    // clickX = null means dismiss
    if (clickX == null) {
      viewModel.handleIntent(GraphIntent.UpdateMarkerIndex(null))
      return@rememberScrubMarkerController null
    }
    // Your logic — same as getTargetPoints()
    val result = getTargetPoints(visibleLabels, allTargetXValues, clickX, segment)
    val markerIndex = result.firstOrNull()
    viewModel.handleIntent(GraphIntent.UpdateMarkerIndex(markerIndex))
    markerIndex  // return to vico — marker shows here
  }
)

// If callback not provided → vico's internal nearest-target logic used as fallback
```

### Key Implementation Details

1. **rememberUpdatedState** for callback — keeps lambda fresh across recompositions
   while controller instance stays stable (keyed on delayMs only)

2. **Idempotent onDismiss()** — guards on `hasActiveMarker || isScrubbing` to prevent
   double-notification when both Modifier.kt and consumedXDeltas dismiss paths fire

3. **allMarkerTargetXValues cached** — only rebuilds list when `_markerTargets.size` changes.
   Prevents per-scrub-move allocation.

4. **TAP_SLOP = 5px** — distinguishes tap from micro-scroll. Prevents scroll gestures
   from being misread as taps.

5. **MOVEMENT_THRESHOLD = 20px** — distinguishes scroll from hold. Movement below this
   during DECIDING state lets the delay timer fire (→ scrub mode).

6. **detectTapGestures SKIPPED** for ScrubMarkerController — the state machine in
   Modifier.kt handles tap/long-press internally via delay timer. The upstream
   detectTapGestures is only used for non-scrub controllers.

7. **shouldAcceptInteraction accepts empty targets** — Tap/LongPress return `true` even
   when `targets` is empty. This matches v3's `onChartClick` which always passed the click
   position regardless of nearby data points. The consumer callback handles empty windows
   (e.g., snap to nearest label via `getTargetPoints`). CartesianChartHost also passes
   `clickX` to the callback even when `narrowedTargets` is empty.

## Usage API

```kotlin
val scrollState = rememberVicoScrollState()
val scrubController = rememberScrubMarkerController(
  scrollState = scrollState,
  delayMs = 200L,
  onMarkerIndexChanged = { clickX, targets -> ... }
)

CartesianChartHost(
  chart = rememberCartesianChart(
    rememberLineCartesianLayer(),
    marker = rememberMarker(),
    markerController = scrubController,
  ),
  scrollState = scrollState,
)
```

## Upstream Merge Guide

1. **Modifier.kt** (highest risk):
   - Our changes add ~180 lines of state machine inside `pointerInput` block
   - The default upstream path is preserved in the `else` branch (lines 222-253)
   - Look for `if (scrubController != null)` to find our code
   - If upstream restructures pointer input, the state machine logic must be ported

2. **CartesianChartHost.kt** (medium risk):
   - Scroll-dismiss LaunchedEffect at lines 170-179
   - Callback wiring in onInteraction at lines 236-266
   - `hasActiveMarker` sync at line 167
   - Look for `ScrubMarkerController` references

3. **CartesianChart.kt** (low risk):
   - `allMarkerTargetXValues` accessor at lines 153-163
   - Additive change, no conflict
