# Feature 1: ScrollAwareRangeProvider

## Overview

Dynamic Y-axis range that adapts to visible data points as the user scrolls through a chart.
The Y-axis ticks and range animate following iOS-like patterns: ticks swap instantly
(approximating iOS cross-fade), chart content animates positionally via range interpolation.

**v2 (updated)**: Replaced segment cache with binary search on sorted X values.
Callback now receives actual visible entries `List<Pair<x, y>>` with configurable
`paddingEntries` instead of pre-computed `(minY, maxY)`. Accurate for non-sequential X data.

## Callback API

```kotlin
val rangeProvider = rememberScrollAwareRangeProvider(
  paddingEntries = 1,   // 1 extra entry before/after visible window
  debounceMs = 150,
  animDurationMs = 250,
  minX = state.chartMinX ?: Double.NaN,  // X range override from ViewModel state
  maxX = state.chartMaxX ?: Double.NaN,
) { visibleEntries ->   // List<Pair<Double, Double>> — (x, y) pairs
  val minY = visibleEntries.minOf { it.second }
  val maxY = visibleEntries.maxOf { it.second }
  // Your nice-scale logic
  (niceMin..niceMax) to ticks
}
```

### X Range Override

`minX`/`maxX` params override `getMinX`/`getMaxX` on the provider. Computed by ViewModel
(matching v3's `CartesianRangeValues.minX/maxX`), stored in state, passed as composable params.
Set during composition via plain var assignment — available before first `prepare()` call.
Without override, falls back to data's actual min/max X.

## Visible Entry Computation

- Binary search on sorted X values: O(log n)
- Padding by entry count (not X distance): includes N extra entries on each side
- Cache: skips recomputation if visible window indices haven't changed
- No segment cache — removed (binary search is faster and exact)

## Files Changed

### New Files
| File | Purpose |
|------|---------|
| `vico/compose/.../data/ScrollAwareRangeProvider.kt` | Core provider — segment cache, visible range computation, callback API |
| `vico/compose/.../axis/ListItemPlacer.kt` | VerticalAxis ItemPlacer that returns consumer-provided tick list, filtered to current range |
| `sample/charts/compose/.../DmdBrandsTestChart.kt` | Test chart with 200 data points in 3 distinct regions |

### Modified Files
| File | Change | Upstream Conflict Risk |
|------|--------|----------------------|
| `vico/compose/.../CartesianChartHost.kt` | Added `ScrollAwareRangeEffect`, `AnimatedYCartesianChartRanges`, scroll info emission in Canvas, alpha(0f) initial hide | **HIGH** — upstream frequently modifies this file |
| `vico/compose/.../layer/LineCartesianLayer.kt` | Added `internalRangeProvider`/`internalVerticalAxisPosition` accessors (2 lines). Skip `drawingModel` when using `ScrollAwareRangeProvider` (5 lines) | **MEDIUM** — the Interpolator refactor changed this file |
| `sample/app/.../Chart.kt` | Added `DmdBrandsTest` detail | LOW |
| `sample/app/.../Charts.kt` | Wired up test chart | LOW |
| `sample/app/.../Charts.android.kt` | Added placeholder for Views | LOW |

## Architecture

```
meApp (SnapshotLineChart.kt)
  │
  │  rememberScrollAwareRangeProvider { minY, maxY ->
  │      NiceScaleCalculator.generateNiceScale(minY, maxY, ...)
  │      → returns (range, ticks)
  │  }
  │
  ▼
ScrollAwareRangeProvider (implements CartesianLayerRangeProvider)
  │  - Segment cache: divides data into chunks, stores (minY, maxY) per chunk
  │  - getMinY()/getMaxY(): returns animated values (or raw data before init)
  │  - scrollUpdates: MutableSharedFlow receives scroll info from Canvas
  │
  ▼
CartesianChartHost (public composable)
  │  - animatedYRange: mutableStateOf<Pair<Double, Double>>
  │  - AnimatedYCartesianChartRanges: lightweight wrapper overriding Y range
  │  - alpha(0f) until first visible-window range is computed
  │
  ▼
ScrollAwareRangeEffect (private composable)
  │  - LaunchedEffect(model): builds segment cache
  │  - LaunchedEffect(provider, model): first scroll → snapTo (no animation)
  │  - LaunchedEffect(provider): debounced scroll → animateTo (250ms tween)
  │  - LaunchedEffect(Unit): snapshotFlow pushes animated values to State
  │
  ▼
CartesianChartHostImpl (internal composable)
  │  - Canvas emits ScrollInfo on each draw frame (deduplicated)
  │  - Receives animated ranges → measuringContext → Canvas redraws
  │
  ▼
LineCartesianLayer
  │  - drawingModel=null when ScrollAwareRangeProvider (live Y recalc per frame)
  │  - internalRangeProvider/internalVerticalAxisPosition: internal accessors
  │
  ▼
ListItemPlacer (VerticalAxis.ItemPlacer)
     - Returns ticks filtered to current animated range (with EPSILON=0.01)
     - Caches filtered result per (range, tickList identity)
```

## Animation Flow

```
User scrolls → Canvas emits ScrollInfo
  → MutableSharedFlow (buffer=10)
  → debounce 150ms (configurable)
  → computeVisibleRange from segment cache (O(segments), typically 1-5)
  → consumer callback (e.g., ImprovedNiceScaleCalculator)
  → returns (range, ticks)

SIMULTANEOUSLY:
  1. provider.currentTicks = newTicks (instant swap — iOS cross-fade approx)
  2. animMinY.animateTo(newMin, tween(250ms))
     animMaxY.animateTo(newMax, tween(250ms))

Each animation frame:
  → snapshotFlow emits (minY, maxY)
  → animatedYRange State updates
  → AnimatedYCartesianChartRanges created (cached YRange, no anonymous objects)
  → CartesianChartHostImpl recomposes → Canvas redraws
  → ListItemPlacer filters ticks to current animated range
```

## Initial Load (Zero Flash)

```
Frame 0:  model loads, initialRanges from provider (Y=NaN until computed, X=from state)
          animatedYRange = NaN → chartAlpha = 0f (invisible)
          Canvas runs → emits ScrollInfo (replay=1 ensures delivery)

Frame 1:  LaunchedEffect receives scroll event → computeVisibleEntries (visible only, never all)
          → snapTo visible range → animatedYRange set → chartAlpha = 1f
```

### modelStructureKey

`LaunchedEffect` and `Animatable` keyed on the layer's structure (series count + point counts),
NOT on model identity. Renormalization (same structure, different Y values) does NOT restart
the effect or reset Animatables. Only structural changes (layer added/removed, point count change)
trigger a restart.

## Performance Characteristics

| Metric | Value | Notes |
|--------|-------|-------|
| Recompositions per animation | ~18 (300ms at 60fps) | CartesianChartHost body only |
| Object allocations per frame | 3 (AnimatedYRanges, Pair, ScrollInfo) | ScrollInfo deduplicated when unchanged |
| Segment cache lookup | O(visible_segments) ~1-5 | Independent of total data size |
| ListItemPlacer filter | Cached per (range, tickList) | Runs once per frame, not 3x |
| drawingModel bypass | O(n) Y recalc per frame | n = total visible entries, acceptable for <1000 points |

## Known Tradeoffs

1. **Recomposition per animation frame**: `animatedYRange` State change triggers `CartesianChartHost` recomposition ~18 times per 300ms animation. Acceptable for single chart; for multiple charts on screen, consider reducing `animDurationMs`.

2. **drawingModel=null**: LineCartesianLayer skips its pre-computed Y position cache, recalculating from raw data each frame. For <1000 data points this is <1ms. For 5000+ points, consider implementing a range-ratio transform on the cached positions instead.

3. **Float precision**: Animatable uses Float, ranges are Double. Values >16M lose sub-unit precision. Acceptable for health data (weight, BP, temperature).

4. **alpha(0f) initial frame**: Canvas runs invisibly on frame 0 to emit scroll info. Full chart drawing work runs but is not composited. Trade: one wasted frame of GPU work for zero visual flash.

## Upstream Merge Guide

When merging a new upstream release:

1. **CartesianChartHost.kt** (highest risk):
   - Our changes are in the `CartesianChartHost` public composable (lines 76-116) and at the end of file (ScrollAwareRangeEffect + AnimatedYCartesianChartRanges)
   - Canvas scroll emission is inside `CartesianChartHostImpl` (look for "Emit scroll info" comment)
   - If upstream restructures `CartesianChartHostImpl`, move the scroll emission to match

2. **LineCartesianLayer.kt** (medium risk):
   - Internal accessors at line 73-75: `internalRangeProvider`, `internalVerticalAxisPosition`
   - drawingModel skip at line 515-523: `if (rangeProvider is ScrollAwareRangeProvider) null`
   - If upstream renames/restructures, update the accessor and the skip check

3. **New files** (no risk): ScrollAwareRangeProvider.kt, ListItemPlacer.kt, DmdBrandsTestChart.kt

## yTransform (Feature 11)

Render-time Y value transformation on `LineCartesianLayer`. Eliminates ViewModel-based
renormalization for secondary metrics.

### API

```kotlin
rememberLineCartesianLayer(
  yTransform = { series, yRange, visibleXRange ->
    // Returns DoubleArray of transformed Y values (same size as series)
    // Called during draw, cached by series hash + yRange
    GraphUtil.normalizeYValues(series, yRange.minY, yRange.maxY, ...)
  }
)
```

### Cache Strategy

- Key: `series.hashCode() + yRange.minY.toBits() + yRange.maxY.toBits()`
- During scroll (yRange constant): cached, zero computation
- On scroll settle (yRange animates): cache invalidates, recomputes per animation frame
- `transformIndexMap`: `entry.x → index` for O(1) lookup in both `getDrawY` functions

### Shared Constants

`Animation.RANGE_ANIM_DURATION` (300ms) — shared between ScrollAwareRangeProvider Y-range
animation and any future yTransform animation.

### Provider Deduplication

When multiple layers share the same `ScrollAwareRangeProvider` instance,
`ScrollAwareRangeEffect` deduplicates via `distinctBy { it.first }` — `buildCache` runs
once for the first layer (primary), preventing secondary data from overwriting the cache.
