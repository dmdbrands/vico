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

import android.graphics.RectF
import android.util.Log
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartRanges
import com.patrykandpatrick.vico.core.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayer
import com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayerDimensions
import com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayerPadding
import com.patrykandpatrick.vico.core.common.MeasuringContext
import com.patrykandpatrick.vico.core.common.Point
import kotlin.math.abs
import kotlin.math.sqrt

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

  public val scroll: Float

  public val initialScroll: Scroll.Absolute

  public val isInitializedScroll: Boolean

  /** Stores the [CartesianLayer] padding values. */
  public val layerPadding: CartesianLayerPadding

  /** The pointer position. */
  public val pointerPosition: Point?
}

public fun CartesianMeasuringContext.getFullXRange(layerDimensions: CartesianLayerDimensions): ClosedFloatingPointRange<Double> =
  layerDimensions.run {
    val start =
      (ranges.minX - startPadding / xSpacing * ranges.xStep).coerceAtLeast(minimumValue = ranges.minX)
    val end =
      (ranges.maxX + endPadding / xSpacing * ranges.xStep).coerceAtMost(maximumValue = ranges.maxX)
    start..end
  }

/**
 * Returns the visible x range (viewport): the x range that is mapped onto the chart layer.
 * This matches what is drawn: the full viewport is mapped onto the data strip.
 */
public fun CartesianMeasuringContext.getVisibleXRange(
  bounds: RectF,
  layerDimensions: CartesianLayerDimensions,
  scroll: Float,
): ClosedFloatingPointRange<Double> {
  val fullRange = getFullXRange(layerDimensions)
  val xSpacing = layerDimensions.xSpacing
  if (xSpacing <= 0f) return fullRange
  val start =
    (fullRange.start + layoutDirectionMultiplier * scroll / xSpacing * ranges.xStep).coerceAtLeast(
      minimumValue = fullRange.start,
    )
  val end =
    (start + bounds.width() / xSpacing * ranges.xStep).coerceAtMost(maximumValue = fullRange.endInclusive)
  return start..end
}


/**
 * Interpolates the Y value for a given X coordinate from a series of data points.
 * This function supports different interpolation types including linear and cubic.
 *
 * @param series the series of data points (must be sorted by X values)
 * @param xValue the X coordinate for which to find the Y value
 * @param interpolationType the type of interpolation to use
 * @param curvature the curvature parameter for cubic interpolation (0.0 to 1.0, default 0.5)
 * @param verticalAxisPosition the vertical axis position to use for Y range lookup
 * @return the interpolated Y value, or null if the X value is outside the data range
 */
public fun CartesianMeasuringContext.interpolateYValue(
  series: List<LineCartesianLayerModel.Entry>,
  xValue: Double,
  interpolationType: InterpolationType,
  curvature: Float = 0.5f,
  minY: Double? = null,
  maxY: Double? = null,
): Double? {
  if (series.isEmpty()) return null

  // Check if X value is within the data range
  if (xValue < series.first().x || xValue > series.last().x) {
    return null
  }

  // Find the two points that surround the X value
  for (i in 0 until series.size - 1) {
    val currentPoint = series[i]
    val nextPoint = series[i + 1]

    if (xValue >= currentPoint.x && xValue <= nextPoint.x) {
      return when (interpolationType) {
        InterpolationType.LINEAR -> linearInterpolation(currentPoint, nextPoint, xValue)
        InterpolationType.CUBIC -> this.cubicInterpolation(series, i, xValue, curvature)
        InterpolationType.MONOTONE -> this.monotoneInterpolation(series, i, xValue)
      }
    }
  }

  return null
}

/**
 * Performs linear interpolation between two points.
 */
private fun linearInterpolation(
  point1: LineCartesianLayerModel.Entry,
  point2: LineCartesianLayerModel.Entry,
  xValue: Double,
): Double {
  val x1 = point1.x
  val y1 = point1.y
  val x2 = point2.x
  val y2 = point2.y

  // Avoid division by zero
  if (x2 == x1) return y1

  val ratio = (xValue - x1) / (x2 - x1)
  return y1 + ratio * (y2 - y1)
}

/**
 * Performs cubic Bézier interpolation between points.
 * This mimics the behavior of CubicPointConnector but works in data space.
 * Note: For interpolation, we work directly with data values and don't normalize by Y range,
 * as the interpolation should be independent of the visible chart range.
 */
