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

package com.patrykandpatrick.vico.compose.cartesian.layer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import kotlin.math.abs
import kotlin.math.min

/**
 * Monotone cubic interpolation using the Fritsch-Carlson algorithm.
 *
 * Produces iOS-style smooth curves matching SwiftUI's `.monotone` interpolation:
 * - **No overshoot** at local extrema (peaks and valleys are preserved)
 * - Smooth, continuous first derivative
 * - Data-driven tangents via Fritsch-Carlson method
 * - GPU-friendly cubic Bezier output
 *
 * Unlike [CatmullRomInterpolator] which can overshoot, this interpolator guarantees
 * the curve stays within the Y bounds of the data — critical for health data
 * (weight, blood pressure, temperature) where overshoots misrepresent the data.
 *
 * Algorithm:
 * 1. Compute secant slopes between consecutive points
 * 2. At each interior point, compute monotone tangent:
 *    - If neighboring slopes have different signs → tangent = 0 (local extremum)
 *    - Otherwise → weighted harmonic mean, clamped to 3x min slope (Fritsch-Carlson)
 * 3. Convert Hermite spline segments to cubic Bezier control points
 */
internal object MonotoneInterpolator : LineCartesianLayer.Interpolator {

  override fun interpolate(
    context: CartesianDrawingContext,
    path: Path,
    points: List<Offset>,
    visibleIndexRange: IntRange,
  ) {
    if (visibleIndexRange.isEmpty()) return
    val n = points.size
    if (n < 2) {
      path.moveTo(points[visibleIndexRange.first].x, points[visibleIndexRange.first].y)
      return
    }

    // Step 1: Compute secant slopes between consecutive points
    val secants = FloatArray(n - 1) { i ->
      val dx = points[i + 1].x - points[i].x
      if (dx != 0f) (points[i + 1].y - points[i].y) / dx else 0f
    }

    // Step 2: Compute monotone tangents at each point (Fritsch-Carlson)
    val tangents = FloatArray(n)
    tangents[0] = secants[0]
    tangents[n - 1] = secants[n - 2]
    for (i in 1 until n - 1) {
      tangents[i] = computeMonotoneTangent(secants[i - 1], secants[i])
    }

    // Step 3: Draw cubic Bezier segments
    path.moveTo(points[visibleIndexRange.first].x, points[visibleIndexRange.first].y)
    for (index in visibleIndexRange.first + 1..visibleIndexRange.last) {
      val p1 = points[index - 1]
      val p2 = points[index]
      val dx = p2.x - p1.x
      val t0 = tangents[index - 1]
      val t1 = tangents[index]

      // Hermite to Bezier: control points at 1/3 intervals
      val c1x = p1.x + dx / 3f
      val c1y = p1.y + t0 * dx / 3f
      val c2x = p2.x - dx / 3f
      val c2y = p2.y - t1 * dx / 3f

      path.cubicTo(c1x, c1y, c2x, c2y, p2.x, p2.y)
    }
  }

  /**
   * Returns the Y range of the data. Since monotone interpolation never overshoots,
   * the range is exactly the data min/max — no need for Bezier extrema computation.
   */
  override fun getYRange(y: List<Double>): ClosedRange<Double> = y.min()..y.max()

  /**
   * Computes the interpolated Y value at any X position using Fritsch-Carlson monotone cubic.
   * [entries] must be sorted by X. Returns null if X is outside the data range or entries < 2.
   */
  internal fun getYAtX(x: Double, entries: List<Pair<Double, Double>>): Double? {
    if (entries.size < 2) return entries.firstOrNull()?.second
    if (x <= entries.first().first) return entries.first().second
    if (x >= entries.last().first) return entries.last().second

    // Find bracketing segment
    var segIndex = 0
    for (i in 0 until entries.lastIndex) {
      if (x >= entries[i].first && x <= entries[i + 1].first) { segIndex = i; break }
    }

    val n = entries.size
    // Compute secants
    val secants = DoubleArray(n - 1) { i ->
      val dx = entries[i + 1].first - entries[i].first
      if (dx != 0.0) (entries[i + 1].second - entries[i].second) / dx else 0.0
    }
    // Monotone tangents at segment endpoints
    val t0 = if (segIndex == 0) secants[0]
    else computeMonotoneTangentD(secants[segIndex - 1], secants[segIndex])
    val t1 = if (segIndex == n - 2) secants[n - 2]
    else computeMonotoneTangentD(secants[segIndex], secants[segIndex + 1])

    // Hermite interpolation
    val x0 = entries[segIndex].first
    val x1 = entries[segIndex + 1].first
    val y0 = entries[segIndex].second
    val y1 = entries[segIndex + 1].second
    val dx = x1 - x0
    val t = (x - x0) / dx

    // Hermite basis functions
    val h00 = (1 + 2 * t) * (1 - t) * (1 - t)
    val h10 = t * (1 - t) * (1 - t)
    val h01 = t * t * (3 - 2 * t)
    val h11 = t * t * (t - 1)

    return h00 * y0 + h10 * dx * t0 + h01 * y1 + h11 * dx * t1
  }

  private fun computeMonotoneTangentD(m1: Double, m2: Double): Double {
    if (m1 * m2 <= 0.0) return 0.0
    val harmonicMean = 2.0 * m1 * m2 / (m1 + m2)
    val maxAllowed = 3.0 * min(abs(m1), abs(m2))
    return harmonicMean.coerceIn(-maxAllowed, maxAllowed)
  }

  /**
   * Computes a monotone tangent at an interior point using Fritsch-Carlson.
   *
   * - If slopes have different signs (or either is zero) → tangent = 0 (local extremum)
   * - Otherwise → harmonic mean, clamped to 3x min slope to prevent overshoot
   */
  private fun computeMonotoneTangent(m1: Float, m2: Float): Float {
    if (m1 * m2 <= 0f) return 0f
    val harmonicMean = 2f * m1 * m2 / (m1 + m2)
    val maxAllowed = 3f * min(abs(m1), abs(m2))
    return harmonicMean.coerceIn(-maxAllowed, maxAllowed)
  }
}
