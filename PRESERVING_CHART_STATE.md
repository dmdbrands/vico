# Preserving Chart State in Vico

This guide explains how to preserve chart data and visual state (scroll position, zoom level) across configuration changes and navigation in Vico charts.

## Problem

When using Jetpack Compose navigation or experiencing configuration changes (like screen rotation), charts would lose their:

1. **Data** - requiring `runTransaction` to be called repeatedly
2. **Visual state** - scroll position and zoom level reset to initial values

## Solution Overview

Vico provides enhanced state preservation through:

1. **`rememberSaveableCartesianChartModelProducer()`** - Preserves chart data
2. **`rememberVicoScrollState()`** - Preserves scroll position
3. **`rememberVicoZoomState()`** - Preserves zoom level

## Implementation

### Basic Usage (Data Preservation Only)

```kotlin
@Composable
fun MyChart() {
    // ✅ Use rememberSaveableCartesianChartModelProducer instead of rememberCartesianChartModelProducer
    val modelProducer = rememberSaveableCartesianChartModelProducer()

    // Only populate data when it actually changes, not on every recomposition
    LaunchedEffect(dataVersion) { // dataVersion should only change when data changes
        modelProducer.runTransaction {
            lineSeries {
                series(xValues, yValues)
            }
        }
    }

    CartesianChartHost(
        chart = rememberCartesianChart(/* chart config */),
        modelProducer = modelProducer
    )
}
```

### Complete State Preservation (Recommended)

```kotlin
@Composable
fun MyChart() {
    // ✅ Preserve chart data across config changes and navigation
    val modelProducer = rememberSaveableCartesianChartModelProducer()

    // ✅ Preserve scroll position
    val scrollState = rememberVicoScrollState()

    // ✅ Preserve zoom level
    val zoomState = rememberVicoZoomState()

    var dataVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(dataVersion) {
        modelProducer.runTransaction {
            lineSeries {
                series(xValues, yValues)
            }
        }
    }

    CartesianChartHost(
        chart = rememberCartesianChart(/* chart config */),
        modelProducer = modelProducer,
        scrollState = scrollState,  // ✅ Explicit scroll state
        zoomState = zoomState,      // ✅ Explicit zoom state
    )
}
```

## Key Changes in Enhanced Implementation

### 1. Enhanced CartesianChartModelProducer.Saver

The saver now preserves actual chart data instead of just hash codes:

```kotlin
// Before: Only saved hash codes, data was lost
SavedState(
    partialsHashCode = producer.lastPartials.hashCode(),
    extraStoreHashCode = producer.lastTransactionExtraStore.hashCode(),
    hasData = producer.lastPartials.isNotEmpty()
)

// After: Saves actual serializable data
SavedState(
    serializedPartials = producer.lastPartials.map { SerializablePartial.from(it) },
    serializedExtraStore = SerializableExtraStore.from(producer.lastTransactionExtraStore),
    hasData = producer.lastPartials.isNotEmpty()
)
```

### 2. Serializable Data Classes

Created serializable wrappers for all chart data types:

-   `SerializableLinePartial` - Line chart data
-   `SerializableColumnPartial` - Column chart data
-   `SerializableCandlestickPartial` - Candlestick chart data
-   `SerializableEntry` - Basic chart entries
-   `SerializableExtraStore` - Auxiliary data

### 3. Automatic Data Restoration

The enhanced saver automatically restores chart data without requiring `runTransaction`:

```kotlin
restore = { savedState ->
    CartesianChartModelProducer().apply {
        if (savedState.hasData && savedState.serializedPartials.isNotEmpty()) {
            // Restore data directly into producer's internal state
            val restoredPartials = savedState.serializedPartials.mapNotNull { it.toPartial() }
            val restoredExtraStore = savedState.serializedExtraStore.toMutableExtraStore()

            lastPartials = restoredPartials
            lastTransactionExtraStore = restoredExtraStore

            // Clear cached model to force regeneration with restored data
            cachedModel = null
            cachedModelPartialHashCode = null
        }
    }
}
```

## Migration Guide

### From Basic to Enhanced Preservation

```kotlin
// ❌ Old way - data lost on config changes
@Composable
fun MyChart() {
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(Unit) { // Runs on every recomposition!
        modelProducer.runTransaction {
            lineSeries { series(data) }
        }
    }

    CartesianChartHost(
        chart = rememberCartesianChart(/* config */),
        modelProducer = modelProducer
    )
}

// ✅ New way - complete state preservation
@Composable
fun MyChart() {
    val modelProducer = rememberSaveableCartesianChartModelProducer()
    val scrollState = rememberVicoScrollState()
    val zoomState = rememberVicoZoomState()

    LaunchedEffect(dataVersion) { // Only when data actually changes
        modelProducer.runTransaction {
            lineSeries { series(data) }
        }
    }

    CartesianChartHost(
        chart = rememberCartesianChart(/* config */),
        modelProducer = modelProducer,
        scrollState = scrollState,
        zoomState = zoomState
    )
}
```

## Benefits

1. **Performance** - No recalculation of chart models on config changes
2. **User Experience** - Charts maintain their state during navigation and rotation
3. **Simplified Code** - Less boilerplate for handling state restoration
4. **Backward Compatible** - Existing code continues to work unchanged

## Supported Chart Types

The enhanced saver supports all major chart types:

-   ✅ Line charts (`LineCartesianLayerModel.Partial`)
-   ✅ Column charts (`ColumnCartesianLayerModel.Partial`)
-   ✅ Candlestick charts (`CandlestickCartesianLayerModel.Partial`)
-   ✅ Mixed charts (combination of above)

## Demo

Check out `SaveableStateDemo.kt` in the sample app to see the enhanced functionality in action. The demo shows how chart data and scroll position persist across:

-   Configuration changes (screen rotation)
-   Navigation (leaving and returning to screen)
-   Process death and recreation

## Troubleshooting

### Chart still resets on navigation

-   Ensure you're using `rememberSaveableCartesianChartModelProducer()` instead of `rememberCartesianChartModelProducer()`
-   Make sure your navigation setup preserves saved state
-   Check that `LaunchedEffect` dependencies only change when data actually changes

### Scroll position not preserved

-   Use explicit `scrollState = rememberVicoScrollState()` parameter in `CartesianChartHost`
-   Don't create new scroll state instances unnecessarily

### Data not restored after process death

-   Verify that your data classes are properly serializable
-   Check Android logs for any serialization errors
-   Ensure you're not exceeding saved state size limits

