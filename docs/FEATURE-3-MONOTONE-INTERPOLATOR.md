# Feature 3: Monotone Cubic Interpolator

## Overview

Fritsch-Carlson monotone cubic interpolation matching SwiftUI's `.monotone` and iOS Health charts.
Unlike Catmull-Rom (upstream), this interpolator **never overshoots** — the curve stays within
the Y bounds of the data.

## Usage

```kotlin
rememberLineCartesianLayer(
  lineProvider = LineCartesianLayer.LineProvider.series(
    LineCartesianLayer.Line(
      interpolator = LineCartesianLayer.Interpolator.monotone(),
    ),
  ),
)
```

## Algorithm: Fritsch-Carlson

1. Compute secant slopes between consecutive points
2. At each interior point, compute monotone tangent:
   - Slopes have different signs → tangent = 0 (local extremum, no overshoot)
   - Same sign → harmonic mean, clamped to 3x min slope
3. Boundary tangents = adjacent secant slope
4. Convert Hermite spline to cubic Bezier control points (GPU-friendly)

## Comparison

| | Monotone (ours) | CatmullRom (upstream) | Cubic (upstream) |
|---|---|---|---|
| Overshoot | **Never** | Yes | Yes |
| iOS Health match | **Yes** | Close | No |
| `getYRange()` | Data min/max (exact) | Must compute Bezier extrema | Data min/max (wrong for overshoot) |
| Performance | O(n), 2 FloatArrays | O(n), zero arrays | O(n), zero arrays |
| Instance | Singleton (`object`) | `data class` | `data class` |

## Files

| File | Change | Upstream Risk |
|------|--------|--------------|
| `MonotoneInterpolator.kt` | NEW — Fritsch-Carlson algorithm | **NEW** |
| `LineCartesianLayer.kt` | Added `Interpolator.monotone()` factory (1 line) | **LOW** |

## Upstream Merge Guide

Only 1 line added to `LineCartesianLayer.kt` inside `Interpolator.Companion`:
```kotlin
public fun monotone(): Interpolator = MonotoneInterpolator
```
If upstream adds/removes interpolators in this companion, just add the line back.
