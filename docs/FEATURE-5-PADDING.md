# Feature 5: Chart Edge Padding (xWithPadding + FadingEdges padding)

## Overview

Chart edge padding so the first visible data point has breathing room from the left edge.
Two additions:

1. **`Scroll.Absolute.xWithPadding(x, paddingXStep)`** — scroll to X with padding offset
2. **`rememberFadingEdges(startPaddingXStep, endPaddingXStep)`** — fade width from xStep fraction

## Usage

```kotlin
// Compute padding for segment
val (startPaddingXStep, _) = GraphSnapHelper.getVisiblePaddingXStepForSegment(segment)

// Scroll to X with padding offset
val initialScroll = Scroll.Absolute.xWithPadding(startX, startPaddingXStep)

// Fading edges respect padding
val fadingEdges = rememberFadingEdges(
  startWidth = 0.dp,
  endWidth = 0.dp,
  startPaddingXStep = startPaddingXStep.takeIf { it > 0.0 },
)

CartesianChartHost(
  scrollState = rememberVicoScrollState(initialScroll = initialScroll),
  chart = rememberCartesianChart(..., fadingEdges = fadingEdges),
)
```

## Files

| File | Change |
|------|--------|
| `Scroll.kt` | Added `Scroll.Absolute.xWithPadding()` factory |
| `FadingEdges.kt` | Added `PaddedFadingEdges` + `startPaddingXStep`/`endPaddingXStep` params on `rememberFadingEdges` |
