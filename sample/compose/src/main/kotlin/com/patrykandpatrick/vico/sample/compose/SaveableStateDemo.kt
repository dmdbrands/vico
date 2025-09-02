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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
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
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.axis.Axis
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.core.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.core.common.Position
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

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

  // Only rebuild data when dataVersion changes (user action), not on configuration changes or navigation
  LaunchedEffect(Unit) {
      // Initialize with some default data
    if (dataVersion == 0) {
      Log.i("CHECKING" , "done")
      modelProducer.runTransaction {
        columnSeries {
          series(5, 6, 5, 2, 11, 8, 5, 2, 15, 11, 8, 13, 12, 10, 2, 7)
        }
        lineSeries {
          series(3, 4, 8, 5, 9, 6, 7, 4, 12, 9, 6, 11, 10, 8, 4, 5)
        }
      }
      dataVersion++
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
      text = "This chart's data AND scroll position persist across:\n" +
            "• Configuration changes (screen rotation)\n" +
            "• Navigation (leaving and returning to this screen)\n" +
            "• Process death and recreation\n\n" +
            "Try scrolling the chart, then navigate away and back, or rotate the device. " +
            "Your position and data will be preserved!",
      style = MaterialTheme.typography.bodyMedium
    )

    Button(
      onClick = { dataVersion++ },
      modifier = Modifier.fillMaxWidth()
    ) {
      Text("Generate New Data (Version: $dataVersion)")
    }

    Text(
      text = "Current scroll position: ${scrollState.value.toInt()}px",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val decoration = rememberHorizontalLine()

    CartesianChartHost(
      chart = rememberCartesianChart(
        rememberColumnCartesianLayer(),
        rememberLineCartesianLayer(),
        startAxis = VerticalAxis.rememberStart(),
        bottomAxis = HorizontalAxis.rememberBottom(
          tick = rememberAxisGuidelineComponent(),
          tickLength = 20.dp,
          horizontalLabelPosition = Position.Horizontal.End
        ),
        decorations = listOf(decoration)
      ),
      animateIn = false,
      modelProducer = modelProducer,
      scrollState = scrollState,
      zoomState = zoomState,
      modifier = Modifier.fillMaxWidth()

    )
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
@Preview
private fun SaveableStateDemoPreview() {
  val modelProducer = remember { CartesianChartModelProducer() }
  // Use `runBlocking` only for previews, which don't support asynchronous execution.
  runBlocking {
    modelProducer.runTransaction {
      columnSeries { series(5, 6, 5, 2, 11, 8, 5, 2, 15, 11, 8, 13, 12, 10, 2, 7) }
      lineSeries { series(3, 4, 8, 5, 9, 6, 7, 4, 12, 9, 6, 11, 10, 8, 4, 5) }
    }
  }
  PreviewBox { SaveableStateDemo() }
}
