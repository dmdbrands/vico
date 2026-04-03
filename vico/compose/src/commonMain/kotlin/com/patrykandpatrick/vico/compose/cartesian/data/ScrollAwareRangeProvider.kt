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
import androidx.compose.runtime.rememberUpdatedState
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A [CartesianLayerRangeProvider] that dynamically adjusts the Y range based on the currently
 * visible data points. Follows iOS-like animation: new ticks appear instantly (approximating
 * cross-fade), while chart content animates positionally via range interpolation.
 *
 * Uses binary search on sorted X values for O(log n) visible-window lookup — accurate for
 * both sequential and non-sequential X data.
 *
 * @param paddingEntries extra entries to include before and after the visible window
 *   for stable range computation. Default 1.
 * @param debounceMs milliseconds to wait after scroll settles before updating the range.
 * @param animDurationMs duration of the range animation in milliseconds.
 * @param onVisibleEntries callback receiving the visible entries (+ padding) as (x, y) pairs.
 *   Returns a [Pair] of the display range ([ClosedRange]) and tick label values ([List]).
 */
public class ScrollAwareRangeProvider(
  private val paddingEntries: Int = 1,
  internal val debounceMs: Long = 100L,
  internal val animDurationMs: Int = 300,
  private val onVisibleEntries: (visibleEntries: List<Pair<Double, Double>>) -> Pair<ClosedRange<Double>, List<Double>>,
) : CartesianLayerRangeProvider {

  init {
    require(paddingEntries >= 0) { "paddingEntries must be >= 0" }
  }

  // All entries from the first series, sorted by X. Stores (x, y).
  private var allEntries: List<Pair<Double, Double>> = emptyList()

  // Sorted X values for binary search (parallel to allEntries).
  private var sortedXValues: DoubleArray = DoubleArray(0)

  // Current animated range values — set by ScrollAwareRangeEffect animation.
  internal var currentMinY: Double = Double.NaN
  internal var currentMaxY: Double = Double.NaN


  // X range override — set during composition via direct property access.
  // Plain var, not Compose State — no recomposition from provider side.
  public var xRangeMin: Double = Double.NaN
  public var xRangeMax: Double = Double.NaN

  // Whether entries have been loaded at least once.
  internal var isCacheReady: Boolean = false

  // Current tick labels — read by ListItemPlacer.
  public var currentTicks: List<Double> = emptyList()
    internal set

  // Flow for scroll updates — CartesianChartHost emits to this.
  // replay = 1: Canvas emits before LaunchedEffect subscribes. Replay ensures first emission isn't lost.
  internal val scrollUpdates = MutableSharedFlow<ScrollInfo>(replay = 1, extraBufferCapacity = 10)

  // Cache: avoid recomputing if visible window hasn't changed.
  private var lastVisibleStartIndex: Int = -1
  private var lastVisibleEndIndex: Int = -1
  private var lastVisibleEntries: List<Pair<Double, Double>> = emptyList()

  override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore): Double =
    if (!xRangeMin.isNaN()) xRangeMin else minX

  override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore): Double =
    if (!xRangeMax.isNaN()) xRangeMax else maxX

  override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore): Double =
    if (isCacheReady && !currentMinY.isNaN()) currentMinY else minY

  override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore): Double =
    if (isCacheReady && !currentMaxY.isNaN()) currentMaxY else maxY

  /**
   * Builds the entry list from model data. Called when the model changes.
   * Uses the first series sorted by X.
   */
  internal fun buildCache(series: List<List<LineCartesianLayerModel.Entry>>) {
    if (series.isEmpty() || series.first().isEmpty()) {
      allEntries = emptyList()
      sortedXValues = DoubleArray(0)
      isCacheReady = false
      lastVisibleStartIndex = -1
      return
    }
    val entries = series.first().sortedBy { it.x }
    allEntries = entries.map { it.x to it.y }
    sortedXValues = DoubleArray(entries.size) { entries[it].x }
    isCacheReady = true
    lastVisibleStartIndex = -1
  }

  /**
   * Computes the visible entries (+ padding) based on scroll info.
   * Uses binary search on sorted X values — O(log n).
   * Returns null if not ready or no entries in visible range.
   */
  internal fun computeVisibleEntries(info: ScrollInfo): List<Pair<Double, Double>>? {
    if (!isCacheReady || allEntries.isEmpty() || info.xSpacing <= 0f) return null

    // Use the visible X range directly from scroll info.
    // visibleXStart/End are computed from the actual chart ranges.xStep (not estimated).
    val visibleXStart = info.visibleXStart
    val visibleXEnd = info.visibleXEnd

    // Binary search for start and end indices
    var startIndex = sortedXValues.binarySearchInsertionPoint(visibleXStart)
    var endIndex = sortedXValues.binarySearchInsertionPoint(visibleXEnd)

    // Clamp to valid range
    startIndex = startIndex.coerceIn(0, allEntries.lastIndex)
    endIndex = endIndex.coerceIn(0, allEntries.lastIndex)

    // Add padding entries
    val paddedStart = (startIndex - paddingEntries).coerceAtLeast(0)
    val paddedEnd = (endIndex + paddingEntries).coerceAtMost(allEntries.lastIndex)

    if (paddedStart > paddedEnd) return null

    // Cache check — skip if same window
    if (paddedStart == lastVisibleStartIndex && paddedEnd == lastVisibleEndIndex) {
      return lastVisibleEntries
    }

    val entries = allEntries.subList(paddedStart, paddedEnd + 1)
    lastVisibleStartIndex = paddedStart
    lastVisibleEndIndex = paddedEnd
    lastVisibleEntries = entries
    return entries
  }

  /**
   * Calls the consumer's callback with visible entries.
   * Returns null if the callback returns a zero-length range.
   */
  internal fun computeDisplayRange(
    visibleEntries: List<Pair<Double, Double>>,
  ): Pair<ClosedRange<Double>, List<Double>>? {
    if (visibleEntries.isEmpty()) return null
    val result = try {
      onVisibleEntries(visibleEntries)
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
    val visibleXStart: Double,
    val visibleXEnd: Double,
  )
}

