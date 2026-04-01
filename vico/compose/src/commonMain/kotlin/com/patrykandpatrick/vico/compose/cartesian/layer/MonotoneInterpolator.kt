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
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
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
  public fun getYAtX(x: Double, entries: List<Pair<Double, Double>>): Double? {
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

  /**
   * Batch interpolation — computes Y values for multiple X positions efficiently.
   * Precomputes secants and tangents once, then evaluates each X with binary search.
   * [entries] must be sorted by X. Returns null for X values outside the data range.
   */
  public fun getYValues(
    xValues: Collection<Double>,
    entries: List<Pair<Double, Double>>,
  ): List<Double?> {
    val n = entries.size
    if (n == 0) return xValues.map { null }
    if (n == 1) return xValues.map { entries[0].second }

    val xMin = entries.first().first
    val xMax = entries.last().first

    // Precompute secants once
    val secants = DoubleArray(n - 1) { i ->
      val dx = entries[i + 1].first - entries[i].first
      if (dx != 0.0) (entries[i + 1].second - entries[i].second) / dx else 0.0
    }

    // Precompute tangents once
    val tangents = DoubleArray(n)
    tangents[0] = secants[0]
    tangents[n - 1] = secants[n - 2]
    for (i in 1 until n - 1) {
      tangents[i] = computeMonotoneTangentD(secants[i - 1], secants[i])
    }

    return xValues.map { x ->
      when {
        x <= xMin -> entries.first().second
        x >= xMax -> entries.last().second
        else -> {
          // Binary search for bracketing segment
          var lo = 0
          var hi = n - 2
          while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid + 1].first < x) lo = mid + 1 else hi = mid
          }
          val seg = lo
          val x0 = entries[seg].first
          val x1 = entries[seg + 1].first
          val y0 = entries[seg].second
          val y1 = entries[seg + 1].second
          val dx = x1 - x0
          val t = (x - x0) / dx
          val h00 = (1 + 2 * t) * (1 - t) * (1 - t)
          val h10 = t * (1 - t) * (1 - t)
          val h01 = t * t * (3 - 2 * t)
          val h11 = t * t * (t - 1)
          h00 * y0 + h10 * dx * tangents[seg] + h01 * y1 + h11 * dx * tangents[seg + 1]
        }
      }
    }
  }

  /**
   * Zero-allocation version of [getYAtX] that works directly on [LineCartesianLayerModel.Entry].
   * Avoids `series.map { it.x to it.y }` allocation on every call.
   * Uses binary search for O(log n) segment lookup, computes only the 2-3 secants needed.
   */
  public fun getYAtXFromEntries(x: Double, series: List<LineCartesianLayerModel.Entry>): Double? {
    val n = series.size
    if (n < 2) return series.firstOrNull()?.y
    if (x <= series.first().x) return series.first().y
    if (x >= series.last().x) return series.last().y

    // Binary search for bracketing segment
    var lo = 0
    var hi = n - 2
    while (lo < hi) {
      val mid = (lo + hi) ushr 1
      if (series[mid + 1].x < x) lo = mid + 1 else hi = mid
    }
    val seg = lo

    // Compute only the secants needed for this segment's tangents
    val secantPrev = if (seg > 0) {
      val dx = series[seg].x - series[seg - 1].x
      if (dx != 0.0) (series[seg].y - series[seg - 1].y) / dx else 0.0
    } else 0.0

    val secantCur = run {
      val dx = series[seg + 1].x - series[seg].x
      if (dx != 0.0) (series[seg + 1].y - series[seg].y) / dx else 0.0
    }

    val secantNext = if (seg + 2 < n) {
      val dx = series[seg + 2].x - series[seg + 1].x
      if (dx != 0.0) (series[seg + 2].y - series[seg + 1].y) / dx else 0.0
    } else 0.0

    val t0 = if (seg == 0) secantCur else computeMonotoneTangentD(secantPrev, secantCur)
    val t1 = if (seg == n - 2) secantCur else computeMonotoneTangentD(secantCur, secantNext)

    val x0 = series[seg].x
    val x1 = series[seg + 1].x
    val y0 = series[seg].y
    val y1 = series[seg + 1].y
    val dx = x1 - x0
    val t = (x - x0) / dx

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
