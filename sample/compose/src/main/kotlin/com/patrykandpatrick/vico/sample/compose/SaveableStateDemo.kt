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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.SnapBehaviorConfig
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberEnd
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberTop
import com.patrykandpatrick.vico.compose.cartesian.axis.scroll
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
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.InterpolationType
import com.patrykandpatrick.vico.core.cartesian.Scroll
import com.patrykandpatrick.vico.core.cartesian.axis.Axis
import com.patrykandpatrick.vico.core.cartesian.axis.BaseAxis
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianRangeValues
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayerPadding
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.common.Position
import com.patrykandpatrick.vico.core.common.component.ShapeComponent
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

private const val MULTIPLE_STEP = 7

/**
 * Horizontal item placer for the list of 10000: labels/ticks at multiples of [MULTIPLE_STEP] (7)
 * over the full X range.
 */
@Composable
private fun rememberMultiplesOfSevenItemPlacer(): HorizontalAxis.ItemPlacer {
  val defaultPlacer = remember { HorizontalAxis.ItemPlacer.aligned() }
  return remember {
    object : HorizontalAxis.ItemPlacer by defaultPlacer {
      override fun getLabelValues(
        context: CartesianDrawingContext,
        visibleXRange: ClosedFloatingPointRange<Double>,
        fullXRange: ClosedFloatingPointRange<Double>,
        maxLabelWidth: Float,
      ): List<Double> = multiplesOfStepInRange(fullXRange, MULTIPLE_STEP)

      override fun getLineValues(
        context: CartesianDrawingContext,
        visibleXRange: ClosedFloatingPointRange<Double>,
        fullXRange: ClosedFloatingPointRange<Double>,
        maxLabelWidth: Float,
      ): List<Double> = multiplesOfStepInRange(fullXRange, MULTIPLE_STEP)
    }
  }
}

private fun multiplesOfStepInRange(
  range: ClosedFloatingPointRange<Double>,
  step: Int,
): List<Double> {
  val start = range.start.toLong()
  val endInclusive = range.endInclusive.toLong()
  val firstMultiple = ceil(start.toDouble() / step).toLong() * step
  if (firstMultiple > endInclusive) return emptyList()
  return generateSequence(firstMultiple) { it + step }
    .takeWhile { it <= endInclusive }
    .map { it.toDouble() }
    .toList()
}

/**
 * Generates random data points for chart demonstration.
 * Returns a pair where first is x coordinates (custom values) and second is y coordinates (data values).
 */
private fun generateData(
  count: Int,
  minValue: Int,
  maxValue: Int,
): Pair<List<Double>, List<Double>> {
  // Generate custom x values (not sequential, can be any values like 1, 5, 7, 9, 11, 12, 15, 16, 18, etc.)
  val xValues = (1..count).map {
    // Generate random x values between 1 and count*2 to create non-sequential x coordinates
    Random.nextInt(1, count * 2).toDouble()
  }.distinct().sorted() // Remove duplicates and sort to maintain order

  // Generate y values for each x coordinate
  val yValues =
    xValues.map { Random.nextFloat() * (maxValue - minValue) + minValue }.map { it.toDouble() }
  return Pair(xValues, yValues)
}

/**
 * Generates example data: x at multiples of [MULTIPLE_STEP] only (no need for all x values),
 * y with high rate of variation (oscillation + noise so values differ more).
 */
