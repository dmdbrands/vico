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

package com.patrykandpatrick.vico.compose.cartesian.axis

import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.core.cartesian.axis.Axis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.common.Position
import com.patrykandpatrick.vico.core.common.half
import kotlin.math.max

/**
 * A [VerticalAxis.ItemPlacer] that returns a fixed list of label values supplied by [ticks].
 * Gives full control over which Y values appear as axis labels — no internal step or count
 * recomputation. Designed for pairing with
 * [com.patrykandpatrick.vico.compose.cartesian.data.ScrollAwareRangeProvider]: pass
 * `provider::currentTicks` and the axis automatically tracks the live, scroll-driven range.
 *
 * Ported from vico 4.0.x to vico 3.0.x (dmdbrands fork).
 *
 * @param ticks lambda returning the current list of Y values to display as labels.
 * @param shiftTopLines whether to shift the top guideline down by the line thickness.
 */
public class ListItemPlacer(
  private val ticks: () -> List<Double>,
  private val shiftTopLines: Boolean = true,
) : VerticalAxis.ItemPlacer {

  // Cache: filter once per (range, ticks identity), reuse across getLabelValues + measurement
  // calls in the same frame. Avoids repeated O(n) filtering during a single layout pass.
  private var cachedRange: Pair<Double, Double>? = null
  private var cachedFiltered: List<Double> = emptyList()
  private var cachedTicksIdentity: List<Double>? = null

  private fun getFilteredTicks(minY: Double, maxY: Double): List<Double> {
    val current = ticks()
    if (cachedRange?.first == minY &&
      cachedRange?.second == maxY &&
      cachedTicksIdentity === current
    ) {
      return cachedFiltered
    }
    val filtered = current.filter { it >= minY - EPSILON && it <= maxY + EPSILON }
    cachedRange = minY to maxY
    cachedFiltered = filtered
    cachedTicksIdentity = current
    return filtered
  }

  override fun getShiftTopLines(context: CartesianDrawingContext): Boolean = shiftTopLines

  override fun getLabelValues(
    context: CartesianDrawingContext,
    axisHeight: Float,
    maxLabelHeight: Float,
    position: Axis.Position.Vertical,
  ): List<Double> {
    val yRange = context.ranges.getYRange(position)
    return getFilteredTicks(yRange.minY, yRange.maxY)
  }

  override fun getWidthMeasurementLabelValues(
    context: CartesianMeasuringContext,
    axisHeight: Float,
    maxLabelHeight: Float,
    position: Axis.Position.Vertical,
  ): List<Double> {
    val yRange = context.ranges.getYRange(position)
    return getFilteredTicks(yRange.minY, yRange.maxY).ifEmpty { listOf(yRange.minY, yRange.maxY) }
  }

  override fun getHeightMeasurementLabelValues(
    context: CartesianMeasuringContext,
    position: Axis.Position.Vertical,
  ): List<Double> {
    val yRange = context.ranges.getYRange(position)
    return getFilteredTicks(yRange.minY, yRange.maxY).ifEmpty { listOf(yRange.minY, yRange.maxY) }
  }

  override fun getTopLayerMargin(
    context: CartesianMeasuringContext,
    verticalLabelPosition: Position.Vertical,
    maxLabelHeight: Float,
    maxLineThickness: Float,
  ): Float =
    when (verticalLabelPosition) {
      Position.Vertical.Top ->
        maxLabelHeight + (if (shiftTopLines) maxLineThickness else -maxLineThickness).half
      Position.Vertical.Center ->
        (max(maxLabelHeight, maxLineThickness) +
          if (shiftTopLines) maxLineThickness else -maxLineThickness).half
      else -> if (shiftTopLines) maxLineThickness else 0f
    }

  override fun getBottomLayerMargin(
    context: CartesianMeasuringContext,
    verticalLabelPosition: Position.Vertical,
    maxLabelHeight: Float,
    maxLineThickness: Float,
  ): Float =
    when (verticalLabelPosition) {
      Position.Vertical.Top -> maxLineThickness
      Position.Vertical.Center ->
        (max(maxLabelHeight, maxLineThickness) + maxLineThickness).half
      else -> maxLabelHeight + maxLineThickness.half
    }

  private companion object {
    const val EPSILON = 0.01
  }
}
