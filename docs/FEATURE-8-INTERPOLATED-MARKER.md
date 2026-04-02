# Feature 8: Interpolated Marker Targets

## Overview

Markers can now land at arbitrary X positions — not just actual data points. When a
consumer's callback returns an X that doesn't match any data point, vico synthesizes
a marker target with monotone-interpolated Y values. The indicator dot sits exactly
on the curve at the interpolated position.

## How It Works

### Flow
```
User tap → ScrubMarkerController.onMarkerIndexChanged(tapX, allTargetXValues)
  → Consumer returns targetX
  → CartesianChartHost sets markerX = targetX
  → CartesianChart.draw():
      getMarkerTargets(markerX) → empty (no data point at X)
      → synthesizeInterpolatedTargets(context, markerX)
        → MonotoneInterpolator.getYAtXFromEntries(x, series)  // O(log n), zero alloc
        → Convert data Y to canvas Y
        → Create synthetic LineCartesianLayerMarkerTarget
      → DefaultCartesianMarker renders guideline + indicator + label
```

### Consumer Callback
```kotlin
val scrubController = rememberScrubMarkerController(
  scrollState = scrollState,
  onMarkerIndexChanged = { clickX, targets ->
    if (clickX == null) null  // dismiss
    else {
      // Option A: Snap to nearest data point (real target)
      targets.minByOrNull { abs(it - clickX) }

      // Option B: Snap to nearest visible label (may be interpolated)
      val labels = scrollState.getVisibleAxisLabels()
      labels.minByOrNull { abs(it - clickX) }

      // Option C: Exact tap position (always interpolated)
      clickX
    }
  },
)
```

## Performance

### vs. Previous Version (meApp v3 onChartClick)

| Aspect | v3 (meApp onChartClick) | v4 (synthesizeInterpolatedTargets) |
|--------|------------------------|------------------------------------|
| Trigger | Per-click event | Per-frame during marker display |
| Allocation | `getTargetPoints` + list filters | Zero list alloc (direct Entry access) |
| Interpolation | N/A (data points only) | O(log n) binary search + 2-3 secants |
| Y-range lookup | N/A | Cached outside series loop |
| Color lookup | N/A | Single pass over sorted targets |

### Zero-Allocation Path
- `getYAtXFromEntries` works directly on `LineCartesianLayerModel.Entry` — no `map` to `Pair`
- Binary search for segment: O(log n) instead of O(n) linear scan
- Only computes 2-3 secants (the segment's neighbors), not all n-1
- Y-range and colors cached outside the series loop

## Architecture

| File | Change |
|------|--------|
| `CartesianChart.kt` | `synthesizeInterpolatedTargets()`, `findNearestColors()`, persistent marker fallback |
| `MonotoneInterpolator.kt` | `getYAtXFromEntries()` — zero-alloc overload |
| `CartesianChartHost.kt` | No change — existing callback flow handles it |
| `ScrubMarkerController.kt` | No change — callback already returns arbitrary X |

## Persistent Markers

`forEachPersistentMarker` also falls back to `synthesizeInterpolatedTargets` when a
persistent marker's X doesn't match a data point. This means persistent markers at
arbitrary X positions (e.g., goal lines, annotations between data points) render with
interpolated Y — same zero-alloc path as interactive markers.

## Complementary APIs on VicoScrollState

For scroll-stopped use cases (not marker rendering):

- `getVisibleAxisLabels(labelProvider?)` — visible X labels in current window
- `getInterpolatedYValues(xValues, interpolationType)` — batch Y interpolation per series

These use the `Pair`-based `getYValues()` batch path (precomputes all tangents once).

## Marker contentPadding (v3 Parity)

`DefaultCartesianMarker` now accepts a `contentPadding: Insets` parameter that offsets the label from `layerBounds` without affecting chart margin calculations.

### Behavior

| LabelPosition | Effect |
|---------------|--------|
| `Top` | Label Y shifts up by `contentPadding.bottom` |
| `Bottom` | Label Y shifts down by `contentPadding.top` |
| `AbovePoint` / `AroundPoint` | contentPadding offsets from point |

### Guideline Extension

The guideline now extends beyond `layerBounds` to reach the label when contentPadding pushes it outside. The extension is computed per `labelPosition` — e.g., for `Top`, the guideline top extends to `layerBounds.top - tickSize - contentPadding.bottom`.

### Fixed Margin Height

`updateLayerMargins` uses a fixed 24dp height instead of dynamic `label.getHeight()`. This prevents chart jump when the marker shows/hides (matching v3 behavior). `contentPadding` is intentionally excluded from the margin — it only affects the label draw position.

### Usage

```kotlin
rememberDefaultCartesianMarker(
  label = rememberTextComponent(...),
  contentPadding = Insets(vertical = 16.dp),
)
```

### Files:
- `DefaultCartesianMarker.kt` — `contentPadding` param, guideline top/bottom calculation, fixed margin height, wired into `rememberDefaultCartesianMarker`