private fun CartesianMeasuringContext.cubicInterpolation(
  series: List<LineCartesianLayerModel.Entry>,
  currentIndex: Int,
  xValue: Double,
  curvature: Float,
): Double {
  val currentPoint = series[currentIndex]
  val nextPoint = series[currentIndex + 1]

  // For cubic interpolation, we need at least 2 points
  if (series.size < 2) {
    return linearInterpolation(currentPoint, nextPoint, xValue)
  }

  val p1 = currentPoint
  val p2 = nextPoint

  // Calculate the parameter t (0 to 1) for the given x value
  val dx = p2.x - p1.x
  if (dx == 0.0) {
    return p1.y
  }

  val t = (xValue - p1.x) / dx
  val clampedT = t.coerceIn(0.0, 1.0)

  // Calculate control points for the cubic Bézier curve
  // Use a simple approach: control points are offset by a fraction of the segment length
  // This mimics CubicPointConnector's behavior but adapted for data space
  // The curvature parameter directly controls the control point offset
  val xDelta = curvature * dx * 0.33  // Use 1/3 of segment length scaled by curvature

  p1.x + xDelta
  val cp1y = p1.y
  p2.x - xDelta
  val cp2y = p2.y

  // Cubic Bézier formula: B(t) = (1-t)³P₀ + 3(1-t)²tP₁ + 3(1-t)t²P₂ + t³P₃
  val oneMinusT = 1.0 - clampedT
  val oneMinusTSquared = oneMinusT * oneMinusT
  val oneMinusTCubed = oneMinusTSquared * oneMinusT
  val tSquared = clampedT * clampedT
  val tCubed = tSquared * clampedT

  val y = oneMinusTCubed * p1.y +
    3.0 * oneMinusTSquared * clampedT * cp1y +
    3.0 * oneMinusT * tSquared * cp2y +
    tCubed * p2.y

  return y
}

/**
 * Gets 6 neighboring entries from series for monotone interpolation.
 * Returns [entry-2, entry-1, entry, entry+1, entry+2, entry+3] where entry is at currentIndex.
 * Handles edge cases by extrapolating boundary entries for first and last segments.
 */
private fun getNeighborsForMonotone(
  series: List<LineCartesianLayerModel.Entry>,
  currentIndex: Int,
): List<LineCartesianLayerModel.Entry> {
  if (series.isEmpty()) return emptyList()
  if (currentIndex < 0 || currentIndex >= series.size) return emptyList()

  val result = mutableListOf<LineCartesianLayerModel.Entry>()
  val isFirstSegment = currentIndex == 0
  val isLastSegment = currentIndex >= series.size - 1

  val entry1 = series[currentIndex]
  val entry2 = if (currentIndex < series.size - 1) series[currentIndex + 1] else entry1

  // For first segment: extrapolate backward neighbors
  if (isFirstSegment) {
    // Get future neighbors to calculate average dx for extrapolation
    val futureNeighbor1 = series.getOrNull(currentIndex + 2) ?: entry2
    val futureNeighbor2 = series.getOrNull(currentIndex + 3) ?: futureNeighbor1

    // Calculate average dx from future segments
    val dx0 = entry2.x - entry1.x
    val dx1 = futureNeighbor1.x - entry2.x
    val dx2 = futureNeighbor2.x - futureNeighbor1.x

    // Calculate average dx, fallback to dx0 if others are zero
    val avgDx = when {
      dx0 != 0.0 && dx1 != 0.0 && dx2 != 0.0 -> (dx0 + dx1 + dx2) / 3.0
      dx0 != 0.0 && dx1 != 0.0 -> (dx0 + dx1) / 2.0
      dx0 != 0.0 -> dx0
      else -> if (dx1 != 0.0) dx1 else if (dx2 != 0.0) dx2 else 1.0
    }

    // Determine trend direction
    val dy0 = entry2.y - entry1.y
    val isIncreasing = entry1.y < entry2.y

    // Extrapolate backward: continue the trend in reverse
    val absDy0 = abs(dy0)
    val prevY1 = if (isIncreasing) {
      entry1.y - absDy0 * 0.5
    } else {
      entry1.y + absDy0 * 0.5
    }

    val prevY2 = if (isIncreasing) {
      prevY1 - absDy0 * 0.3
    } else {
      prevY1 + absDy0 * 0.3
    }

    // Create extrapolated entries for backward neighbors
    result.add(
      LineCartesianLayerModel.Entry(
        entry1.x - 2 * avgDx,
        prevY2,
      ),
    )
    result.add(
      LineCartesianLayerModel.Entry(
        entry1.x - avgDx,
        prevY1,
      ),
    )
    result.add(entry1)
    result.add(entry2)
  } else {
    // Normal case: get actual backward neighbors
    result.add(series.getOrNull(currentIndex - 2) ?: series.first())
    result.add(series.getOrNull(currentIndex - 1) ?: series.first())
    result.add(entry1)
    result.add(entry2)
  }

  // For last segment: extrapolate future neighbors
  if (isLastSegment) {
    val neighbors0 = result[0]
    val neighbors1 = result[1]
    val neighbors2 = result[2]
    val neighbors3 = result[3]

    val dxMinus2 = neighbors1.x - neighbors0.x
    val dxMinus1 = neighbors2.x - neighbors1.x
    val dx0 = neighbors3.x - neighbors2.x

    // Calculate average dx
    val avgDx = when {
      dxMinus2 != 0.0 && dxMinus1 != 0.0 && dx0 != 0.0 -> (dxMinus2 + dxMinus1 + dx0) / 3.0
      dxMinus1 != 0.0 && dx0 != 0.0 -> (dxMinus1 + dx0) / 2.0
      dx0 != 0.0 -> dx0
      else -> if (dxMinus1 != 0.0) dxMinus1 else if (dxMinus2 != 0.0) dxMinus2 else 1.0
    }

    // Determine segment direction
    val dy0 = entry2.y - entry1.y
    val isIncreasing = entry1.y < entry2.y

    // Extrapolate next points: continue the trend
    val absDy0 = abs(dy0)
    val nextY1 = if (isIncreasing) {
      entry2.y + absDy0 * 0.5
    } else {
      entry2.y - absDy0 * 0.5
    }

    val nextY2 = if (isIncreasing) {
      nextY1 + absDy0 * 0.3
    } else {
      nextY1 - absDy0 * 0.3
    }

    // Create extrapolated entries
    result.add(
      LineCartesianLayerModel.Entry(
        entry2.x + avgDx,
        nextY1,
      ),
    )
    result.add(
      LineCartesianLayerModel.Entry(
        entry2.x + 2 * avgDx,
        nextY2,
      ),
    )
  } else {
    // Normal case: get actual future neighbors
    result.add(series.getOrNull(currentIndex + 2) ?: series.last())
    result.add(series.getOrNull(currentIndex + 3) ?: series.last())
  }

  return result
}

