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
import com.patrykandpatrick.vico.core.common.getStart
import kotlin.math.ceil

/** A [DrawingContext] extension with [CartesianChart]-specific data. */
public interface CartesianDrawingContext : DrawingContext, CartesianMeasuringContext {
  /** The bounds of the [CartesianLayer] area. */
  public val layerBounds: RectF

  /** Stores shared [CartesianLayer] dimensions. */
  public val layerDimensions: CartesianLayerDimensions

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
 * Visible-window padding in pixels (start, end). Computed from [CartesianLayerPadding] xStep
 * values and current [CartesianLayerDimensions.xSpacing]. Used when drawing so the visible
 * window shows [gap][first visible]…[last visible][gap]. Returns (0f, 0f) when xSpacing is 0
 * or padding xStep values are non-positive.
 * At full range start: no start padding (no gap before first data). At full range end: no end padding (no gap after last data).
 */
internal fun CartesianDrawingContext.getVisibleWindowPaddingPx(): Pair<Float, Float> =
  layerPadding.run {
    val xSpacing = layerDimensions.xSpacing
    if (xSpacing <= 0f) return 0f to 0f
    val startPx =
      (visibleStartPaddingXStep * xSpacing).toFloat().coerceAtLeast(0f)
    val endPx =
      (visibleEndPaddingXStep * xSpacing).toFloat().coerceAtLeast(0f)
    val viewport = getVisibleXRange()
    val fullRange = getFullXRange(layerDimensions)
    val epsilon = 1e-9 * kotlin.math.max(ranges.xStep, 1.0)
    val atFullRangeStart = viewport.start <= fullRange.start + epsilon
    val atFullRangeEnd = viewport.endInclusive >= fullRange.endInclusive - epsilon
    // Start: no gap before first data. End: no gap after last data. Middle: apply both paddings.
    val effectiveStartPx = when {
      atFullRangeStart -> 0f
      else -> startPx
    }
    val effectiveEndPx = when {
      atFullRangeEnd -> 0f
      else -> endPx
    }
    effectiveStartPx to effectiveEndPx
  }

/**
 * Returns the canvas x coordinate for a given data x value.
 * Uses visible-window mapping when visible padding is enabled (so marker/fallback positions match the line).
 */
internal fun CartesianDrawingContext.getCanvasXFromDataX(dataX: Double): Float {
  val (visibleStartPx, visibleEndPx) = getVisibleWindowPaddingPx()
  val useVisibleWindowPadding = visibleStartPx > 0f || visibleEndPx > 0f
  val visibleXRange = if (useVisibleWindowPadding) getVisibleXRange() else null
  val visibleSpan = visibleXRange?.let { it.endInclusive - it.start } ?: 0.0
  val dataWidthPx =
    if (useVisibleWindowPadding && visibleSpan > 0)
      (layerBounds.width() - visibleStartPx - visibleEndPx).coerceAtLeast(0f)
    else
      0f
  val effectiveXSpacing =
    if (useVisibleWindowPadding && visibleSpan > 0 && dataWidthPx > 0f)
      dataWidthPx / (visibleSpan / ranges.xStep).toFloat()
    else
      null
  val useVisibleMapping =
    useVisibleWindowPadding &&
      visibleXRange != null &&
      effectiveXSpacing != null &&
      effectiveXSpacing > 0f
  val drawingStart =
    if (useVisibleMapping)
      layerBounds.getStart(isLtr = isLtr) + layoutDirectionMultiplier * visibleStartPx
    else
      layerBounds.getStart(isLtr = isLtr) -
        scroll + layoutDirectionMultiplier * layerDimensions.startPadding
  val xSpacing =
    if (useVisibleMapping) effectiveXSpacing!! else layerDimensions.xSpacing
  val refMinX = if (useVisibleMapping) visibleXRange!!.start else ranges.minX
  return drawingStart +
    layoutDirectionMultiplier * xSpacing * ((dataX - refMinX) / ranges.xStep).toFloat()
}

/**
 * Returns the exact x value at the given click position.
 * This converts pixel x coordinate to the actual data x value, accounting for scroll and visible-window padding.
 */
internal fun CartesianDrawingContext.getExactXValue(clickXPosition: Double): Double? {
  val visibleXRange = getVisibleXRange()
  val (visibleStartPx, visibleEndPx) = getVisibleWindowPaddingPx()
  val useVisibleWindowPadding = visibleStartPx > 0f || visibleEndPx > 0f
  val visibleSpan = visibleXRange.endInclusive - visibleXRange.start
  val dataWidthPx =
    if (useVisibleWindowPadding && visibleSpan > 0)
      (layerBounds.width() - visibleStartPx - visibleEndPx).coerceAtLeast(0f)
    else
      0f
  val effectiveXSpacing =
    if (useVisibleWindowPadding && visibleSpan > 0 && dataWidthPx > 0f)
      dataWidthPx / (visibleSpan / ranges.xStep).toFloat()
    else
      null
  val useVisibleMapping =
    useVisibleWindowPadding &&
      effectiveXSpacing != null &&
      effectiveXSpacing > 0f
  val drawingStart =
    if (useVisibleMapping)
      layerBounds.getStart(isLtr = isLtr) + layoutDirectionMultiplier * visibleStartPx
    else
      layerBounds.getStart(isLtr = isLtr) -
        scroll + layoutDirectionMultiplier * layerDimensions.startPadding
  val dataX =
    if (useVisibleMapping)
      visibleXRange.start +
        (clickXPosition - drawingStart) / (layoutDirectionMultiplier * effectiveXSpacing!!) * ranges.xStep
    else
      visibleXRange.start +
        (clickXPosition - layerBounds.left) / layerDimensions.xSpacing * ranges.xStep

  return if (dataX >= visibleXRange.start && dataX <= visibleXRange.endInclusive) {
    dataX
  } else {
    null
  }
}

/**
 * Returns a list of x-axis label values that are currently visible in the chart.
 * This function can use either the provided ItemPlacer (for accurate results) or fall back to
 * simple step-based calculation.
 *
 * @param itemPlacer optional ItemPlacer used by the HorizontalAxis for accurate label calculation
 * @param maxLabelWidth the maximum label width for calculations (used with ItemPlacer)
 * @param stepMultiplier optional multiplier for the step size when ItemPlacer is not provided
 * @return List of Double values representing the x-coordinates of visible axis labels
 */
public fun CartesianDrawingContext.getVisibleAxisLabels(
  itemPlacer: com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis.ItemPlacer? = null,
  maxLabelWidth: Float = 0f,
  stepMultiplier: Double = 1.0,
): List<Double> {
  val fullXRange = getFullXRange(layerDimensions)
  val visibleXRange = getVisibleXRange()

  return if (itemPlacer != null) {
    // Use ItemPlacer for accurate label calculation
    itemPlacer.getLabelValues(this, visibleXRange, fullXRange, maxLabelWidth)
  } else {
    // Fall back to simple step-based calculation
    val step = ranges.xStep * stepMultiplier
    val allLabels = mutableListOf<Double>()
    var currentValue = ranges.minX

    while (currentValue <= ranges.maxX) {
      allLabels.add(currentValue)
      currentValue += step
    }

    // Return the sublist that falls within the visible range
    allLabels.filter { it >= visibleXRange.start && it <= visibleXRange.endInclusive }
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