/**
 * Binary search insertion point — returns the index where [value] would be inserted
 * to maintain sorted order. O(log n).
 */
private fun DoubleArray.binarySearchInsertionPoint(value: Double): Int {
  var low = 0
  var high = size
  while (low < high) {
    val mid = (low + high) ushr 1
    if (this[mid] < value) low = mid + 1 else high = mid
  }
  return low
}

/**
 * Creates and remembers a [ScrollAwareRangeProvider].
 *
 * @param paddingEntries extra entries before/after visible window (default 1)
 * @param debounceMs debounce delay after scroll settles (default 100ms)
 * @param animDurationMs range animation duration (default 300ms)
 * @param onVisibleEntries callback receiving visible (x, y) entries + padding.
 *   Returns display range and tick labels.
 */
@Composable
public fun rememberScrollAwareRangeProvider(
  paddingEntries: Int = 1,
  debounceMs: Long = 100L,
  animDurationMs: Int = 300,
  minX: Double = Double.NaN,
  maxX: Double = Double.NaN,
  onVisibleEntries: (visibleEntries: List<Pair<Double, Double>>) -> Pair<ClosedRange<Double>, List<Double>>,
): ScrollAwareRangeProvider {
  val callbackRef = rememberUpdatedState(onVisibleEntries)
  val provider = remember(paddingEntries, debounceMs, animDurationMs) {
    ScrollAwareRangeProvider(paddingEntries, debounceMs, animDurationMs) { entries ->
      callbackRef.value(entries)
    }
  }
  // Update X range from params — no recomposition from provider side (plain var)
  provider.xRangeMin = minX
  provider.xRangeMax = maxX
  return provider
}
