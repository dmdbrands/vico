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

import android.graphics.Canvas
import android.graphics.RectF
import androidx.annotation.RestrictTo
import com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayer
import com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayerDimensions
import com.patrykandpatrick.vico.core.common.DrawingContext
import kotlin.math.ceil

/** A [DrawingContext] extension with [CartesianChart]-specific data. */
public interface CartesianDrawingContext : DrawingContext, CartesianMeasuringContext {
  /** The bounds of the [CartesianLayer] area. */
  public val layerBounds: RectF

  /** Stores shared [CartesianLayer] dimensions. */
  public val layerDimensions: CartesianLayerDimensions

  /** The scroll value (in pixels). */
  public val scroll: Float

  /** The zoom factor. */
  public val zoom: Float
}

/** @suppress */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun CartesianMeasuringContext.getMaxScrollDistance(
  chartWidth: Float,
  layerDimensions: CartesianLayerDimensions,
): Float =
  ceil(
    (layoutDirectionMultiplier * (layerDimensions.getContentWidth(this) - chartWidth)).run {
      if (isLtr) coerceAtLeast(0f) else coerceAtMost(0f)
    }
  )

internal fun CartesianDrawingContext.getMaxScrollDistance() =
  getMaxScrollDistance(layerBounds.width(), layerDimensions)

internal fun CartesianDrawingContext.getVisibleXRange(): ClosedFloatingPointRange<Double> {
  val fullRange = getFullXRange(layerDimensions)
  val start =
    fullRange.start + layoutDirectionMultiplier * scroll / layerDimensions.xSpacing * ranges.xStep
  val end = start + layerBounds.width() / layerDimensions.xSpacing * ranges.xStep
  return start..end
}

/**
 * Returns the exact x value at the given click position.
 * This converts pixel x coordinate to the actual data x value, accounting for scroll.
 */
internal fun CartesianDrawingContext.getExactXValue(clickXPosition: Double): Double? {
  val visibleXRange = getVisibleXRange()

  // Convert pixel x coordinate to data x coordinate, accounting for scroll
  // This uses the same logic as getVisibleXRange() but in reverse
  val relativeX = (clickXPosition - layerBounds.left) / layerDimensions.xSpacing
  val dataX = visibleXRange.start + relativeX * ranges.xStep

  // Return the exact data x value if it's within the visible range
  return if (dataX >= visibleXRange.start && dataX <= visibleXRange.endInclusive) {
    dataX
  } else {
    null
  }
}

/** @suppress */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun CartesianDrawingContext(
  measuringContext: CartesianMeasuringContext,
  canvas: Canvas,
  layerDimensions: CartesianLayerDimensions,
  layerBounds: RectF,
  scroll: Float,
  zoom: Float,
): CartesianDrawingContext =
  object : CartesianDrawingContext, CartesianMeasuringContext by measuringContext {
    override val layerBounds: RectF = layerBounds

    override var canvas: Canvas = canvas

    override val layerDimensions: CartesianLayerDimensions = layerDimensions

    override val scroll: Float = scroll

    override val zoom: Float = zoom

    override fun withCanvas(canvas: Canvas, block: () -> Unit) {
      val originalCanvas = this.canvas
      this.canvas = canvas
      block()
      this.canvas = originalCanvas
    }
  }
