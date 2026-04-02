# Feature 7: visibleLabelsCount (replaced pointSpacingProvider)

## Overview

`visibleLabelsCount` on `CartesianChart` controls how many data entries are visible
in the chart window. It scales `xSpacing` in `prepare()` so exactly N entries fit.

Replaced the layer-level `pointSpacingProvider` lambda — `visibleLabelsCount` is
simpler (just a number), chart-level (applies to all layers), and evaluated once
during prepare (no per-measure lambda invocation).

## Usage

```kotlin
rememberCartesianChart(
  rememberLineCartesianLayer(...),
  visibleLabelsCount = 8.0,  // 8 entries visible in the window
)
```

In meApp:
```kotlin
val visibleLabelsCount = when (segment) {
  GraphSegment.WEEK -> 7.0
  GraphSegment.MONTH -> 5.0
  GraphSegment.YEAR -> 12.0
  GraphSegment.TOTAL -> 0.0  // 0 = auto (fit all)
}
```

## How It Works

In `CartesianChart.prepare()`:
```
desiredSpacing = availableWidth / visibleLabelsCount
scaleFactor = desiredSpacing / currentXSpacing
layerDimensions.scale(scaleFactor)
```

Scales all layer dimensions proportionally — xSpacing, scalable padding.

## Migration from pointSpacingProvider

| Before (pointSpacingProvider) | After (visibleLabelsCount) |
|-------------------------------|---------------------------|
| Layer-level lambda | Chart-level number |
| Evaluated per measure | Evaluated once in prepare |
| `{ availableWidth -> availableWidth / 8f }` | `visibleLabelsCount = 8.0` |
| Adds to `maxPointSize` | Scales existing xSpacing |
