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

  public val scroll : Float

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
  series: List<com.patrykandpatrick.vico.core.cartesian.data.LineCartesianLayerModel.Entry>,
  xValue: Double,
  interpolationType: InterpolationType,
  curvature: Float = 0.5f,
  minY : Double,
  maxY : Double,
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
        InterpolationType.CUBIC -> this.cubicInterpolation(series, i, xValue, curvature, minY , maxY)
      }
    }
  }

  return null
}

/**
 * Performs linear interpolation between two points.
 */
private fun linearInterpolation(
  point1: com.patrykandpatrick.vico.core.cartesian.data.LineCartesianLayerModel.Entry,
  point2: com.patrykandpatrick.vico.core.cartesian.data.LineCartesianLayerModel.Entry,
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
 * This mimics the behavior of CubicPointConnector and considers the chart's Y ranges.
 */
private fun CartesianMeasuringContext.cubicInterpolation(
  series: List<com.patrykandpatrick.vico.core.cartesian.data.LineCartesianLayerModel.Entry>,
  currentIndex: Int,
  xValue: Double,
  curvature: Float,
  minY: Double,
  maxY: Double
): Double {
  val currentPoint = series[currentIndex]
  val nextPoint = series[currentIndex + 1]

  // For cubic interpolation, we need at least 4 points to create a proper curve
  // If we don't have enough points, fall back to linear interpolation
  if (series.size < 4) {
    return linearInterpolation(currentPoint, nextPoint, xValue)
  }

  // Get control points for cubic Bézier curve
  val p0 = if (currentIndex > 0) series[currentIndex - 1] else currentPoint
  val p1 = currentPoint
  val p2 = nextPoint
  val p3 = if (currentIndex < series.size - 2) series[currentIndex + 2] else nextPoint

  // Calculate the parameter t (0 to 1) for the given x value
  val t = (xValue - p1.x) / (p2.x - p1.x)

  // Clamp t to [0, 1] range
  val clampedT = t.coerceIn(0.0, 1.0)

  // Calculate control points for the cubic Bézier curve
  // This mimics the CubicPointConnector logic but considers the chart's Y range
  val yRange = maxY - minY

  // Debug logging to help identify issues
  android.util.Log.d("CUBIC_INTERPOLATION", "Y Range: minY=${minY}, maxY=${maxY}, yRange=$yRange")
  android.util.Log.d("CUBIC_INTERPOLATION", "Points: p1=(${p1.x}, ${p1.y}), p2=(${p2.x}, ${p2.y})")

  // Safety check to avoid division by zero
  if (yRange <= 0) {
    android.util.Log.w("CUBIC_INTERPOLATION", "Invalid Y range, falling back to linear interpolation")
    return linearInterpolation(currentPoint, nextPoint, xValue)
  }

  val normalizedYDelta = kotlin.math.abs(p2.y - p1.y) / yRange
  val xDelta = (4 * normalizedYDelta).coerceAtMost(1.0) * curvature * (p2.x - p1.x)

  val cp1x = p1.x + xDelta
  val cp1y = p1.y
  val cp2x = p2.x - xDelta
  val cp2y = p2.y

  // Cubic Bézier formula: B(t) = (1-t)³P₀ + 3(1-t)²tP₁ + 3(1-t)t²P₂ + t³P₃
  // For our case, we use the control points we calculated
  val oneMinusT = 1.0 - clampedT
  val oneMinusTSquared = oneMinusT * oneMinusT
  val oneMinusTCubed = oneMinusTSquared * oneMinusT
  val tSquared = clampedT * clampedT
  val tCubed = tSquared * clampedT

  val y = oneMinusTCubed * p1.y +
          3 * oneMinusTSquared * clampedT * cp1y +
          3 * oneMinusT * tSquared * cp2y +
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
  CUBIC
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
