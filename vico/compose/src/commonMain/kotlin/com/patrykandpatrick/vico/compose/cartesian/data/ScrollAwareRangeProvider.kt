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

package com.patrykandpatrick.vico.compose.cartesian.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A [CartesianLayerRangeProvider] that dynamically adjusts the Y range based on the currently
 * visible data points. Follows iOS-like animation: new ticks appear instantly (approximating
 * cross-fade), while chart content animates positionally via range interpolation.
 *
 * @param segmentSize the number of data points per cache segment. Must be > 0.
 * @param debounceMs milliseconds to wait after scroll settles before updating the range.
 * @param animDurationMs duration of the range animation in milliseconds.
 * @param onVisibleRange callback receiving (visibleMinY, visibleMaxY) and returning a
 *   [Pair] of the display range ([ClosedRange]) and tick label values ([List]).
 */
public class ScrollAwareRangeProvider(
  private val segmentSize: Int = 10,
  internal val debounceMs: Long = 100L,
  internal val animDurationMs: Int = 300,
  private val onVisibleRange: (minY: Double, maxY: Double) -> Pair<ClosedRange<Double>, List<Double>>,
) : CartesianLayerRangeProvider {

  init {
    require(segmentSize > 0) { "segmentSize must be > 0" }
  }

  // Segment cache: index -> (minY, maxY) for that segment
  private var segmentCache: List<Pair<Double, Double>> = emptyList()

  // All Y values from the first series, indexed by entry position
  private var allYValues: List<Double> = emptyList()

  // Current animated range values — set by ScrollAwareRangeEffect animation
  internal var currentMinY: Double = Double.NaN
  internal var currentMaxY: Double = Double.NaN

  // Whether the cache has been built at least once
  internal var isCacheReady: Boolean = false

  // Current tick labels — read by ListItemPlacer
  public var currentTicks: List<Double> = emptyList()
    internal set

  // Flow for scroll updates — CartesianChartHost emits to this.
  // Buffer of 10 prevents dropped events during rapid scrolling.
  internal val scrollUpdates = MutableSharedFlow<ScrollInfo>(extraBufferCapacity = 10)

  override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore): Double =
    if (isCacheReady && !currentMinY.isNaN()) currentMinY else minY

  override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore): Double =
    if (isCacheReady && !currentMaxY.isNaN()) currentMaxY else maxY

  /**
   * Builds the segment cache from model data. Called when the model changes.
   * Uses the first series of the [LineCartesianLayerModel].
   */
  internal fun buildCache(series: List<List<LineCartesianLayerModel.Entry>>) {
    if (series.isEmpty() || series.first().isEmpty()) {
      segmentCache = emptyList()
      allYValues = emptyList()
      isCacheReady = false
      return
    }
    allYValues = series.first().map { it.y }
    segmentCache = allYValues.chunked(segmentSize).map { chunk ->
      chunk.min() to chunk.max()
    }
    isCacheReady = true
  }

  /**
   * Computes the visible Y min/max from the segment cache based on scroll info.
   * Returns null if the cache is not ready or visible range is empty.
   */
  internal fun computeVisibleRange(info: ScrollInfo): Pair<Double, Double>? {
    if (!isCacheReady || allYValues.isEmpty() || info.xSpacing <= 0f) return null

    val startIndex = (info.scrollPixels / info.xSpacing).toInt().coerceAtLeast(0)
    val visibleCount = (info.chartWidth / info.xSpacing).toInt().coerceAtLeast(1)
    val endIndex = (startIndex + visibleCount).coerceAtMost(allYValues.lastIndex)

    if (startIndex > allYValues.lastIndex) return null

    val startSegment = (startIndex / segmentSize).coerceAtMost(segmentCache.lastIndex)
    val endSegment = (endIndex / segmentSize).coerceAtMost(segmentCache.lastIndex)
    var visibleMin = Double.MAX_VALUE
    var visibleMax = -Double.MAX_VALUE

    for (i in startSegment..endSegment) {
      val (segMin, segMax) = segmentCache[i]
      visibleMin = min(visibleMin, segMin)
      visibleMax = max(visibleMax, segMax)
    }

    if (visibleMin > visibleMax) return null
    return visibleMin to visibleMax
  }

  /**
   * Calls the consumer's callback to compute the display range and ticks.
   * Returns null if the callback returns a zero-length range.
   */
  internal fun computeDisplayRange(
    visibleMinY: Double,
    visibleMaxY: Double,
  ): Pair<ClosedRange<Double>, List<Double>>? {
    val result = try {
      onVisibleRange(visibleMinY, visibleMaxY)
    } catch (_: Exception) {
      return null
    }
    val range = result.first
    val length = range.endInclusive - range.start
    if (length <= 0.0 || length.isNaN() || length.isInfinite()) return null
    return result
  }

  internal data class ScrollInfo(
    val scrollPixels: Float,
    val xSpacing: Float,
    val chartWidth: Float,
  )
}

/**
 * Creates and remembers a [ScrollAwareRangeProvider].
 */
@Composable
public fun rememberScrollAwareRangeProvider(
  segmentSize: Int = 10,
  debounceMs: Long = 100L,
  animDurationMs: Int = 300,
  onVisibleRange: (minY: Double, maxY: Double) -> Pair<ClosedRange<Double>, List<Double>>,
): ScrollAwareRangeProvider =
  remember(segmentSize, debounceMs, animDurationMs) {
    ScrollAwareRangeProvider(segmentSize, debounceMs, animDurationMs, onVisibleRange)
  }
