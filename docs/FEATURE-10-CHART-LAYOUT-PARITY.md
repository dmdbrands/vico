# Feature 10: Chart Layout Parity (v3)

## Overview

Two fixes to ensure the chart drawing area (layerBounds) matches v3 exactly:

1. **Remove inner height cap** — `CartesianChartHostBox` had `heightIn(max = 200.dp)` which capped the chart regardless of the consumer's modifier height.
2. **Restore margin halving** — `CartesianLayerMargins.ensureValuesAtLeast` must halve top/bottom values before storing (v3 behavior). Without this, axis heights and marker margins are stored at 2x, shrinking the chart area.

## CartesianChartHostBox Height Cap

| Version | Behavior |
|---------|----------|
| v3 | No inner height constraint — consumer's `modifier.height()` is respected |
| v4 (upstream) | `heightIn(max = CHART_HEIGHT.dp)` (200dp) caps the box regardless of outer modifier |
| v4 (fixed) | `fillMaxWidth()` only — consumer controls height |

## Margin Halving

`CartesianLayerMargins.ensureValuesAtLeast` in v3 halves top/bottom inputs before storing. This is load-bearing — all callers (axes, markers) pass full values expecting them to be halved.

| Caller | Passes | Stored (v3) | Stored (v4 before fix) |
|--------|--------|-------------|----------------------|
| HorizontalAxis (bottom, height=40px) | `bottom = 40` | `20` | `40` |
| LineCartesianLayer (pointSize.half=4px) | `top = 8, bottom = 8` | `4` | `4`* |
| Marker (fixedHeight + tickSize = 30px) | `top = 30` | `15` | `30` |

*LineCartesianLayer in v4 was passing `maxMargin` (not `* 2`), so it stored `maxMargin` without halving. Fixed to pass `maxMargin * 2` to match v3's stored value of `maxMargin`.

### Result

With the same 300dp total chart height:
- v3: layerBounds.height = 300dp - (halved margins)
- v4 before fix: layerBounds.height = 300dp - (full margins) — **smaller chart**
- v4 after fix: layerBounds.height = 300dp - (halved margins) — **exact match**

## Files

| File | Change |
|------|--------|
| `CartesianChartHost.kt` | Remove `heightIn(max = CHART_HEIGHT.dp)` from `CartesianChartHostBox` |
| `CartesianLayerMargins.kt` | Restore `top.div(2)` and `bottom.div(2)` in `ensureValuesAtLeast` |
| `LineCartesianLayer.kt` | Pass `top = maxMargin * 2, bottom = maxMargin * 2` to compensate for halving |
