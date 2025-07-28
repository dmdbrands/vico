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

import android.util.Log
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
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.fixed
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.continuous
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineWithConnectionCondition
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.fill
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
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.data.MutableCartesianChartRanges
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.Insets
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import kotlinx.coroutines.flow.debounce
import kotlin.math.sqrt


/**
 * Example demonstrating conditional point connections where points are only connected
 * when x1 is odd and x2 is even, combined with dynamic Y range updates.
 */
@Composable
fun DynamicYRangeExample() {
  // Sample data
  val xLabels = (1..50).toList()
  val ySeries = listOf(
    (1..50).map { it * 2 + (it % 5) * 15 },
  )

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
    LineCartesianLayer.rememberLineWithConnectionCondition(
      fill = LineCartesianLayer.LineFill.single(fill(Color(0xFF2196F3))),
      stroke = LineCartesianLayer.LineStroke.continuous(thickness = 3.dp),
      connectionCondition = { previousEntry, currentEntry ->
        // Check if previous x is odd and current x is even
        val isPreviousOdd = previousEntry.x.toLong() % 2 == 1L
        val isCurrentEven = currentEntry?.x?.toLong()?.let { it % 2 == 0L } ?: false
        isPreviousOdd && isCurrentEven
      }
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
    },
    verticalAxisPosition = Axis.Position.Vertical.Start,
  )

  val scrollState = rememberVicoScrollState()

  val horizontalItemPlacer = horizontalItemPlacer{min, max ->
    minTarget = min
    maxTarget = max
  }

  // Create the chart
  val chart = rememberCartesianChart(
    lineLayer,
    startAxis = VerticalAxis.rememberStart(
      label = rememberTextComponent(padding = Insets(endDp = 16f)),
      line = null,
      size = BaseAxis.Size.fixed(40.dp),
      guideline = null,
      itemPlacer = step(
        step = {(manualMaxY - manualMinY).div(6).toDouble()},
      ),
      tickLength = 0.dp,
    ),
    bottomAxis = HorizontalAxis.rememberBottom(
      itemPlacer = horizontalItemPlacer,
    ),
  )

  // Initialize chart data
  LaunchedEffect(Unit) {
    modelProducer.runTransaction {
      lineSeries {
        ySeries.forEach { y ->
          series(
            x = xLabels.map { it.toLong() },
            y = y.map { it.toDouble() },
          )
        }
      }
    }
  }

  LaunchedEffect(Unit) {
    snapshotFlow { minTarget to maxTarget }
      .debounce(500) // wait for 500ms of inactivity
      .collect { (min, max) ->
        if (min != null && max != null) {
          try {
            val subList = ySeries.first().subList(min.toInt(), max.toInt() + 1)
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
      Text("Conditional Point Connection Example")
      Text("Points connect only when x1 is odd and x2 is even",
           style = MaterialTheme.typography.bodySmall)
      Text("Current Y Range: ${manualMinY.toInt()} - ${manualMaxY.toInt()}",
           style = MaterialTheme.typography.bodySmall)

      Spacer(modifier = Modifier.height(16.dp))

      // Manual range controls
      Text("Min Y: ${manualMinY.toInt()}")
      Slider(
        value = manualMinY.toFloat(),
        onValueChange = { manualMinY = it.toDouble() },
        valueRange = 0f..50f,
        modifier = Modifier.fillMaxWidth()
      )

      Text("Max Y: ${manualMaxY.toInt()}")
      Slider(
        value = manualMaxY.toFloat(),
        onValueChange = { manualMaxY = it.toDouble() },
        valueRange = 50f..200f,
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
