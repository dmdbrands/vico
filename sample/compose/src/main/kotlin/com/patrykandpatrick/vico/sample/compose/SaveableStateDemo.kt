/*
 * Copyright 2025 by Patryk Goworowski and Patrick Michalik.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.patrykandpatrick.vico.sample.compose

import android.graphics.Typeface
import android.text.Layout
import android.util.Log
import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.fixed
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberEnd
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberSaveableCartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.component.shapeComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.common.insets
import com.patrykandpatrick.vico.core.cartesian.axis.Axis
import com.patrykandpatrick.vico.core.cartesian.axis.BaseAxis
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.Position
import com.patrykandpatrick.vico.core.common.component.TextComponent
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Generates random data points for chart demonstration.
 * Returns a pair where first is x coordinates (custom values) and second is y coordinates (data values).
 */
private fun generateData(count: Int, minValue: Int, maxValue: Int): Pair<List<Double>, List<Double>> {
  // Generate custom x values (not sequential, can be any values like 1, 5, 7, 9, 11, 12, 15, 16, 18, etc.)
  val xValues = (1..count).map {
    // Generate random x values between 1 and count*2 to create non-sequential x coordinates
    Random.nextInt(1, count * 2).toDouble()
  }.distinct().sorted() // Remove duplicates and sort to maintain order

  // Generate y values for each x coordinate
  val yValues = xValues.map { Random.nextFloat() * (maxValue - minValue) + minValue }.map { it.toDouble() }
  return Pair(xValues, yValues)
}

/**
 * Generates example data with specific x values like 1, 5, 7, 9, 11, 12, 15, 16, 18, etc.
 * This demonstrates how x values can be any custom values, not just sequential indices.
 */
private fun generateExampleData(minValue: Int, maxValue: Int): Pair<List<Double>, List<Double>> {
  // Example x values: non-sequential custom values
  val xValues = listOf(1.0, 5.0, 7.0, 9.0, 11.0, 12.0, 15.0, 16.0, 18.0, 20.0, 22.0, 25.0, 28.0, 30.0, 33.0, 35.0, 38.0, 40.0, 42.0, 45.0)

  // Generate y values for each x coordinate
  val yValues = xValues.map { Random.nextFloat() * (maxValue - minValue) + minValue }.map { it.toDouble() }
  return Pair(xValues, yValues)
}

/**
 * Demo showing how rememberSaveableCartesianChartModelProducer preserves chart data
 * across configuration changes and navigation without requiring manual data recreation.
 * Also demonstrates scroll threshold feature for controlling scroll sensitivity.
 */
