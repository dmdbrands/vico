/*
 * Copyright 2026 by Patryk Goworowski and Patrick Michalik.
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

package com.patrykandpatrick.vico.sample.charts.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.ListItemPlacer
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.ScrollAwareRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.cartesian.data.rememberScrollAwareRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberScrubMarkerController
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * Test chart for DMD Brands custom vico features.
 * Each section below tests a specific feature.
 *
 * **Feature 1: ScrollAwareRangeProvider**
 * The chart shows 200 data points with 3 distinct regions:
 * - Points 0-60: values around 150-170 (low weight region)
 * - Points 60-130: values around 175-195 (high weight region)
 * - Points 130-200: values around 155-165 (medium weight region)
 *
 * As you scroll through the chart, the Y-axis range dynamically adjusts
 * to the visible data points with animated transitions and nice tick labels.
 */
@Composable
fun DmdBrandsTestChart(modifier: Modifier = Modifier) {
  val modelProducer = remember { CartesianChartModelProducer() }
  // Start scrolled to point 80 (high-value region ~185) to test initial scroll
  val scrollState = rememberVicoScrollState(
    initialScroll = Scroll.Absolute.x(80.0),
  )

  // ScrollAwareRangeProvider with simple nice-scale callback
  val rangeProvider = rememberScrollAwareRangeProvider(
    segmentSize = 10,
    debounceMs = 150,
    animDurationMs = 250,
  ) { visibleMinY, visibleMaxY ->
    // Simple nice scale: round to nearest 5, add padding
    val step = niceStep(visibleMaxY - visibleMinY)
    val niceMin = floor(visibleMinY / step) * step
    val niceMax = ceil(visibleMaxY / step) * step
    val ticks = buildList {
      var tick = niceMin
      while (tick <= niceMax + step * 0.01) {
        add(tick)
        tick += step
      }
    }
    (niceMin..niceMax) to ticks
  }

  // Generate sample data with distinct regions
  LaunchedEffect(Unit) {
    val data = generateWeightData(200)
    modelProducer.runTransaction {
      lineSeries {
        series(x = data.indices.map { it.toDouble() }, y = data)
      }
    }
  }

  Column(modifier = modifier.padding(16.dp)) {
    Text(
      text = "Scroll-Aware Range Demo",
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.padding(bottom = 8.dp),
    )
    Text(
      text = "Scroll the chart — Y-axis adapts to visible data with animation",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(bottom = 16.dp),
    )
    // Feature 2 test: iOS Health-like marker scrubbing
    // - Tap (no movement): toggle marker
    // - Hold 200ms + drag: scrub marker along data, all scroll locked
    // - Horizontal swipe: chart scrolls normally
    // - Release after scrub: marker stays
    // - Scroll after marker visible: marker auto-dismisses
    var selectedMarkerX by remember { mutableStateOf<Double?>(null) }

    val scrubController = rememberScrubMarkerController(
      scrollState = scrollState,
      delayMs = 200L,
      onMarkerIndexChanged = { clickX, targets ->
        // Test: only allow marker on X values that are multiples of 3.
        // Proves callback controls marker positioning (like meApp's getTargetPoints).
        if (clickX == null) {
          selectedMarkerX = null
          null
        } else {
          val nearest = targets
            .filter { it.toLong() % 3 == 0L }
            .minByOrNull { kotlin.math.abs(it - clickX) }
          selectedMarkerX = nearest
          nearest
        }
      },
    )
    CartesianChartHost(
      chart = rememberCartesianChart(
        rememberLineCartesianLayer(rangeProvider = rangeProvider),
        startAxis = VerticalAxis.rememberStart(
          itemPlacer = ListItemPlacer(ticks = { rangeProvider.currentTicks }),
        ),
        bottomAxis = HorizontalAxis.rememberBottom(),
        marker = rememberMarker(),
        markerController = scrubController,
      ),
      modelProducer = modelProducer,
      scrollState = scrollState,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

/** Generates weight-like data with 3 distinct regions to test scroll-aware range. */
private fun generateWeightData(count: Int): List<Double> = List(count) { i ->
  val base = when {
    i < 60 -> 160.0   // Low region
    i < 130 -> 185.0  // High region
    else -> 158.0      // Medium region
  }
  // Add some variation
  base + 8.0 * sin(i * 0.3) + (i % 7) * 0.5
}

/** Returns a nice step size for the given range. Uses 1-2-5 pattern. */
private fun niceStep(range: Double): Double {
  if (range <= 0) return 1.0
  val magnitude = 10.0.pow(floor(kotlin.math.log10(range / 4.0)))
  val normalized = range / 4.0 / magnitude
  val nice = when {
    normalized <= 1.0 -> 1.0
    normalized <= 2.0 -> 2.0
    normalized <= 5.0 -> 5.0
    else -> 10.0
  }
  return nice * magnitude
}
