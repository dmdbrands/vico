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

package com.patrykandpatrick.vico.core.cartesian

import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartRanges
import com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayer
import com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayerDimensions
import com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayerPadding
import com.patrykandpatrick.vico.core.common.MeasuringContext
import com.patrykandpatrick.vico.core.common.Point

/** A [MeasuringContext] extension with [CartesianChart]-specific data. */
public interface CartesianMeasuringContext : MeasuringContext {
  /** Stores the [CartesianChart]’s data. */
  public val model: CartesianChartModel

  /** Stores the [CartesianChart]’s _x_ and _y_ ranges. */
  public val ranges: CartesianChartRanges

  /** Whether scroll is enabled. */
  public val scrollEnabled: Boolean

  /** Whether zoom is enabled. */
  public val zoomEnabled: Boolean

  /** Stores the [CartesianLayer] padding values. */
  public val layerPadding: CartesianLayerPadding

  /** The pointer position. */
  public val pointerPosition: Point?
}

public fun CartesianMeasuringContext.getFullXRange(layerDimensions: CartesianLayerDimensions) =
  layerDimensions.run {
    val start = ranges.minX - startPadding / xSpacing * ranges.xStep
    val end = ranges.maxX + endPadding / xSpacing * ranges.xStep
    start..end
  }

public fun CartesianMeasuringContext.getVisibleXRange(
  bounds: android.graphics.RectF,
  layerDimensions: CartesianLayerDimensions,
  scroll: Float,
): ClosedFloatingPointRange<Double> {
  val fullRange = getFullXRange(layerDimensions)
  val start =
    fullRange.start + layoutDirectionMultiplier * scroll / layerDimensions.xSpacing * ranges.xStep
  val end = start + bounds.width() / layerDimensions.xSpacing * ranges.xStep
  return start..end
}

/**
 * Returns a list of x-axis label values that are currently visible in the chart.
 * This function calculates which axis labels would be displayed based on the current
 * visible range and chart dimensions.
 *
 * @param bounds the chart bounds
 * @param layerDimensions the layer dimensions
 * @param scroll the current scroll value
 * @param stepMultiplier optional multiplier for the step size (defaults to 1.0)
 * @return List of Double values representing the x-coordinates of visible axis labels
 */
public fun CartesianMeasuringContext.getVisibleAxisLabels(
  bounds: android.graphics.RectF,
  layerDimensions: CartesianLayerDimensions,
  scroll: Float,
  stepMultiplier: Double = 1.0,
): List<Double> {
  val fullXRange = getFullXRange(layerDimensions)
  val visibleXRange = getVisibleXRange(bounds, layerDimensions, scroll)
  val step = ranges.xStep * stepMultiplier

  // Generate all possible label values for the full range
  val allLabels = mutableListOf<Double>()
  var currentValue = ranges.minX

  while (currentValue <= ranges.maxX) {
    allLabels.add(currentValue)
    currentValue += step
  }

  // Return the sublist that falls within the visible range
  return allLabels.filter { it >= visibleXRange.start && it <= visibleXRange.endInclusive }
}
