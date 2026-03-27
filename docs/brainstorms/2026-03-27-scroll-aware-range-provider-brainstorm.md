# Brainstorm: Scroll-Aware Range Provider — iOS-like Y-Axis Animation

**Date:** 2026-03-27
**Status:** Approved
**Feature:** Dynamic Y-axis ranges that adapt to visible data with iOS-like animation

## iOS Deep Analysis

### How Apple Health / Swift Charts actually works:

1. **Label transition**: CROSS-FADE (opacity), NOT positional sliding
   - Old labels fade out (opacity 1→0) at their current positions
   - New labels fade in (opacity 0→1) at their final positions
   - Duration: ~200-300ms, ease-in-out

2. **Chart content**: Positional animation (data points move vertically)
   - The line/bar chart content animates to new positions as Y range changes
   - This happens SIMULTANEOUSLY with the label cross-fade

3. **Debouncing**: Y-axis does NOT update on every scroll pixel
   - Waits ~100-150ms after scroll settles
   - Prevents jittery axis updates

4. **Tick count**: Fixed count (3-5 labels), variable step size
   - Always uses "nice" numbers (1, 2, 5, 10, 20, 50...)

5. **Key insight**: iOS does NOT merge old+new ticks, does NOT slide ticks in/out.
   It simply cross-fades the entire label set while animating the plot.

### Vico limitation:
- `VerticalAxis.ItemPlacer` returns a list of values — no per-label opacity control
- Cannot do true cross-fade

### Best approximation for vico:
- **Set new ticks immediately** (instant swap = our "cross-fade" approximation)
- **Animate the Y range** (chart content scales = our positional animation)
- Both happen simultaneously — the range animation masks the instant tick swap
- This IS what iOS does, minus the 250ms opacity fade on labels

## Architecture

### Files:

1. **ScrollAwareRangeProvider.kt** (new)
   - Implements `CartesianLayerRangeProvider`
   - Holds segment cache for fast visible-range lookup
   - Stores current animated minY/maxY and current ticks
   - Exposes `scrollUpdates` flow for Canvas to emit scroll info
   - Consumer provides callback: `(minY, maxY) -> Pair<range, ticks>`

2. **ListItemPlacer.kt** (new)
   - Simple `VerticalAxis.ItemPlacer` that returns a fixed tick list
   - Filters ticks to current Y range with EPSILON tolerance
   - All measurement methods also filter — prevents layout jitter

3. **CartesianChartHost.kt** (modified)
   - `CartesianChartHost` (public): wraps ranges with `AnimatedYCartesianChartRanges`
   - `CartesianChartHostImpl` (internal): emits scroll info from Canvas
   - `ScrollAwareRangeEffect` (private): manages cache, debounce, animation
   - `AnimatedYCartesianChartRanges` (private): lightweight ranges wrapper

4. **LineCartesianLayer.kt** (modified)
   - `rangeProvider` and `verticalAxisPosition` changed to `internal`
   - Skips cached `drawingModel` when using `ScrollAwareRangeProvider`

### Animation flow:

```
User scrolls → Canvas emits ScrollInfo → debounce 150ms →
  compute visible Y values from segment cache →
  call user's callback (e.g. ImprovedNiceScaleCalculator) →
  get new range + new ticks →

  SIMULTANEOUSLY:
  1. Set new ticks immediately (instant swap)
  2. Animate range via Animatable (250ms ease-in-out)

  Each animation frame:
  - Update provider.currentMinY/maxY
  - Update animatedYRange Compose State
  - AnimatedYCartesianChartRanges wraps initialRanges with animated Y
  - CartesianChartHostImpl recomposes with new ranges
  - Canvas redraws: line repositions, axis shows filtered new ticks

  Guard: isAnimating flag prevents debounce re-triggering during animation
```

### Critical implementation details:

1. **isAnimating guard**: Scroll info is emitted from Canvas on every frame.
   During animation, Canvas redraws → emits → debounce would re-fire after 150ms.
   Guard prevents this from restarting the animation mid-flight.

2. **EPSILON filtering**: Float↔Double conversion causes boundary ticks to
   flicker (169.9999 vs 170.0001). EPSILON=0.01 in ListItemPlacer filter.

3. **Filtered measurements**: All three ItemPlacer methods (getLabelValues,
   getWidthMeasurement, getHeightMeasurement) must filter to current range.
   Otherwise axis width/height jumps when merged ticks include out-of-range values.

4. **No drawingModel during scroll-aware mode**: LineCartesianLayer pre-computes
   normalized Y positions in a drawingModel. This must be skipped so the line
   recalculates from the live animated yRange each frame.

5. **AnimatedYCartesianChartRanges**: Thin wrapper that delegates X range to
   initialRanges but overrides getYRange() with animated values. No reset/rebuild.

## Usage API (meApp)

```kotlin
val rangeProvider = rememberScrollAwareRangeProvider(
    segmentSize = 10,
    debounceMs = 150,
    animDurationMs = 250,
) { visibleMinY, visibleMaxY ->
    val meta = ImprovedNiceScaleCalculator.generateNiceScale(
        minValue = visibleMinY, maxValue = visibleMaxY, goalWeight = goalWeight
    )
    val ticks = buildTicksFromStep(meta.min, meta.max, meta.step)
    (meta.min..meta.max) to ticks
}

// Chart
rememberLineCartesianLayer(rangeProvider = rangeProvider)

// Axis
VerticalAxis.rememberStart(
    itemPlacer = ListItemPlacer { rangeProvider.currentTicks }
)
```