private fun generateExampleData(minValue: Int, maxValue: Int): Pair<List<Double>, List<Double>> {
  val xValues = (0..10000 step MULTIPLE_STEP).map { it.toDouble() }
  val range = (maxValue - minValue).toDouble()
  val mid = minValue + range / 2
  val yValues = xValues.map { x ->
    val oscillation = kotlin.math.sin(x / 80) * (range * 0.4)
    val noise = (Random.nextFloat() - 0.5f) * (range * 0.5)
    (mid + oscillation + noise).toDouble().coerceIn(minValue.toDouble(), maxValue.toDouble())
  }
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
  modifier: Modifier = Modifier,
) {
  // Using rememberSaveableCartesianChartModelProducer preserves data across configuration changes AND navigation
  val modelProducer = rememberSaveableCartesianChartModelProducer()

  // Snap behavior toggle

  // Also use saveable scroll and zoom states to preserve chart position
  // Use xStable to prevent scroll state recreation when ranges change
  val initialScroll = remember { Scroll.Absolute.x(70.0) }
  val scrollState = rememberVicoScrollState(
    initialScroll = initialScroll,
    snapBehaviorConfig = SnapBehaviorConfig(
      snapToLabelFunction = { currentXLabel, isDrag, isForward ->
        if (currentXLabel == null) return@SnapBehaviorConfig 0.0

        val step = MULTIPLE_STEP.toDouble()
        return@SnapBehaviorConfig if (!isDrag) {
          val requiredMultiple =
            if (isForward) ceil(currentXLabel / step) * step else floor(currentXLabel / step) * step
          Log.i(
            "SnapFunction",
            "Current X Label: $currentXLabel, Next $MULTIPLE_STEP multiples: $requiredMultiple",
          )
          requiredMultiple
        } else {
          (currentXLabel / step).roundToInt().toDouble() * step
        }
      },
    ),
  )

  val zoomState = rememberVicoZoomState(
    zoomEnabled = false,
  )

  // Y range state - these affect both chart display and interpolation
  var minY: Int? by remember { mutableStateOf(0) }
  var maxY: Int? by remember { mutableStateOf(15) }

  // Coroutine scope for programmatic scrolling
  val coroutineScope = rememberCoroutineScope()

  val (xData, yData) = remember {
    generateExampleData(0, 15)
  }
  val markerValue = 100.0

  // Update chart when Y ranges change
  LaunchedEffect(minY, maxY) {
    modelProducer.runTransaction {
      lineSeries {
        series(
          x = xData,
          y = yData,
          ranges = CartesianRangeValues(
            minX = 0.0,
            maxX = 10000.0,
            minY = minY?.toDouble() ?: 0.0,
            maxY = maxY?.toDouble() ?: 15.0,
          ),
        )
      }
    }
  }

  var markerIndex: Double? by remember { mutableStateOf(null) }

  // State to display click results
  var clickResult by remember { mutableStateOf("Click on chart to see results") }

  val markerDecoration = rememberMarkerDecoration(markerValue)

  val primaryLayer = rememberLineCartesianLayer(
    verticalAxisPosition = Axis.Position.Vertical.Start,
    lineProvider = LineCartesianLayer.LineProvider.series(
      LineCartesianLayer.rememberLine(
        pointConnector = LineCartesianLayer.PointConnector.monotone(),
      ),
    ),
    rangeProvider = CartesianLayerRangeProvider.fixed(
      minY = minY?.toDouble(),
      maxY = maxY?.toDouble(),
    ),
  )

  val horizontalItemPlacer = rememberMultiplesOfSevenItemPlacer()


  val marker = rememberDefaultCartesianMarker(
    label = rememberTextComponent(),
    guideline = rememberAxisLineComponent(
      fill = fill(Color.Green),
      thickness = 2.dp,
    ),
    contentPadding = insets(bottom = 20.dp),
    indicator = { color ->
      ShapeComponent(
        fill = fill(color),
        strokeFill = fill(color),
        shape = CorneredShape.Pill,
        strokeThicknessDp = 0f,
      )
    },
    yLabelCallback = { yLabelText ->
      // Handle the formatted Y label text
      Log.i("Y_LABEL", "Received: $yLabelText")
    },
    interpolationType = InterpolationType.CUBIC,
    curvature = 0.5f,
  )

  Column(
    modifier = Modifier.verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(
      text = "Saveable Chart State Demo",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
    )

    Text(
      text = "Screen 1 of 2",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Button(onClick = onNavigateToDynamicYRange) {
      Icon(
        Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = "Navigate to Dynamic Y Range Example",
      )
      Text("Navigate to Dynamic Y Range Example")
    }

    Text(
      text = "✨ Smart Snap Behavior - Drag to snap to nearest data point, fling to jump to next/previous data point!",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.primary,
      fontWeight = FontWeight.Medium,
    )

    Button(
      onClick = {
        minY = (0..10).random()
        maxY = (15..30).random()
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Change Y Range (Min: $minY, Max: $maxY)")
    }

    Button(
      onClick = {
        markerIndex = 4.0
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Reset Y Range")
    }

    Button(
      onClick = {
        coroutineScope.launch {
          // Programmatically scroll to 0.0 using VicoScrollState's animateScroll
          scrollState.animateScroll(Scroll.Absolute.xStable(0.0))
        }
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Scroll to 0.0 (Programmatic)")
    }

    // Click Results Display
    Text(
      text = "Click Results:",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
    )

    Text(
      text = clickResult,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
    )
    CartesianChartHost(
      modifier = Modifier
        .background(Color.LightGray)
        .fillMaxWidth()
        .height(300.dp),
      chart = rememberCartesianChart(

        primaryLayer,
        startAxis = VerticalAxis.rememberStart(
          guideline = null,
          label = null,
          size = BaseAxis.Size.scroll(20.dp, true),
          tickLength = 0.dp,
          tick = null,
        ),
        topAxis = HorizontalAxis.rememberTop(
          label = null,
          tick = null,
          tickLength = 0.dp,
        ),
        endAxis = VerticalAxis.rememberEnd(
          size = BaseAxis.Size.scroll(40.dp, false),
          markerDecoration = markerDecoration,
          tickLength = 0.dp,
          tick = null,
        ),
        visibleLabelsCount = 4.0,
        bottomAxis = HorizontalAxis.rememberBottom(
          itemPlacer = horizontalItemPlacer,
          tick = rememberAxisGuidelineComponent(),
          tickLength = 20.dp,
          horizontalLabelPosition = Position.Horizontal.End,
        ),
        marker = rememberDefaultCartesianMarker(
          label = rememberTextComponent(color = Color.Transparent),
          valueFormatter = emptyFormatter(),
        ),
        onChartClick = { targets, click ->
          if (click == null) {
            markerIndex = null
          } else {
            val targetMarkerIndex =
              getTargetPoints(
                scrollState.getVisibleAxisLabels(itemPlacer = horizontalItemPlacer),
                targets,
                click,
              )
            markerIndex = targetMarkerIndex.first()
          }
        },
        getXStep = {
          7.0
        },
        layerPadding = {
          CartesianLayerPadding(
            unscalableStartDp = 0f,
          )
        },
        persistentMarkers = remember(markerIndex) {
          {
            markerIndex?.let { marker at it.toDouble() }
          }
        },
      ),
      onScrollStopped = { visibleRange ->
        Log.i("SCROLL_CALLBACK", "Scroll stopped! Visible range: $visibleRange")
        // You can now safely use the visible range for calculations
        // without causing circular dependency
        Log.i(
          "testing",
          scrollState.getInterpolatedYValues(
            scrollState.getVisibleAxisLabels(),
          ).toString(),
        )
        visibleRange?.let { range ->
          val start = range.visibleXRange.start
          val end = range.visibleXRange.endInclusive
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
          }
        }
      },
      animateIn = true,
      modelProducer = modelProducer,
      scrollState = scrollState,
      zoomState = zoomState,
    )

    Text(
      text = "Saveable Chart State Demo",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
    )

    Text(
      text = "Screen 1 of 2",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Button(onClick = onNavigateToDynamicYRange) {
      Icon(
        Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = "Navigate to Dynamic Y Range Example",
      )
      Text("Navigate to Dynamic Y Range Example")
    }

    Text(
      text = "✨ Smart Snap Behavior - Drag to snap to nearest data point, fling to jump to next/previous data point!",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.primary,
      fontWeight = FontWeight.Medium,
    )

    Button(
      onClick = {
        minY = (0..10).random()
        maxY = (15..30).random()
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Change Y Range (Min: $minY, Max: $maxY)")
    }

    Button(
      onClick = {
        markerIndex = 4.0
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Reset Y Range")
    }


    Text(
      text = "Saveable Chart State Demo",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
    )

    Text(
      text = "Screen 1 of 2",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Button(onClick = onNavigateToDynamicYRange) {
      Icon(
        Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = "Navigate to Dynamic Y Range Example",
      )
      Text("Navigate to Dynamic Y Range Example")
    }

    Text(
      text = "✨ Smart Snap Behavior - Drag to snap to nearest data point, fling to jump to next/previous data point!",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.primary,
      fontWeight = FontWeight.Medium,
    )

    Button(
      onClick = {
        minY = (0..10).random()
        maxY = (15..30).random()
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Change Y Range (Min: $minY, Max: $maxY)")
    }

    Button(
      onClick = {
        markerIndex = 4.0
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Reset Y Range")
    }

  }
}

/**
 * Internal helper to remember the empty value formatter for the marker.
 */
@Composable
private fun emptyFormatter(): DefaultCartesianMarker.ValueFormatter =
  remember {
    object : DefaultCartesianMarker.ValueFormatter {
      override fun format(
        context: CartesianDrawingContext,
        targets: List<CartesianMarker.Target>,
      ): String {
        return ""
      }
    }
  }


fun getTargetPoints(fullList: List<Double>, points: List<Double>, input: Double): List<Double> {
  if (fullList.isEmpty()) return emptyList()

  // find lower and upper bound from full list
  val lower = fullList.filter { it <= input }.maxOrNull()
  val upper = fullList.filter { it >= input }.minOrNull()

  // edge case: if input is outside range
  if (lower == null && upper == null) return emptyList()
  if (lower == null) return listOfNotNull(upper)
  if (upper == null) return listOfNotNull(lower)

  // filter targets within the upper and lower bound
  val filteredTargets = points.filter { it in lower..upper }


  return when {
    filteredTargets.isEmpty() -> {
      val halfway = (lower + upper) / 2.0

      // check halfway condition to return lower or upper
      if (input < halfway) {
        listOf(lower)
      } else {
        listOf(upper)
      }
    }

    filteredTargets.size == 1 -> {
      val target = filteredTargets.first()
      val halfway = (upper - lower) / 2.0

      // check if rounding of the point meets the target
      if (kotlin.math.abs(target - input) < halfway) {
        listOf(target)
      } else if (target > input) {
        listOf(lower)
      } else {
        listOf(upper)
      }
    }

    else -> {
      // return the nearest target to the point
      val nearestTarget = filteredTargets.minByOrNull { kotlin.math.abs(it - input) }
      listOfNotNull(nearestTarget)
    }
  }
}


@Composable
private fun rememberMarkerDecoration(markerValue: Double): VerticalAxis.MarkerDecoration {
  val fill = fill(Color(0xFF458239))
  rememberLineComponent(fill = fill(Color(0xFF458239)), thickness = 2.dp)
  val labelComponent =
    rememberTextComponent(
      typeface = Typeface.DEFAULT_BOLD,
      color = Color(0xfffdc8c4),
      padding = insets(horizontal = 6.dp, vertical = 2.dp),
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
