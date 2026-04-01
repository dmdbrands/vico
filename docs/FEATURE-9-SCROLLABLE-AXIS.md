# Feature 9: Size.Scroll — Scrollable Vertical Axis

## Overview

`BaseAxis.Size.Scroll` makes the vertical axis line scroll with chart content.
When the user scrolls right, the axis line slides left and disappears.
Scroll back and it returns. Matching the v3 fork behavior.

## Usage

```kotlin
// Axis line scrolls with content
VerticalAxis.rememberStart(
  size = BaseAxis.Size.Scroll(8.dp),
)

// Axis line + margin gap scrolls (gap disappears when scrolled away)
VerticalAxis.rememberStart(
  size = BaseAxis.Size.Scroll(16.dp, isLabelsScrollable = true),
)
```

## How It Works

### Axis Line Scrolling
In `drawLineAndTicks`, the axis line position shifts by `effectiveScroll`:
- Start axis (LTR): `effectiveScroll = -scroll`
- End axis (LTR): `effectiveScroll = maxScroll - scroll`

The line is clipped to `layerBounds` so it doesn't draw outside the chart area.

### Dynamic Margin (isLabelsScrollable = true)
When `isLabelsScrollable`, the axis margin shrinks with scroll:
`effectiveWidth = (width - scrollOffset).coerceAtLeast(0f)`

At scroll=0, full margin. At scroll >= axisWidth, margin = 0. The gap disappears.

Scroll state is injected from `CartesianChartHost` before `prepare()` to avoid
draw-to-measure phase coupling.

### Guidelines
When `Size.Scroll`, guidelines skip the `isNotInRestrictedBounds` check — they
always draw across full `layerBounds` width.

## Also in This Commit

### LineCartesianLayer — Point Clip via Margins
Removed `maxPointSize.half` from `unscalableStartPadding`. Instead, extends the
layer clip rect via `updateLayerMargins` (start/end/top/bottom). Points at data
edges are visible via clip extension, not data shift. Matches v3 behavior.

## Architecture

| File | Change |
|------|--------|
| `BaseAxis.kt` | New `Size.Scroll(value, isLabelsScrollable)` class |
| `VerticalAxis.kt` | Scroll line/ticks, dynamic margin, guideline skip, `updateScrollState()` |
| `HorizontalAxis.kt` | `Size.Scroll` in `when` block for height calculation |
| `CartesianChartHost.kt` | Inject scroll state into axes before `prepare()` |
| `LineCartesianLayer.kt` | Point clip via margins instead of unscalable padding |