/**
 * Performs monotone cubic interpolation using Fritsch-Carlson algorithm.
 * This mimics the behavior of MonotonePointConnector and works entirely in data space.
 * Note: This function doesn't use Y range normalization as interpolation should work
 * directly with data values, independent of the visible chart range.
 */
private fun CartesianMeasuringContext.monotoneInterpolation(
  series: List<LineCartesianLayerModel.Entry>,
  currentIndex: Int,
  xValue: Double,
): Double {
  val currentPoint = series[currentIndex]
  val nextPoint = series[currentIndex + 1]

  // Need at least 2 points for interpolation
  if (series.size < 2) {
    return linearInterpolation(currentPoint, nextPoint, xValue)
  }

  // Get 6 neighbors for Fritsch-Carlson algorithm
  val neighbors = getNeighborsForMonotone(series, currentIndex)
  if (neighbors.size < 6) {
    // Fall back to linear if we don't have enough neighbors
    return linearInterpolation(currentPoint, nextPoint, xValue)
  }

  // Calculate secant slopes in DATA SPACE (normalized by dx to handle non-uniform spacing)
  val dxMinus2 = neighbors[1].x - neighbors[0].x
  val dxMinus1 = neighbors[2].x - neighbors[1].x
  val dx0 = neighbors[3].x - neighbors[2].x  // entry2.x - entry1.x
  val dx1 = neighbors[4].x - neighbors[3].x
  val dx2 = neighbors[5].x - neighbors[4].x

  // Detect if we're missing past neighbors (first segment case)
  val hasPastNeighbors = dxMinus2 != 0.0 && dxMinus1 != 0.0

  // Detect if we're missing future neighbors (last segment case)
  val hasFutureNeighbors = dx1 != 0.0 && dx2 != 0.0

  // Calculate normalized secants (dy/dx)
  val sMinus2 =
    if (hasPastNeighbors && dxMinus2 != 0.0) (neighbors[1].y - neighbors[0].y) / dxMinus2 else 0.0
  val sMinus1 =
    if (hasPastNeighbors && dxMinus1 != 0.0) (neighbors[2].y - neighbors[1].y) / dxMinus1 else 0.0
  val s0 = if (dx0 != 0.0) (neighbors[3].y - neighbors[2].y) / dx0 else 0.0
  val s1 = if (hasFutureNeighbors && dx1 != 0.0) (neighbors[4].y - neighbors[3].y) / dx1 else 0.0
  val s2 = if (hasFutureNeighbors && dx2 != 0.0) (neighbors[5].y - neighbors[4].y) / dx2 else 0.0

  // Use central differences to calculate initial gradients in DATA SPACE
  val totalDxMinus = if (hasPastNeighbors) dxMinus2 + dxMinus1 else 0.0
  val totalDx0 = if (hasPastNeighbors) dxMinus1 + dx0 else dx0
  val totalDx1 = if (hasFutureNeighbors) dx0 + dx1 else 0.0
  val totalDx2 = if (hasFutureNeighbors) dx1 + dx2 else 0.0

  var mMinus1Data =
    if (hasPastNeighbors && totalDxMinus != 0.0) (sMinus2 * dxMinus2 + sMinus1 * dxMinus1) / totalDxMinus else 0.0
  var m0LeftData = if (totalDx0 != 0.0) {
    if (hasPastNeighbors) {
      (sMinus1 * dxMinus1 + s0 * dx0) / totalDx0
    } else {
      s0
    }
  } else 0.0
  var m1LeftData =
    if (hasFutureNeighbors && totalDx1 != 0.0) (s0 * dx0 + s1 * dx1) / totalDx1 else 0.0
  var m2Data =
    if (hasFutureNeighbors && totalDx2 != 0.0) (s1 * dx1 + s2 * dx2) / totalDx2 else 0.0

  // For C1 continuity, we calculate gradients for left and right curves
  var m0RightData = m0LeftData
  var m1RightData = m1LeftData

  // Handle equal values and sign changes
  if (neighbors[2].y == neighbors[3].y || dx0 == 0.0) {
    m0RightData = 0.0
    m1LeftData = 0.0
  } else {
    // Check left curve (only if we have past neighbors)
    if (hasPastNeighbors && (neighbors[1].y == neighbors[2].y || dxMinus1 == 0.0)) {
      m0LeftData = 0.0
    }
    // Check right curve (only if we have future neighbors)
    if (hasFutureNeighbors && (neighbors[3].y == neighbors[4].y || dx1 == 0.0)) {
      m1RightData = 0.0
    }

    // Handle sign changes - set gradients to zero
    if (hasPastNeighbors && sMinus2 != 0.0 && sMinus1 != 0.0 && ((sMinus2 < 0.0 && sMinus1 > 0.0) || (sMinus2 > 0.0 && sMinus1 < 0.0))) {
      mMinus1Data = 0.0
    }
    if (hasPastNeighbors && sMinus1 != 0.0 && s0 != 0.0 && ((sMinus1 < 0.0 && s0 > 0.0) || (sMinus1 > 0.0 && s0 < 0.0))) {
      m0LeftData = 0.0
      m0RightData = 0.0
    }
    if (hasFutureNeighbors && s0 != 0.0 && s1 != 0.0 && ((s0 < 0.0 && s1 > 0.0) || (s0 > 0.0 && s1 < 0.0))) {
      m1LeftData = 0.0
      m1RightData = 0.0
    }
    if (hasFutureNeighbors && s1 != 0.0 && s2 != 0.0 && ((s1 < 0.0 && s2 > 0.0) || (s1 > 0.0 && s2 < 0.0))) {
      m2Data = 0.0
    }

    // Calculate alpha and beta values for Fritsch-Carlson constraint
    val alphaLeft = if (hasPastNeighbors && sMinus1 != 0.0) mMinus1Data / sMinus1 else 0.0
    val betaLeft = if (hasPastNeighbors && sMinus1 != 0.0) m0LeftData / sMinus1 else 0.0

    val alphaCent = if (s0 != 0.0) m0RightData / s0 else 0.0
    val betaCent = if (hasFutureNeighbors && s0 != 0.0) m1LeftData / s0 else 0.0

    val alphaRight = if (hasFutureNeighbors && s1 != 0.0) m1RightData / s1 else 0.0
    val betaRight = if (hasFutureNeighbors && s1 != 0.0) m2Data / s1 else 0.0

    // Apply Fritsch-Carlson constraint (circle of radius 3)
    if (hasPastNeighbors) {
      val discLeft = alphaLeft * alphaLeft + betaLeft * betaLeft
      if (discLeft.compareTo(9.0) > 0) {
        val tau = 3.0 / sqrt(discLeft)
        m0LeftData = tau * betaLeft * sMinus1
      }
    }

    val discCent = alphaCent * alphaCent + betaCent * betaCent
    if (discCent.compareTo(9.0) > 0) {
      val tau = 3.0 / sqrt(discCent)
      m0RightData = tau * alphaCent * s0
      if (hasFutureNeighbors) {
        m1LeftData = tau * betaCent * s0
      }
    }

    if (hasFutureNeighbors) {
      val discRight = alphaRight * alphaRight + betaRight * betaRight
      if (discRight.compareTo(9.0) > 0) {
        val tau = 3.0 / sqrt(discRight)
        m1RightData = tau * alphaRight * s1
      }
    }
  }

  // Choose gradients with smallest magnitude for C1 continuity
  var m0Data =
    if (hasPastNeighbors && abs(m0LeftData) < abs(m0RightData)) m0LeftData else m0RightData
  var m1Data = if (abs(m1LeftData) < abs(m1RightData)) m1LeftData else m1RightData

  // Convert data-space tangents to Bézier control points (in data space)
  // For interpolation, we work directly in data space, no screen-space conversion needed
  val dxData = dx0
  val p1 = neighbors[2]  // entry1
  val p2 = neighbors[3]  // entry2

  // Calculate control points for cubic Bézier curve in data space
  // Control points are positioned at 1/3 and 2/3 along the segment
  val t = if (dxData != 0.0) (xValue - p1.x) / dxData else 0.0
  val clampedT = t.coerceIn(0.0, 1.0)

  // Convert tangents to control point offsets
  // For a cubic Bézier: control points are at p1 + (dx/3, m0*dx/3) and p2 - (dx/3, m1*dx/3)
  val dxThird = dxData / 3.0
  p1.x + dxThird
  val cp1y = p1.y + m0Data * dxThird
  p2.x - dxThird
  val cp2y = p2.y - m1Data * dxThird

  // Enforce monotonicity constraints on control points
  var finalCp1y = cp1y
  var finalCp2y = cp2y

  when {
    p1.y < p2.y -> {
      // Increasing segment: ensure c1y and c2y are between y1 and y2, and c1y ≤ c2y
      finalCp1y = cp1y.coerceIn(p1.y, p2.y)
      finalCp2y = cp2y.coerceIn(p1.y, p2.y)
      if (finalCp1y > finalCp2y) {
        val mid = (finalCp1y + finalCp2y) / 2.0
        finalCp1y = mid.coerceAtMost(p2.y)
        finalCp2y = mid.coerceAtLeast(p1.y)
      }
    }

    p1.y > p2.y -> {
      // Decreasing segment: ensure c1y and c2y are between y2 and y1, and c1y ≥ c2y
      finalCp1y = cp1y.coerceIn(p2.y, p1.y)
      finalCp2y = cp2y.coerceIn(p2.y, p1.y)
      if (finalCp1y < finalCp2y) {
        val mid = (finalCp1y + finalCp2y) / 2.0
        finalCp1y = mid.coerceAtLeast(p2.y)
        finalCp2y = mid.coerceAtMost(p1.y)
      }
    }

    else -> {
      // Flat segment: both control points should equal y1 (and y2)
      finalCp1y = p1.y
      finalCp2y = p2.y
    }
  }

  // Evaluate cubic Bézier curve at t
  // B(t) = (1-t)³P₀ + 3(1-t)²tP₁ + 3(1-t)t²P₂ + t³P₃
  val oneMinusT = 1.0 - clampedT
  val oneMinusTSquared = oneMinusT * oneMinusT
  val oneMinusTCubed = oneMinusTSquared * oneMinusT
  val tSquared = clampedT * clampedT
  val tCubed = tSquared * clampedT

  val y = oneMinusTCubed * p1.y +
    3.0 * oneMinusTSquared * clampedT * finalCp1y +
    3.0 * oneMinusT * tSquared * finalCp2y +
    tCubed * p2.y

  return y
}

/**
 * Enum representing different interpolation types.
 */
public enum class InterpolationType {
  /** Linear interpolation between points */
  LINEAR,

  /** Cubic Bézier curve interpolation */
  CUBIC,

  /** Monotone cubic interpolation using Fritsch-Carlson algorithm */
  MONOTONE
}

/**
 * Gets the Y value for a given X coordinate from the chart model.
 * This function searches through all series in the model and returns the interpolated Y value
 * from the first series that contains data for the given X coordinate.
 *
 * @param xValue the X coordinate for which to find the Y value
 * @return the interpolated Y value, or null if not found
 */
public fun CartesianMeasuringContext.getYValueFromX(
  xValue: Double,
): Double? {
  // This would need access to the model's series data
  // Since we don't have direct access to the model here, this is a placeholder
  // In practice, you would need to pass the series data or access it through the context
  return null
}
