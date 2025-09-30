/*
 * Copyright 2025 by Patrykandpatrick Goworowski and Patrick Michalik.
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

import android.R.attr.stepSize
import android.graphics.Typeface
import android.util.Log
import android.webkit.WebSettings
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.fixed
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberTop
import com.patrykandpatrick.vico.compose.cartesian.layer.continuous
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineWithConnectionCondition
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.component.fixed
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.component.shapeComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.common.insets
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.axis.Axis
import com.patrykandpatrick.vico.core.cartesian.axis.BaseAxis
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis.ItemPlacer.Companion.step
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartRanges.Empty.maxX
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartRanges.Empty.minX
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.data.MutableCartesianChartRanges
import com.patrykandpatrick.vico.core.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.core.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.common.Insets
import com.patrykandpatrick.vico.core.common.Position
import com.patrykandpatrick.vico.core.common.component.ShapeComponent
import com.patrykandpatrick.vico.core.common.component.TextComponent
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.times


/**
 * Example demonstrating conditional point connections where points are only connected
 * when x1 is odd and x2 is even, combined with dynamic Y range updates.
 */
@Composable
fun DynamicYRangeExample(
  onNavigateBack: () -> Unit = {}
) {
  // Sample data
  // Sample data
  val xLabels = List(50) {
    (1..100).random().toLong()
  }
  val ySeries =
    List(30) { index ->
      (50..200).random().toDouble()
    }


  // State for manual Y range control
  var manualMinY by remember { mutableStateOf(0.0) }
  var manualMaxY by remember { mutableStateOf(100.0) }

  var minTarget by remember { mutableStateOf<Long?>(null) }
  var maxTarget by remember { mutableStateOf<Long?>(null) }

  // Create the model producer
    val modelProducer = remember { CartesianChartModelProducer() }

  // State to track when ranges should be updated
  var rangeUpdateTrigger by remember { mutableStateOf(0) }





                // Create line with connection condition that only connects when x1 is odd and x2 is even
  val lineWithCondition =
    LineCartesianLayer.rememberLine(
      fill = LineCartesianLayer.LineFill.single(fill(Color(0xFF2196F3))),
      stroke = LineCartesianLayer.LineStroke.continuous(thickness = 3.dp),
      pointConnector = LineCartesianLayer.PointConnector.cubic(0.5f),
      pointProvider = LineCartesianLayer.PointProvider.single(
        point = LineCartesianLayer.Point(
          rememberShapeComponent(
            fill(Color(0xFF2196F3)),
            CorneredShape.Pill,
            strokeThickness = 2.dp,
          ),
        ),
      ),
    )


  // Create the line layer with custom range provider and connection condition
  val lineLayer = rememberLineCartesianLayer(
    lineProvider = LineCartesianLayer.LineProvider.series(lineWithCondition),
    rangeProvider = object : CartesianLayerRangeProvider {
      override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore): Double {
        return manualMinY.toDouble()
      }

      override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore): Double {
        return manualMaxY.toDouble()
      }

      override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore): Double {
        return xLabels.minOrNull()?.toDouble() ?: minX
      }

      override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore): Double {
        return xLabels.maxOrNull()?.toDouble() ?: maxX
      }
    },
    verticalAxisPosition = Axis.Position.Vertical.Start,
  )

  val scrollState = rememberVicoScrollState()

  val horizontalItemPlacer = horizontalItemPlacer{min, max ->
    minTarget = min
    maxTarget = max

  }
  val decoration = rememberHorizontalLine()
  val marker = rememberDefaultMarker()

  // Create the chart
  val chart = rememberCartesianChart(
    lineLayer,
    startAxis = VerticalAxis.rememberStart(
      line = null,
      guideline = null,
      tickLength = 0.dp,
    ),
    decorations = listOf(decoration),
    marker = marker,
    persistentMarkers = {
      { marker at 4.0}
    },
    getXStep = {
      1.0
    },

    bottomAxis = HorizontalAxis.rememberBottom(
      valueFormatter =
        CartesianValueFormatter { _, value, _ ->
        value.toInt().toString()
      },
      itemPlacer = horizontalItemPlacer,
      horizontalLabelPosition = Position.Horizontal.Start
    ),
    visibleLabelsCount = 5.0
  )

  // Initialize chart data
  LaunchedEffect(Unit) {
    modelProducer.runTransaction {
      lineSeries {
          series(
            x = xLabels.map { it.toLong() },
            y = ySeries.map { it.toDouble() },
          )
      }
    }
  }

  LaunchedEffect(Unit) {
    snapshotFlow { minTarget to maxTarget }
      .debounce(500) // wait for 500ms of inactivity
      .collect { (min, max) ->
        if (min != null && max != null && min < max) {
          try {
            val subList = ySeries.subList(min.toInt(), max.toInt() + 1)
            manualMinY = subList.minOf { it.toDouble() }
            manualMaxY = subList.maxOf { it.toDouble() }
          }catch (e: IndexOutOfBoundsException) {
            // Ignore
          }
        }
      }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    // Controls
    Column {
      Text(
        text = "Dynamic Y Range Example",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
      )

      Text(
        text = "Screen 2 of 2",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      Text("Dynamic Y Range with Timestamp Data Example")
      Text("Using timestamp data with one-day x-step. Max 7 labels shown.",
           style = MaterialTheme.typography.bodySmall)
      Text("Current Y Range: ${manualMinY.toInt()} - ${manualMaxY.toInt()}",
           style = MaterialTheme.typography.bodySmall)

      Spacer(modifier = Modifier.height(16.dp))

      // Back button
      Button(onClick = onNavigateBack) {
        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        Text("← Back to Saveable State Demo")
      }

      Spacer(modifier = Modifier.height(16.dp))

      // Manual range controls
      Text("Min Y: ${manualMinY.toInt()}")
      Slider(
        value = manualMinY.toFloat(),
        onValueChange = { manualMinY = it.toDouble() },
        valueRange = 0f..100f,
        modifier = Modifier.fillMaxWidth()
      )

      Text("Max Y: ${manualMaxY.toInt()}")
      Slider(
        value = manualMaxY.toFloat(),
        onValueChange = { manualMaxY = it.toDouble() },
        valueRange = 0f..100f,
        modifier = Modifier.fillMaxWidth()
      )
    }

    // Chart
    CartesianChartHost(
      chart = chart,
      modelProducer = modelProducer,
      scrollState = scrollState,
      modifier = Modifier
        .fillMaxWidth()
        .height(300.dp),
    )
  }
}

