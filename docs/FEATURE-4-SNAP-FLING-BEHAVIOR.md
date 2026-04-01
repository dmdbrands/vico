# Feature 4: Snap Fling Behavior

## Overview

Custom snap/fling behavior for scrollable charts. After drag or fling, the chart snaps
to a position determined by a consumer callback. Uses physics-based projection for
natural fling distance. Supports `scrollPaddingXStep` to position snap targets at a
padding offset from the chart edge (matching `xWithPadding` initial scroll).

## Gesture Types

| Gesture | Behavior |
|---------|----------|
| **Drag release** (velocity < 500) | `isDrag = true` — callback snaps to nearest label |
| **Fling** (velocity >= 500) | `isDrag = false` — callback receives projected landing position, snaps to nearest window boundary |

## Physics-Based Projection

Instead of arbitrary velocity thresholds, the fling distance is computed from decay physics:
```
projectedX = currentX + velocity / (frictionMultiplier * 4.5)
```
The callback receives BOTH `currentXLabel` and `projectedXLabel` — snap to the nearest
window boundary from the projected position, clamped to a max window count.

## Callback Signature

```kotlin
snapToLabel: (
  currentXLabel: Double?,     // X at visible window start
  projectedXLabel: Double?,   // WHERE decay would naturally settle
  isDrag: Boolean,            // true = slow release, false = fling
  isForward: Boolean,         // fling direction
) -> Double                   // target X to snap to
```

## Usage

```kotlin
val snapFling = rememberChartSnapFlingBehavior(
  scrollState = scrollState,
  config = SnapBehaviorConfig(
    snapToLabel = { current, projected, isDrag, isForward ->
      if (isDrag) {
        // Nearest label
        GraphSnapHelper.getSnappedPositionOnDrag(current, segment)
      } else {
        // Nearest window boundary from projected position, clamped
        GraphSnapHelper.getSnapPositionOnFling(projected, segment, isForward)
      }
    },
  ),
)

CartesianChartHost(
  ...,
  flingBehavior = snapFling,
)
```

## Architecture

| File | Change |
|------|--------|
| `SnapFlingBehavior.kt` | NEW — SnapBehaviorConfig + ChartSnapFlingBehavior |
| `VicoScrollState.kt` | Added `xToScrollValue()` and `scrollValueToX()` helpers |
| `Modifier.kt` | `flingBehavior` parameter passed to `scrollable()` |
| `CartesianChartHost.kt` | `flingBehavior` threaded through to `CartesianChartHostImpl` + snap suppression for range provider |

## Range Provider Integration

During snap animation, the range provider's debounce collector skips updates (`isSnapping` flag).
After snap completes, the next scroll info triggers a range update at the final position.
Scroll info always emits (for dedup cache) — only the collector-side skips during snap.
