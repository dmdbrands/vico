# Feature 9: Size.Scroll + V3 Axis Alignment

## Overview

Matches the v3 fork's axis and label behavior:

1. **Size.Scroll** — vertical axis line scrolls with chart content
2. **No unscalable label padding** — HorizontalAxis.updateLayerDimensions is a no-op (v3 computed padding but never applied it)
3. **No fullXRange boundary exclusion** — labels at data min/max included (v3 behavior)
4. **No maxWidth on labels** — v3 didn't constrain label width per spacing
5. **V3-style clip** — bounds ± startLayerMargin, not tied to axis line position
6. **Label clip extension** — drawUnderLayers clip extends by maxLabelWidth.half so first/last labels are fully visible
7. **Point clip via margins** — LineCartesianLayer uses layer margins instead of unscalable padding

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

## Architecture

| File | Change |
|------|--------|
| `BaseAxis.kt` | New `Size.Scroll(value, isLabelsScrollable)` class |
| `VerticalAxis.kt` | Scroll line/ticks, dynamic margin, guideline skip, `updateScrollState()` |
| `HorizontalAxis.kt` | `Size.Scroll` in when block, no-op updateLayerDimensions, v3-style clip, no maxWidth on labels, label clip extension |
| `HorizontalAxisItemPlacers.kt` | No fullXRange boundary exclusion in getLabelValues |
| `CartesianChartHost.kt` | Inject scroll state into axes before `prepare()` |
| `LineCartesianLayer.kt` | Point clip via margins instead of unscalable padding |

## V3 vs V4 Differences Fixed

| Issue | V4 upstream | V3 / Our fix |
|-------|------------|--------------|
| Label padding | `ensureValuesAtLeast(unscalableStartPadding)` applied | Computed but never applied (no-op) |
| Label at data min | Excluded by `fullXRange.start` check | Included (no fullXRange check) |
| Label maxWidth | `ceil(spacing * xSpacing)` — 0 at boundary | Not passed (no width constraint) |
| Clip boundary | Tied to axis line position via `getLineLeft` | `bounds ± startLayerMargin` |
| Label clip | Only `getStartLayerMargin` (~3px) | Extended by `maxLabelWidth.half` in drawUnderLayers |
| Point padding | `maxPointSize.half` in unscalable padding | Clip via layer margins (start/end/top/bottom) |