const val ONE_DAY_MILLIS = 24 * 60 * 60 * 1000L // 86,400,000 milliseconds

@Composable
internal fun horizontalItemPlacer(
  onDestinationUpdate: (Long, Long) -> Unit,
): HorizontalAxis.ItemPlacer {
  val defaultPlacer = HorizontalAxis.ItemPlacer.aligned()
  return remember {

    object : HorizontalAxis.ItemPlacer by defaultPlacer {
      override fun getLabelValues(
        context: CartesianDrawingContext,
        visibleXRange: ClosedFloatingPointRange<Double>,
        fullXRange: ClosedFloatingPointRange<Double>,
        maxLabelWidth: Float,
      ): List<Double> {
          val (min, max) =
            visibleXRange.start.toLong() to visibleXRange.endInclusive.toLong()
          // Only trigger onDestinationUpdate if not the first time for this segment
          onDestinationUpdate(min, max)
        Log.d("ItemPlacer", "min: $min, max: $max")

        return defaultPlacer.getLabelValues(
          context, visibleXRange, fullXRange, maxLabelWidth,
        )
      }
    }
  }
}


@Composable
private fun rememberHorizontalLine(
): Decoration {
  val fill = fill(Color(0xFF458239))
  val line = rememberLineComponent(fill = fill(Color(0xFF458239)), thickness = 2.dp)
  val labelComponent =
    rememberTextComponent(
      typeface = Typeface.DEFAULT_BOLD,
      color = Color(0xfffdc8c4),
      margins = insets(start = (-40).dp),
      padding = insets(horizontal = 8.dp , vertical = 2.dp),
      background =
        shapeComponent(
          fill,
          shape = CorneredShape.Pill,
        ),
    )

  val decoration =
    object : Decoration {
      override fun drawUnderLayers(context: CartesianDrawingContext) {
        HorizontalLine(
          y = { 150.0 },
          line = line.copy(fill = fill(Color.Transparent)),
          labelComponent = labelComponent,
          horizontalLabelPosition = Position.Horizontal.Start,
          verticalLabelPosition = Position.Vertical.Bottom,
          verticalAxisPosition = Axis.Position.Vertical.End,
        ).drawOverLayers(context)
      }
    }
  return remember { decoration }
}


@Composable
internal fun rememberDefaultMarker(
): CartesianMarker {
  val label =
    rememberTextComponent(
      textSize = 14.sp,
      color = Color(0xFF458239),
    )
  val guideline = rememberAxisGuidelineComponent(
    thickness = 1.dp,
    fill = fill(Color(0xFF458239))
  )

  return rememberDefaultCartesianMarker(
    label = label,
    labelPosition = DefaultCartesianMarker.LabelPosition.Top,
    indicator = { color ->
      ShapeComponent(
        fill = fill(color),
        strokeFill = fill(color),
        shape = CorneredShape.Pill,
        strokeThicknessDp = 2f,
      )
    },
    indicatorSize = 10.dp,
    guideline = guideline,
    contentPadding = insets(horizontal = 20.dp, vertical = 40.dp), // Add content padding here
  )
}

private val weekFormatter = SimpleDateFormat("EEE", Locale.ENGLISH)


fun formatTimestampForSegment(
  timestamp: Long,
): String {
  val date = Date(timestamp)
  val formatter =
       weekFormatter
  val result = formatter.format(date)
  return  result.take(1)
}
