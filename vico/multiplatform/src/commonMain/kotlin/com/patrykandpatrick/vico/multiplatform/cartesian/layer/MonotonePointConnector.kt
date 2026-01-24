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

package com.patrykandpatrick.vico.multiplatform.cartesian.layer

import androidx.compose.ui.graphics.Path
import com.patrykandpatrick.vico.multiplatform.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.multiplatform.cartesian.data.LineCartesianLayerModel
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Implements iOS-style monotone cubic interpolation using Fritsch-Carlson algorithm.
 *
 * Key properties:
 * - Works on raw data values (preserves monotonicity)
 * - Uses 6 neighboring points for proper tangent calculation
 * - Stateless design (no stored state, works during scrolling)
 * - Converts data-space tangents to screen-space for drawing
 * - Resulting curve is smooth and preserves monotonicity
 * - Converted into cubic Bézier segments (GPU friendly)
 *
 * This produces curves similar to SwiftUI's `.monotone` interpolation and iOS Health app charts.
 */
internal data class MonotonePointConnector(private val curvature: Float = 0.5f) :
  LineCartesianLayer.PointConnector {
  init {
    require(curvature > 0 && curvature <= 1) { "`curvature` must be in (0, 1]." }
  }

  override fun connect(
    context: CartesianDrawingContext,
    path: Path,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    entry1: LineCartesianLayerModel.Entry,
    entry2: LineCartesianLayerModel.Entry,
    series: List<LineCartesianLayerModel.Entry>,
  ) {
    // Get 6 neighbors from series (2 before entry1, entry1, entry2, 2 after entry2)
    // Also get index2 and isFirstSegment flag
    val (neighbors, index2, isFirstSegment) = getNeighbors(entry1, entry2, series)

    // If we don't have enough neighbors, fall back to linear interpolation
    if (neighbors.size < 2) {
      path.lineTo(x2, y2)
      return
    }

    // Calculate secant slopes in DATA SPACE (normalized by dx to handle non-uniform spacing)
    val dxMinus2 = neighbors[1].x - neighbors[0].x
    val dxMinus1 = neighbors[2].x - neighbors[1].x
    val dx0 = neighbors[3].x - neighbors[2].x  // entry2.x - entry1.x
    val dx1 = neighbors[4].x - neighbors[3].x
    val dx2 = neighbors[5].x - neighbors[4].x

    // Detect if we're missing past neighbors (first segment case)
    // When past neighbors are duplicated (dxMinus2==0 or dxMinus1==0), skip those calculations
    val hasPastNeighbors = dxMinus2 != 0.0 && dxMinus1 != 0.0

    // Detect if we're missing future neighbors (last segment case)
    // When future neighbors are duplicated (dx1==0 or dx2==0), skip those calculations
    val hasFutureNeighbors = dx1 != 0.0 && dx2 != 0.0

    // Calculate normalized secants (dy/dx)
    // Only calculate sMinus2 and sMinus1 if we have actual past neighbors (not duplicated/extrapolated)
    val sMinus2 = if (hasPastNeighbors && dxMinus2 != 0.0) (neighbors[1].y - neighbors[0].y) / dxMinus2 else 0.0
    val sMinus1 = if (hasPastNeighbors && dxMinus1 != 0.0) (neighbors[2].y - neighbors[1].y) / dxMinus1 else 0.0
    val s0 = if (dx0 != 0.0) (neighbors[3].y - neighbors[2].y) / dx0 else 0.0  // entry2.y - entry1.y
    // Only calculate s1 and s2 if we have actual future neighbors (not duplicated/extrapolated)
    val s1 = if (hasFutureNeighbors && dx1 != 0.0) (neighbors[4].y - neighbors[3].y) / dx1 else 0.0
    val s2 = if (hasFutureNeighbors && dx2 != 0.0) (neighbors[5].y - neighbors[4].y) / dx2 else 0.0

    // Use central differences to calculate initial gradients in DATA SPACE
    // Weight by dx to account for non-uniform spacing
    val totalDxMinus = if (hasPastNeighbors) dxMinus2 + dxMinus1 else 0.0
    val totalDx0 = if (hasPastNeighbors) dxMinus1 + dx0 else dx0
    val totalDx1 = if (hasFutureNeighbors) dx0 + dx1 else 0.0
    val totalDx2 = if (hasFutureNeighbors) dx1 + dx2 else 0.0

    var mMinus1Data = if (hasPastNeighbors && totalDxMinus != 0.0) (sMinus2 * dxMinus2 + sMinus1 * dxMinus1) / totalDxMinus else 0.0
    var m0LeftData = if (totalDx0 != 0.0) {
      if (hasPastNeighbors) {
        (sMinus1 * dxMinus1 + s0 * dx0) / totalDx0
      } else {
        s0  // For first segment without past neighbors, use s0 directly
      }
    } else 0.0
    // Only calculate m1LeftData and m2Data if we have future neighbors
    var m1LeftData = if (hasFutureNeighbors && totalDx1 != 0.0) (s0 * dx0 + s1 * dx1) / totalDx1 else 0.0
    var m2Data = if (hasFutureNeighbors && totalDx2 != 0.0) (s1 * dx1 + s2 * dx2) / totalDx2 else 0.0

    // For C1 continuity, we calculate gradients for left and right curves
    var m0RightData = m0LeftData
    var m1RightData = m1LeftData

    // Handle equal values and sign changes
    if (neighbors[2].y == neighbors[3].y || dx0 == 0.0) {
      // Central curve is horizontal or no X change
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
      // Only check for sign changes when both secants are non-zero (ignore zero secants from duplicated/extrapolated endpoints)
      if (hasPastNeighbors && sMinus2 != 0.0 && sMinus1 != 0.0 && ((sMinus2 < 0.0 && sMinus1 > 0.0) || (sMinus2 > 0.0 && sMinus1 < 0.0))) {
        mMinus1Data = 0.0
      }
      if (hasPastNeighbors && sMinus1 != 0.0 && s0 != 0.0 && ((sMinus1 < 0.0 && s0 > 0.0) || (sMinus1 > 0.0 && s0 < 0.0))) {
        m0LeftData = 0.0
        m0RightData = 0.0
      }
      // Only check sign changes involving s1/s2 if we have future neighbors
      if (hasFutureNeighbors && s0 != 0.0 && s1 != 0.0 && ((s0 < 0.0 && s1 > 0.0) || (s0 > 0.0 && s1 < 0.0))) {
        m1LeftData = 0.0
        m1RightData = 0.0
      }
      if (hasFutureNeighbors && s1 != 0.0 && s2 != 0.0 && ((s1 < 0.0 && s2 > 0.0) || (s1 > 0.0 && s2 < 0.0))) {
        m2Data = 0.0
      }

      // Calculate alpha and beta values for Fritsch-Carlson constraint
      // Note: s values are now normalized slopes (dy/dx), so ratios are correct
      val alphaLeft = if (hasPastNeighbors && sMinus1 != 0.0) mMinus1Data / sMinus1 else 0.0
      val betaLeft = if (hasPastNeighbors && sMinus1 != 0.0) m0LeftData / sMinus1 else 0.0

      val alphaCent = if (s0 != 0.0) m0RightData / s0 else 0.0
      val betaCent = if (hasFutureNeighbors && s0 != 0.0) m1LeftData / s0 else 0.0

      val alphaRight = if (hasFutureNeighbors && s1 != 0.0) m1RightData / s1 else 0.0
      val betaRight = if (hasFutureNeighbors && s1 != 0.0) m2Data / s1 else 0.0

      // Apply Fritsch-Carlson constraint (circle of radius 3)
      // Only apply left constraint if we have past neighbors
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
        // Only update m1LeftData if we have future neighbors
        if (hasFutureNeighbors) {
          m1LeftData = tau * betaCent * s0
        }
      }

      // Only apply right constraint if we have future neighbors
      if (hasFutureNeighbors) {
        val discRight = alphaRight * alphaRight + betaRight * betaRight
        if (discRight.compareTo(9.0) > 0) {
          val tau = 3.0 / sqrt(discRight)
          m1RightData = tau * alphaRight * s1
        }
      }
    }

    // Choose gradients with smallest magnitude for C1 continuity
    var m0Data = if (hasPastNeighbors && abs(m0LeftData) < abs(m0RightData)) m0LeftData else m0RightData
    var m1Data = if (abs(m1LeftData) < abs(m1RightData)) m1LeftData else m1RightData

    val isLastSegment = index2 != -1 && index2 == series.size - 1

    // Capture conversion snapshot from context
    val scaleX = context.layoutDirectionMultiplier *
                 context.layerDimensions.xSpacing /
                 context.ranges.xStep
    val yRange = context.ranges.getYRange(null) // Use default vertical axis
    val scaleY = context.layerBounds.height / yRange.length

    // For last segment: ensure minimum curve strength for visual endpoint emphasis
    // The extrapolated neighbors may produce very small m1Data, causing flat endings
    // We ensure m1Data has at least a minimum strength based on screen-space segment height
    if (isLastSegment && dx0 != 0.0) {
      // Calculate screen-space segment height for visual minimum
      val segmentHeightScreen = abs(y2 - y1)
      val dxScreen = x2 - x1
      // Use screen-space based minimum: ensure m1Screen creates at least 15% of segment height offset
      // Convert back to data space: m1Data = m1Screen * scaleX / scaleY
      // m1Screen = (segmentHeightScreen * 0.15) / (dxScreen / 3) = segmentHeightScreen * 0.15 * 3 / dxScreen
      val minM1Screen = if (dxScreen != 0f) {
        (segmentHeightScreen * 0.15f * 3f) / dxScreen
      } else {
        0f
      }
      // Convert to data space minimum
      val minM1Data = if (scaleX != 0.0) {
        (minM1Screen.toDouble() * scaleY) / scaleX
      } else {
        0.0
      }
      
      if (abs(m1Data) < abs(minM1Data)) {
        // Preserve the sign of m1Data (or use s0's sign if m1Data is effectively zero)
        val targetSign = if (abs(m1Data) < 1e-10) {
          if (s0 > 0.0) 1.0 else -1.0
        } else {
          if (m1Data >= 0.0) 1.0 else -1.0
        }
        m1Data = abs(minM1Data) * targetSign
      }
    }

    // Convert data-space tangents to screen-space tangents
    // Note: Y-axis is flipped in screen space (bottom is higher Y value)
    // Use actual dx0 for more accurate conversion
    val dxData = dx0
    val dxScreen = x2 - x1

    // Calculate screen-space tangent: m_screen = (dy_screen / dx_screen)
    // where dy_screen = dy_data * scaleY and dx_screen = dx_data * scaleX
    // So: m_screen = (dy_data * scaleY) / (dx_data * scaleX) = m_data * (scaleY / scaleX)
    // But we need to account for Y-axis flip, so: m_screen = -m_data * (scaleY / scaleX)
    val scaleRatio = scaleY / scaleX
    var m0Screen = (-m0Data * scaleRatio).toFloat()
    var m1Screen = (-m1Data * scaleRatio).toFloat()

    // Clamp tangents to prevent extreme control points
    // Maximum reasonable tangent: limit control point offset to reasonable fraction of segment
    val maxTangent = if (dxScreen != 0f) {
      val segmentHeight = abs(y2 - y1)
      // Allow control points to extend at most 2x the segment height vertically
      (2f * segmentHeight / abs(dxScreen)).coerceAtMost(50f) // Cap at 50 for safety
    } else {
      0f
    }
    m0Screen = m0Screen.coerceIn(-maxTangent, maxTangent)
    m1Screen = m1Screen.coerceIn(-maxTangent, maxTangent)

    // Calculate control points in SCREEN SPACE
    var c1x = x1 + dxScreen / 3f
    var c1y = y1 + m0Screen * dxScreen / 3f
    var c2x = x2 - dxScreen / 3f
    var c2y = y2 - m1Screen * dxScreen / 3f

    // DEBUG: Log original control points before constraints
    val c1yOriginal = c1y
    val c2yOriginal = c2y
    val segmentType = when {
      y1 < y2 -> "INCREASING"
      y1 > y2 -> "DECREASING"
      else -> "FLAT"
    }

    // Enforce Bézier monotonicity constraints
    // For a cubic Bézier curve to be monotone:
    // - If increasing (y1 < y2): y1 ≤ c1y ≤ c2y ≤ y2
    // - If decreasing (y1 > y2): y1 ≥ c1y ≥ c2y ≥ y2
    // This prevents overshoot by construction
    // For last segment, be extra conservative to prevent any visual overshoot
    val constraintApplied = when {
      y1 < y2 -> {
        // Increasing segment: ensure c1y and c2y are between y1 and y2, and c1y ≤ c2y
        c1y = c1y.coerceIn(y1, y2)
        c2y = c2y.coerceIn(y1, y2)
        if (c1y > c2y) {
          // If order is wrong, use midpoint to maintain monotonicity
          val mid = (c1y + c2y) / 2f
          c1y = mid.coerceAtMost(y2)
          c2y = mid.coerceAtLeast(y1)
        }
        // Endpoint margin logic (below) handles last segment endpoint positioning
        // No need to force c2y = y2 here as endpoint margin provides better control
        "coerceIn($y1,$y2)"
      }
      y1 > y2 -> {
        // Decreasing segment: ensure c1y and c2y are between y2 and y1, and c1y ≥ c2y
        c1y = c1y.coerceIn(y2, y1)
        c2y = c2y.coerceIn(y2, y1)
        if (c1y < c2y) {
          // If order is wrong, use midpoint to maintain monotonicity
          val mid = (c1y + c2y) / 2f
          c1y = mid.coerceAtLeast(y2)
          c2y = mid.coerceAtMost(y1)
        }
        // Endpoint margin logic (below) handles last segment endpoint positioning
        // No need to force c2y = y2 here as endpoint margin provides better control
        "coerceIn($y2,$y1)"
      }
      else -> {
        // Flat segment: both control points should equal y1 (and y2)
        c1y = y1
        c2y = y2
        "flat"
      }
    }
    val c1yAfterConstraints = c1y
    val c2yAfterConstraints = c2y

    // Clamp control points to bounds to prevent drawing outside chart area
    // Account for stroke width: shrink bounds by half stroke width + anti-aliasing + safety buffer
    // This ensures the stroke itself doesn't visually extend beyond bounds
    val bounds = context.layerBounds
    val strokeWidthMargin = 4f // Accounts for up to 6dp stroke (~18px on 3x devices, half=9px) + anti-aliasing (~1px) + safety (~1px)
    val effectiveTop = bounds.top + strokeWidthMargin
    val effectiveBottom = bounds.bottom - strokeWidthMargin
    val effectiveLeft = bounds.left + strokeWidthMargin
    val effectiveRight = bounds.right - strokeWidthMargin
    val c1yBeforeBounds = c1y
    val c2yBeforeBounds = c2y
    c1x = c1x.coerceIn(effectiveLeft, effectiveRight)
    c1y = c1y.coerceIn(effectiveTop, effectiveBottom)
    c2x = c2x.coerceIn(effectiveLeft, effectiveRight)
    c2y = c2y.coerceIn(effectiveTop, effectiveBottom)
    val boundsClamped = c1y != c1yBeforeBounds || c2y != c2yBeforeBounds

    // For last segment: ensure curve joins from right of p1 and approaches bottom of p2
    // c1x is already positioned to the right of x1 (c1x = x1 + dxScreen / 3f)
    val endpointMarginApplied: String
    val c2yBeforeEndpointMargin = c2y
    if (isLastSegment) {
      // For last segment: preserve original c2y position from extrapolated neighbors
      // The original c2y already provides smooth approach based on m1Screen calculation
      // Don't override it - just ensure it's within bounds and maintains monotonicity
      if (y1 > y2) {
        // Decreasing segment: ensure c2y is between y2 and y1
        c2y = c2yBeforeEndpointMargin.coerceIn(y2, y1).coerceIn(effectiveTop, effectiveBottom)
        endpointMarginApplied = "lastSegment: c2y=$c2yBeforeEndpointMargin->$c2y (preserve original from extrapolation)"
      } else if (y1 < y2) {
        // Increasing segment: ensure c2y is between y1 and y2
        c2y = c2yBeforeEndpointMargin.coerceIn(y1, y2).coerceIn(effectiveTop, effectiveBottom)
        endpointMarginApplied = "lastSegment: c2y=$c2yBeforeEndpointMargin->$c2y (preserve original from extrapolation)"
      } else {
        // Flat segment: c2y = y2
        c2y = y2.coerceIn(effectiveTop, effectiveBottom)
        endpointMarginApplied = "lastSegment: c2y=$c2yBeforeEndpointMargin->$c2y (flat segment)"
      }
    } else {
      endpointMarginApplied = "notLastSegment"
    }

    // DEBUG: Sample Bézier curve to verify monotonicity
    fun bezierY(t: Float): Float {
      val mt = 1f - t
      val mt2 = mt * mt
      val mt3 = mt2 * mt
      val t2 = t * t
      val t3 = t2 * t
      return mt3 * y1 + 3f * mt2 * t * c1y + 3f * mt * t2 * c2y + t3 * y2
    }

    // Sample curve VERY densely to detect any overshoot (1000 points for precision)
    val denseSamplePoints = (0..1000).map { it / 1000f }
    val denseSampleY = denseSamplePoints.map { bezierY(it) }
    val minSampleY = denseSampleY.minOrNull() ?: y1
    val maxSampleY = denseSampleY.maxOrNull() ?: y2
    val minY = minOf(y1, y2)
    val maxY = maxOf(y1, y2)
    val overshootAbove = (maxSampleY - maxY).coerceAtLeast(0f)
    val overshootBelow = (minY - minSampleY).coerceAtLeast(0f)
    val hasOvershoot = overshootAbove > 1e-3f || overshootBelow > 1e-3f

    // Find where overshoot occurs
    val overshootTAbove = if (overshootAbove > 1e-3f) {
      denseSamplePoints.zip(denseSampleY).firstOrNull { it.second > maxY + 1e-3f }?.first
    } else null
    val overshootTBelow = if (overshootBelow > 1e-3f) {
      denseSamplePoints.zip(denseSampleY).firstOrNull { it.second < minY - 1e-3f }?.first
    } else null

    // Also sample at key points for logging
    val keySamplePoints = listOf(0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f)
    val keySampleY = keySamplePoints.map { bezierY(it) }

    // DEBUG: Log comprehensive information
    println(
      "MonotonePointConnector: === DEBUG Bézier Monotonicity ===\n" +
        "entry1=${entry1.y} entry2=${entry2.y} isFirst=$isFirstSegment isLast=$isLastSegment\n" +
        "NEIGHBORS: n0=(${neighbors[0].x},${neighbors[0].y}) n1=(${neighbors[1].x},${neighbors[1].y}) n2=(${neighbors[2].x},${neighbors[2].y}) n3=(${neighbors[3].x},${neighbors[3].y}) n4=(${neighbors[4].x},${neighbors[4].y}) n5=(${neighbors[5].x},${neighbors[5].y})${if (isFirstSegment) " [EXTRAPOLATED:0,1]" else ""}${if (isLastSegment) " [EXTRAPOLATED:4,5]" else ""}\n" +
        "y1=$y1 y2=$y2 segmentType=$segmentType\n" +
        "m0Data=$m0Data m1Data=$m1Data m0Screen=$m0Screen m1Screen=$m1Screen\n" +
        "ORIGINAL: c1y=$c1yOriginal c2y=$c2yOriginal\n" +
        "AFTER_CONSTRAINTS: c1y=$c1yAfterConstraints c2y=$c2yAfterConstraints constraint=$constraintApplied\n" +
        "AFTER_BOUNDS: c1y=$c1y c2y=$c2y boundsClamped=$boundsClamped bounds=[${bounds.top},${bounds.bottom}] effectiveBounds=[$effectiveTop,$effectiveBottom] strokeMargin=$strokeWidthMargin\n" +
        "ENDPOINT_MARGIN: y2=$y2 $endpointMarginApplied\n" +
        "MONOTONICITY_CHECK: y1≤c1y≤c2y≤y2? ${if (y1 < y2) "$y1≤$c1y≤$c2y≤$y2 = ${y1 <= c1y && c1y <= c2y && c2y <= y2}" else "N/A"}\n" +
        "MONOTONICITY_CHECK: y1≥c1y≥c2y≥y2? ${if (y1 > y2) "$y1≥$c1y≥$c2y≥$y2 = ${y1 >= c1y && c1y >= c2y && c2y >= y2}" else "N/A"}\n" +
        "SAMPLED_CURVE: minSampleY=$minSampleY maxSampleY=$maxSampleY [minY=$minY, maxY=$maxY]\n" +
        "OVERSHOOT: above=$overshootAbove below=$overshootBelow hasOvershoot=$hasOvershoot\n" +
        "OVERSHOOT_LOCATION: tAbove=$overshootTAbove tBelow=$overshootTBelow\n" +
        "KEY_SAMPLES: ${keySamplePoints.zip(keySampleY).joinToString(", ") { "t=${it.first}=${(it.second * 100).toInt() / 100f}" }}\n" +
        "DENSE_CHECK: sampled ${denseSamplePoints.size} points, found min=$minSampleY max=$maxSampleY"
    )

    // Use cubic Bézier for smooth curves
    // Bézier monotonicity constraints ensure no overshoot
    path.cubicTo(c1x, c1y, c2x, c2y, x2, y2)
  }

  /**
   * Gets 6 neighboring entries from series: [entry1-2, entry1-1, entry1, entry2, entry2+1, entry2+2]
   * Handles edge cases by extrapolating boundary entries for first and last segments.
   * Returns the neighbors list, index2, and flags indicating if this is the first or last segment.
   *
   * Performance optimized: Uses single pass to find both indices instead of two indexOf() calls.
   */
  private fun getNeighbors(
    entry1: LineCartesianLayerModel.Entry,
    entry2: LineCartesianLayerModel.Entry,
    series: List<LineCartesianLayerModel.Entry>
  ): Triple<List<LineCartesianLayerModel.Entry>, Int, Boolean> {
    // Optimized: Find both indices in a single pass
    var index1 = -1
    var index2 = -1
    for (i in series.indices) {
      if (index1 == -1 && series[i] === entry1) index1 = i
      if (index2 == -1 && series[i] === entry2) index2 = i
      if (index1 != -1 && index2 != -1) break // Early exit when both found
    }

    // Handle case where entries are not found
    if (index1 == -1 || index2 == -1) {
      return Triple(listOf(entry1, entry1, entry1, entry2, entry2, entry2), index2, false)
    }

    val result = mutableListOf<LineCartesianLayerModel.Entry>()
    val isFirstSegment = index1 == 0
    val isLastSegment = index2 == series.size - 1

    // For first segment: extrapolate backward neighbors instead of duplicating series.first()
    // This prevents dxMinus2==0 and dxMinus1==0 which cause incorrect tangent calculations
    if (isFirstSegment) {
      // We'll add entry1 first, then extrapolate backward
      result.add(entry1)  // Temporary placeholder, will be replaced
      result.add(entry1)  // Temporary placeholder, will be replaced
      result.add(entry1)
      result.add(entry2)

      // Get future neighbors to calculate average dx for extrapolation
      val futureNeighbor1 = series.getOrNull(index2 + 1) ?: entry2
      val futureNeighbor2 = series.getOrNull(index2 + 2) ?: futureNeighbor1

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
      // For increasing trend: previous Y should be lower than entry1.y
      // For decreasing trend: previous Y should be higher than entry1.y
      val absDy0 = kotlin.math.abs(dy0)
      val prevY1 = if (isIncreasing) {
        entry1.y - absDy0 * 0.5  // Continue backward trend (lower)
      } else {
        entry1.y + absDy0 * 0.5  // Continue backward trend (higher)
      }

      val prevY2 = if (isIncreasing) {
        prevY1 - absDy0 * 0.3  // Continue backward trend (lower)
      } else {
        prevY1 + absDy0 * 0.3  // Continue backward trend (higher)
      }

      // Create extrapolated entries for backward neighbors
      val extrapolatedMinus2 = LineCartesianLayerModel.Entry(
        entry1.x - 2 * avgDx,
        prevY2
      )
      val extrapolatedMinus1 = LineCartesianLayerModel.Entry(
        entry1.x - avgDx,
        prevY1
      )

      // Replace placeholders with extrapolated entries
      result[0] = extrapolatedMinus2
      result[1] = extrapolatedMinus1
    } else {
      // Normal case: get actual backward neighbors
      result.add(series.getOrNull(index1 - 2) ?: series.first())
      result.add(series.getOrNull(index1 - 1) ?: series.first())
      result.add(entry1)
    }

    // Get entry2
    result.add(entry2)

    // For last segment: extrapolate neighbors instead of duplicating entry2
    // This prevents dx1==0 and dx2==0 which cause incorrect tangent calculations
    if (isLastSegment) {
      // Calculate average dx from previous segments to estimate spacing
      val neighbors0 = result[0]  // entry1-2
      val neighbors1 = result[1]  // entry1-1
      val neighbors2 = result[2]  // entry1
      val neighbors3 = result[3]  // entry2

      val dxMinus2 = neighbors1.x - neighbors0.x
      val dxMinus1 = neighbors2.x - neighbors1.x
      val dx0 = neighbors3.x - neighbors2.x

      // Calculate average dx, fallback to dx0 if others are zero
      val avgDx = when {
        dxMinus2 != 0.0 && dxMinus1 != 0.0 && dx0 != 0.0 -> (dxMinus2 + dxMinus1 + dx0) / 3.0
        dxMinus1 != 0.0 && dx0 != 0.0 -> (dxMinus1 + dx0) / 2.0
        dx0 != 0.0 -> dx0
        else -> if (dxMinus1 != 0.0) dxMinus1 else if (dxMinus2 != 0.0) dxMinus2 else 1.0
      }

      // Determine segment direction (increasing/decreasing in data space)
      val dy0 = entry2.y - entry1.y
      val isIncreasing = entry1.y < entry2.y

      // Extrapolate next points: continue the trend
      // For increasing: next Y should be higher than entry2.y
      // For decreasing: next Y should be lower than entry2.y
      val absDy0 = kotlin.math.abs(dy0)
      val nextY1 = if (isIncreasing) {
        entry2.y + absDy0 * 0.5  // Continue upward trend
      } else {
        entry2.y - absDy0 * 0.5  // Continue downward trend
      }

      val nextY2 = if (isIncreasing) {
        nextY1 + absDy0 * 0.3  // Continue upward trend
      } else {
        nextY1 - absDy0 * 0.3  // Continue downward trend
      }

      // Create extrapolated entries
      val extrapolated1 = LineCartesianLayerModel.Entry(
        entry2.x + avgDx,
        nextY1
      )
      val extrapolated2 = LineCartesianLayerModel.Entry(
        entry2.x + 2 * avgDx,
        nextY2
      )

      result.add(extrapolated1)
      result.add(extrapolated2)
    } else {
      // Normal case: get actual future neighbors
      result.add(series.getOrNull(index2 + 1) ?: series.last())
      result.add(series.getOrNull(index2 + 2) ?: series.last())
    }

    return Triple(result, index2, isFirstSegment)
  }
}
