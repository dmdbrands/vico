# Feature 7: pointSpacingProvider — Dynamic Entry Spacing

## Overview

Controls how many data entries fit in the visible chart window. A lambda that receives
the available chart width and returns the pixel spacing between entries.

## Problem

Upstream's `pointSpacing: Dp` is a fixed value — it doesn't know the chart width.
For segment-based views (week=7 entries, month=~30, year=~365), the spacing must
adapt to screen size so exactly N entries fit on screen.

## Solution

`pointSpacingProvider: ((availableWidth: Float) -> Float)?` — evaluated during
`updateDimensions` (measure phase, not composition). When provided, overrides
`pointSpacing`. Zero extra recomposition.

## Usage

```kotlin
rememberLineCartesianLayer(
  pointSpacingProvider = { availableWidth ->
    availableWidth / visibleLabelsCount.toFloat()
  },
)
```

### Per segment:
```kotlin
val visibleLabelsCount = when (segment) {
  WEEK -> 7.0
  MONTH -> 32.0 / 7.0
  YEAR -> 366.0 / 31.0
  TOTAL -> 365.0 / 31.0
}

rememberLineCartesianLayer(
  pointSpacingProvider = { it / visibleLabelsCount.toFloat() },
)
```

## Performance vs old approach

| | Old (visibleLabelsCount on CartesianChart) | New (pointSpacingProvider) |
|---|---|---|
| Where | CartesianChart.prepare() — scale factor post-layout | LineCartesianLayer.updateDimensions() — inline |
| Layout passes | 2 (compute → scale → recompute) | 1 (correct from start) |
| Recomposition | Scale change triggers redraw | Lambda is stable, no recomposition |
| Vico changes | Modified CartesianChart + CartesianDrawingContext | LineCartesianLayer only |

## Files

| File | Change |
|------|--------|
| `LineCartesianLayer.kt` | `pointSpacingProvider` on primary constructor, public constructor, copy(), rememberLineCartesianLayer() |
