# Feature 6: Axis Enhancements (MarkerDecoration + Separators)

## Overview

Two axis enhancements for health/fitness chart patterns:

1. **MarkerDecoration** on VerticalAxis — goal weight indicator on Y-axis
2. **Separators** on HorizontalAxis — vertical lines at period boundaries

## MarkerDecoration

A decoration drawn at a specific Y value on the vertical axis (e.g., goal weight pill label).

```kotlin
VerticalAxis.rememberEnd(
  markerDecoration = VerticalAxis.MarkerDecoration(
    y = { goalWeight.toDouble() },
    markerComponent = rememberTextComponent(
      style = TextStyle(color = Color.White, fontSize = 12.sp),
      padding = Insets(horizontal = 8.dp, vertical = 2.dp),
      background = rememberShapeComponent(Fill(Color(0xFF458239)), CircleShape),
    ),
    label = { goalWeight.toString() },
    outsideRangeOffset = 30f,  // offset from edge when goal is outside visible Y range
  ),
)
```

### Behavior:
- Drawn at the Y position computed from `yRange`
- When Y is outside visible range: positions at top/bottom edge with `outsideRangeOffset`
- Centered horizontally in the axis bounds
- Zero per-frame allocations — all components are `remember`ed

### Files:
- `VerticalAxis.kt` — `MarkerDecoration` data class + `markerDecoration` param + drawing in `drawOverLayers`

## Separators

Vertical lines at specific X positions on the chart, drawn from top to bottom of `layerBounds`.

```kotlin
HorizontalAxis.rememberBottom(
  separators = HorizontalAxis.Separators(
    values = listOf(minX, monthStart1, monthStart2, maxX),
    line = rememberLineComponent(Fill(Color(0x33000000)), thickness = 1.dp),
  ),
)
```

### Performance vs old approach:
| Aspect | Old (v3.0.0) | New |
|--------|-------------|-----|
| Values | Lambda `(ExtraStore) -> List` — allocates per frame | Stable `List<Double>` — zero allocation |
| Clipping | `canvas.clipRect` every frame (GPU) | Bounds check per separator (CPU, cheaper) |
| Off-screen | Draws all, clip hides | Skips before draw call |
| Style | Reuses axis `line` | Dedicated `LineComponent` in `Separators` data class |

### Files:
- `HorizontalAxis.kt` — `Separators` data class + `separators` param + `drawSeparators()` method + wired into `rememberTop`/`rememberBottom`
