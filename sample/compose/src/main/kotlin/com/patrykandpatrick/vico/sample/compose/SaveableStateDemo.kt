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
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberEnd
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
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
 */
private fun generateData(count: Int, minValue: Int, maxValue: Int): List<Float> {
  return (1..count).map { Random.nextFloat() * (maxValue - minValue) + minValue }
}

/**
 * Demo showing how rememberSaveableCartesianChartModelProducer preserves chart data
 * across configuration changes and navigation without requiring manual data recreation.
 */
@Composable
fun SaveableStateDemo(
  onNavigateToDynamicYRange: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  // Using rememberSaveableCartesianChartModelProducer preserves data across configuration changes AND navigation
  val modelProducer = rememberSaveableCartesianChartModelProducer()

  // Also use saveable scroll and zoom states to preserve chart position
  val scrollState = rememberVicoScrollState()
  val zoomState = rememberVicoZoomState()


  var dataVersion by rememberSaveable { mutableIntStateOf(0) }

  var minY by rememberSaveable { mutableIntStateOf(0) }
  var maxY by rememberSaveable { mutableIntStateOf(15) }

  var rangeUpdateTrigger by remember { mutableIntStateOf(0) }
  val visibleRange by scrollState.visibleRange.collectAsState()

  val data = remember { generateData(100, 0, 15).toList() }
  val secondaryData = remember { generateData(100, 5, 25).toList() }

  var showSecondaryLine by rememberSaveable { mutableStateOf(false) }
  var animateIn by remember { mutableStateOf(false) }
  var secondaryMinY by rememberSaveable { mutableIntStateOf(5) }
  var secondaryMaxY by rememberSaveable { mutableIntStateOf(25) }
  var markerValue by rememberSaveable { mutableStateOf(100.0) }


  LaunchedEffect(showSecondaryLine) {
    animateIn = false
    modelProducer.runTransaction {
        lineSeries {
          series(data)
        }
      if(showSecondaryLine)
        lineSeries {
          series(secondaryData)
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
          val visibleData = data.let { data ->
            val startIndex = max(0, min(start.toInt(), data.size - 1))
            val endIndex = max(0, min(end.toInt(), data.size - 1))
            data.subList(startIndex, endIndex + 1)
          }

           if (visibleData.isNotEmpty()) {
             val calculatedMinY = visibleData.minOrNull()?.toInt() ?: 0
             val calculatedMaxY = visibleData.maxOrNull()?.toInt() ?: 20

             // Update the Y range based on visible data
             minY = calculatedMinY - 2
             maxY = calculatedMaxY + 2

             // Calculate secondary range if secondary line is shown
             if (showSecondaryLine) {
               val secondaryVisibleData = secondaryData.let { data ->
                 val startIndex = max(0, min(start.toInt(), data.size - 1))
                 val endIndex = max(0, min(end.toInt(), data.size - 1))
                 data.subList(startIndex, endIndex + 1)
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

    Button(
      onClick = { dataVersion++ },
      modifier = Modifier.fillMaxWidth()
    ) {
      Text("Generate New Data (Version: $dataVersion)")
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
        )
        ,
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
    modelProducer.runTransaction {
      columnSeries { series(*generateData(100, 0, 20).toTypedArray()) }
      lineSeries { series(*generateData(100, 0, 15).toTypedArray()) }
    }
  }
  PreviewBox { SaveableStateDemo() }
}