@Composable
fun SaveableStateDemo(
  onNavigateToDynamicYRange: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  // Using rememberSaveableCartesianChartModelProducer preserves data across configuration changes AND navigation
  val modelProducer = rememberSaveableCartesianChartModelProducer()

  // Scroll threshold to control scroll sensitivity (higher values = less sensitive)
  var scrollThreshold by rememberSaveable { mutableStateOf(5f) }

  // Also use saveable scroll and zoom states to preserve chart position
  val scrollState = rememberVicoScrollState(scrollThreshold = scrollThreshold)
  val zoomState = rememberVicoZoomState()


  var dataVersion by rememberSaveable { mutableIntStateOf(0) }
  var useExampleData by rememberSaveable { mutableStateOf(false) }

  var minY by rememberSaveable { mutableIntStateOf(0) }
  var maxY by rememberSaveable { mutableIntStateOf(15) }

  var rangeUpdateTrigger by remember { mutableIntStateOf(0) }
  val visibleRange by scrollState.visibleRange.collectAsState()

  val (xData, yData) = remember(useExampleData, dataVersion) {
    if (useExampleData) generateExampleData(0, 15) else generateData(100, 0, 15)
  }
  val (xSecondaryData, ySecondaryData) = remember(useExampleData, dataVersion) {
    if (useExampleData) generateExampleData(5, 25) else generateData(100, 5, 25)
  }

  var showSecondaryLine by rememberSaveable { mutableStateOf(false) }
  var animateIn by remember { mutableStateOf(false) }
  var secondaryMinY by rememberSaveable { mutableIntStateOf(5) }
  var secondaryMaxY by rememberSaveable { mutableIntStateOf(25) }
  var markerValue by rememberSaveable { mutableStateOf(100.0) }


  LaunchedEffect(showSecondaryLine) {
    animateIn = false
    modelProducer.runTransaction {
        lineSeries {
          series(x = xData, y = yData)
        }
      if(showSecondaryLine)
        lineSeries {
          series(x = xSecondaryData, y = ySecondaryData)
        }
    }
  }


  LaunchedEffect(visibleRange) {
    visibleRange?.let { range ->
      val startX = range.visibleXRange.start
      val endX = range.visibleXRange.endInclusive

      snapshotFlow { startX to endX }
        .debounce(300)
        .distinctUntilChanged()
        .collect { (start, end) ->
          // Calculate the actual Y range from the visible data
          // Find data points where x values fall within the visible range
          val visibleData = yData.filterIndexed { index, _ ->
            val xValue = xData[index]
            xValue >= start && xValue <= end
          }

           if (visibleData.isNotEmpty()) {
             val calculatedMinY = visibleData.minOrNull()?.toInt() ?: 0
             val calculatedMaxY = visibleData.maxOrNull()?.toInt() ?: 20

             // Update the Y range based on visible data
             minY = calculatedMinY - 2
             maxY = calculatedMaxY + 2

             // Calculate secondary range if secondary line is shown
             if (showSecondaryLine) {
               val secondaryVisibleData = ySecondaryData.filterIndexed { index, _ ->
                 val xValue = xSecondaryData[index]
                 xValue >= start && xValue <= end
               }

               if (secondaryVisibleData.isNotEmpty()) {
                 val secondaryCalculatedMinY = secondaryVisibleData.minOrNull()?.toInt() ?: 5
                 val secondaryCalculatedMaxY = secondaryVisibleData.maxOrNull()?.toInt() ?: 25

                 secondaryMinY = secondaryCalculatedMinY - 2
                 secondaryMaxY = secondaryCalculatedMaxY + 2
               }
             }

             Log.i("VisibleRange", "Updated Y range: Min=$minY, Max=$maxY for visible data range")
             if (showSecondaryLine) {
               Log.i("VisibleRange", "Updated Secondary Y range: Min=$secondaryMinY, Max=$secondaryMaxY")
             }
           }
        }
    }
  }


  Column(
    modifier = modifier.padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    Text(
      text = "Saveable Chart State Demo",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold
    )

    Text(
      text = "Screen 1 of 2",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Button(onClick = onNavigateToDynamicYRange) {
      Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Navigate to Dynamic Y Range Example")
      Text("Navigate to Dynamic Y Range Example")
    }

    Text(
      text = "This chart's data AND scroll position persist across configuration changes and navigation.",
      style = MaterialTheme.typography.bodyMedium
    )

    Text(
      text = "Use the scroll threshold control below to adjust scroll sensitivity. Higher values make scrolling less sensitive to small movements.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Button(
      onClick = { dataVersion++ },
      modifier = Modifier.fillMaxWidth()
    ) {
      Text("Generate New Data (Version: $dataVersion)")
    }

    Button(
      onClick = { useExampleData = !useExampleData },
      modifier = Modifier.fillMaxWidth()
    ) {
      Text(if (useExampleData) "Switch to Random Data" else "Switch to Example Data (1,5,7,9,11,12,15,16,18...)")
    }

    Button(
      onClick = {
        minY = (0..10).random()
        maxY = (15..30).random()
      },
      modifier = Modifier.fillMaxWidth()
    ) {
      Text("Change Y Range (Min: $minY, Max: $maxY)")
    }

    Button(
      onClick = {
        minY = 0
        maxY = 20
      },
      modifier = Modifier.fillMaxWidth()
    ) {
      Text("Reset Y Range")
    }

    Button(
      onClick = { showSecondaryLine = !showSecondaryLine },
      modifier = Modifier.fillMaxWidth()
    ) {
      Text(if (showSecondaryLine) "Hide Secondary Line" else "Show Secondary Line")
    }

    Button(
      onClick = { markerValue = (0..10).random().toDouble() },
      modifier = Modifier.fillMaxWidth()
    ) {
      Text("Randomize Marker Value (Current: $markerValue)")
    }

    Text(
      text = "Scroll Threshold: ${scrollThreshold.toInt()}px (Higher = Less Sensitive)",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Button(
      onClick = {
        scrollThreshold = when {
          scrollThreshold < 2f -> 5f
          scrollThreshold < 10f -> 15f
          else -> 2f
        }
      },
      modifier = Modifier.fillMaxWidth()
    ) {
      Text("Change Scroll Sensitivity (Current: ${scrollThreshold.toInt()}px)")
    }

    val markerDecoration = rememberMarkerDecoration(markerValue)

    val primaryLayer = rememberLineCartesianLayer(
      verticalAxisPosition = Axis.Position.Vertical.Start,
      rangeProvider = remember(rangeUpdateTrigger, minY, maxY) {
        val currentMinY = minY
        val currentMaxY = maxY
        object : com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider {
          override fun getMinY(minY: Double, maxY: Double, extraStore: com.patrykandpatrick.vico.core.common.data.ExtraStore): Double {
            return currentMinY.toDouble()
          }
          override fun getMaxY(minY: Double, maxY: Double, extraStore: com.patrykandpatrick.vico.core.common.data.ExtraStore): Double {
            return currentMaxY.toDouble()
          }
        }
      }
    )

    val colorList = listOf(Color.Red)

    val secondaryLayer = rememberLineCartesianLayer(
      verticalAxisPosition = Axis.Position.Vertical.End,
      lineProvider = LineCartesianLayer.LineProvider.series(
        listOf(colorList).map {
          LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(fill(it.first()))
        )
        },
      ),
      rangeProvider = remember(rangeUpdateTrigger, secondaryMinY, secondaryMaxY) {
        val currentSecondaryMinY = secondaryMinY
        val currentSecondaryMaxY = secondaryMaxY
        object : com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider {
          override fun getMinY(minY: Double, maxY: Double, extraStore: com.patrykandpatrick.vico.core.common.data.ExtraStore): Double {
            return currentSecondaryMinY.toDouble()
          }
          override fun getMaxY(minY: Double, maxY: Double, extraStore: com.patrykandpatrick.vico.core.common.data.ExtraStore): Double {
            return currentSecondaryMaxY.toDouble()
          }
        }
      }
    )
    val fill = fill(Color(0xFF458239))

    CartesianChartHost(
      chart = rememberCartesianChart(
        primaryLayer,
        secondaryLayer,
        endAxis = VerticalAxis.rememberEnd(
          label = rememberTextComponent(
            textSize = 14.sp,
            textAlignment = Layout.Alignment.ALIGN_CENTER,
          ),
          size = BaseAxis.Size.fixed(40.dp),
          markerDecoration = markerDecoration,
          tickLength = 0.dp,
          tick = null
        ),
        bottomAxis = HorizontalAxis.rememberBottom(
          tick = rememberAxisGuidelineComponent(),
          tickLength = 20.dp,
          horizontalLabelPosition = Position.Horizontal.End
        ),
        marker = rememberDefaultCartesianMarker(
          guideline = rememberAxisLineComponent(),
          contentPadding = insets(horizontal = 40.dp),
          label = rememberTextComponent()
        ),
        visibleLabelsCount = 6
      ),
      animateIn = true,
      modelProducer = modelProducer,
      scrollState = scrollState,
      zoomState = zoomState,
      modifier = Modifier.fillMaxWidth()
    )
  }
}

@Composable
private fun rememberMarkerDecoration(markerValue: Double): VerticalAxis.MarkerDecoration {
  val fill = fill(Color(0xFF458239))
  val line = rememberLineComponent(fill = fill(Color(0xFF458239)), thickness = 2.dp)
  val labelComponent =
    rememberTextComponent(
      typeface = Typeface.DEFAULT_BOLD,
      color = Color(0xfffdc8c4),
      padding = insets(horizontal = 6.dp , vertical = 2.dp),
      textSize = 14.sp,
      textAlignment = Layout.Alignment.ALIGN_CENTER,

              background =
        shapeComponent(
          fill,
          shape = CorneredShape.Pill,
        ),
    )

  return remember(markerValue) {
    VerticalAxis.MarkerDecoration(
      y = { markerValue },
      labelComponent = labelComponent,
      label = { markerValue.toInt().toString() },
      horizontalLabelPosition = VerticalAxis.HorizontalLabelPosition.Outside,
      verticalLabelPosition = Position.Vertical.Center,
    )
  }
}


@Composable
@Preview
private fun SaveableStateDemoPreview() {
  val modelProducer = remember { CartesianChartModelProducer() }
  // Use `runBlocking` only for previews, which don't support asynchronous execution.
  runBlocking {
    val (xData, yData) = generateData(100, 0, 20)
    val (xSecondaryData, ySecondaryData) = generateData(100, 0, 15)
    modelProducer.runTransaction {
      columnSeries { series(x = xData, y = yData) }
      lineSeries { series(x = xSecondaryData, y = ySecondaryData) }
    }
  }
  PreviewBox { SaveableStateDemo() }
}
