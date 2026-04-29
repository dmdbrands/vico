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

package com.patrykandpatrick.vico.compose.cartesian

import android.annotation.SuppressLint
import android.graphics.RectF
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.data.ScrollAwareRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.component1
import com.patrykandpatrick.vico.compose.cartesian.data.component2
import com.patrykandpatrick.vico.compose.cartesian.data.component3
import com.patrykandpatrick.vico.compose.cartesian.data.component4
import com.patrykandpatrick.vico.core.cartesian.CartesianChart
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.VisibleRange
import com.patrykandpatrick.vico.core.cartesian.axis.Axis
import com.patrykandpatrick.vico.core.cartesian.getVisibleXRange
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartRanges
import com.patrykandpatrick.vico.core.cartesian.data.MutableCartesianChartRanges
import com.patrykandpatrick.vico.core.cartesian.data.toImmutable
import com.patrykandpatrick.vico.core.cartesian.layer.MutableCartesianLayerDimensions
import com.patrykandpatrick.vico.core.common.Defaults.CHART_HEIGHT
import com.patrykandpatrick.vico.core.common.Point
import com.patrykandpatrick.vico.core.common.ValueWrapper
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.getValue
import com.patrykandpatrick.vico.core.common.set
import com.patrykandpatrick.vico.core.common.setValue
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Displays a [CartesianChart].
 *
 * @param chart the [CartesianChart].
 * @param modelProducer creates and updates the [CartesianChartModel].
 * @param modifier the modifier to be applied to the chart.
 * @param scrollState houses information on the [CartesianChart]'s scroll value. Allows for scroll
 *   customization and programmatic scrolling.
 * @param zoomState houses information on the [CartesianChart]'s zoom factor. Allows for zoom
 *   customization.
 * @param animationSpec the [AnimationSpec] for difference animations.
 * @param animateIn whether to run an initial animation when the [CartesianChartHost] enters
 *   composition. The animation is skipped for previews.
 * @param consumeMoveEvents whether to consume move touch events when scroll is disabled and
 *   [CartesianChart.marker] is not null.
 * @param onScrollStopped callback invoked when scrolling stops, providing the current visible range.
 * @param placeholder shown when no [CartesianChartModel] is available.
 */
@Composable
public fun CartesianChartHost(
  chart: CartesianChart,
  modelProducer: CartesianChartModelProducer,
  modifier: Modifier = Modifier,
  scrollState: VicoScrollState = rememberVicoScrollState(),
  zoomState: VicoZoomState = rememberDefaultVicoZoomState(scrollState.scrollEnabled),
  animationSpec: AnimationSpec<Float>? = defaultCartesianDiffAnimationSpec,
  animateIn: Boolean = true,
  consumeMoveEvents: Boolean = false,
  onScrollStopped: ((VisibleRange?) -> Unit)? = null,
  placeholder: @Composable BoxScope.() -> Unit = {},
) {
  val mutableRanges = remember { MutableCartesianChartRanges() }
  val modelWrapper by modelProducer.collectAsState(chart, animationSpec, animateIn, mutableRanges)
  val (model, previousModel, ranges, extraStore) = modelWrapper

  // Animated Y range driven by ScrollAwareRangeEffect. NaN means "no animated range yet"
  // (frame-0 / no scroll-aware provider on the chart) — fall through to the model's natural ranges.
  var animatedMinY by remember { mutableStateOf(Double.NaN) }
  var animatedMaxY by remember { mutableStateOf(Double.NaN) }
  var targetMinY by remember { mutableStateOf(Double.NaN) }
  var targetMaxY by remember { mutableStateOf(Double.NaN) }

  val firstScrollAwareLayer = remember(chart) {
    chart.layers.firstOrNull {
      (it as? LineCartesianLayer)?.internalRangeProvider is ScrollAwareRangeProvider
    } as? LineCartesianLayer
  }
  val firstScrollAwareProvider = firstScrollAwareLayer?.internalRangeProvider as? ScrollAwareRangeProvider

  // Seed fallback: when animated range not yet established (first frame with model data),
  // use provider.seedMinY/seedMaxY supplied synchronously by the caller. Eliminates the
  // 1-2 frame flash of full-model Y range on initial composition / segment switch.
  val effectiveMinY = if (!animatedMinY.isNaN()) animatedMinY
    else firstScrollAwareProvider?.seedMinY?.takeIf { !it.isNaN() } ?: Double.NaN
  val effectiveMaxY = if (!animatedMaxY.isNaN()) animatedMaxY
    else firstScrollAwareProvider?.seedMaxY?.takeIf { !it.isNaN() } ?: Double.NaN

  val effectiveRanges = if (!effectiveMinY.isNaN() && !effectiveMaxY.isNaN()) {
    AnimatedYCartesianChartRanges(
      delegate = ranges,
      animMinY = effectiveMinY,
      animMaxY = effectiveMaxY,
      targetMinY = if (!targetMinY.isNaN()) targetMinY else effectiveMinY,
      targetMaxY = if (!targetMaxY.isNaN()) targetMaxY else effectiveMaxY,
      targetAxisPosition = firstScrollAwareLayer?.internalVerticalAxisPosition,
    )
  } else ranges

  if (model != null && firstScrollAwareProvider != null) {
    ScrollAwareRangeEffect(
      chart = chart,
      model = model,
      onAnimatedRange = { minY, maxY ->
        animatedMinY = minY
        animatedMaxY = maxY
      },
      onTargetRange = { minY, maxY ->
        targetMinY = minY
        targetMaxY = maxY
      },
    )
  }

  CartesianChartHostBox(modifier) {
    if (model != null) {
      CartesianChartHostImpl(
        chart,
        model,
        scrollState,
        zoomState,
        effectiveRanges,
        consumeMoveEvents,
        onScrollStopped,
        previousModel,
        extraStore,
      )
    } else {
      placeholder()
    }
  }
}

/**
 * Displays a [CartesianChart]. This function accepts a [CartesianChartModel]. For dynamic data, use
 * the function overload that accepts a [CartesianChartModelProducer] instance.
 *
 * @param chart the [CartesianChart].
 * @param model the [CartesianChartModel].
 * @param modifier the modifier to be applied to the chart.
 * @param scrollState houses information on the [CartesianChart]'s scroll value. Allows for scroll
 *   customization and programmatic scrolling.
 * @param zoomState houses information on the [CartesianChart]'s zoom factor. Allows for zoom
 *   customization.
 * @param consumeMoveEvents whether to consume move touch events when scroll is disabled and
 *   [CartesianChart.marker] is not null.
 * @param onScrollStopped callback invoked when scrolling stops, providing the current visible range.
 */
@Composable
@SuppressLint("RememberReturnType")
public fun CartesianChartHost(
  chart: CartesianChart,
  model: CartesianChartModel,
  modifier: Modifier = Modifier,
  scrollState: VicoScrollState = rememberVicoScrollState(),
  zoomState: VicoZoomState = rememberDefaultVicoZoomState(scrollState.scrollEnabled),
  consumeMoveEvents: Boolean = false,
  onScrollStopped: ((VisibleRange?) -> Unit)? = null,
) {
  val ranges = remember { MutableCartesianChartRanges() }
  remember(chart, model) {
    ranges.reset()
    chart.updateRanges(ranges, model)
  }
  val baseRanges = ranges.toImmutable()

  // Animated Y range driven by ScrollAwareRangeEffect (see modelProducer overload above).
  var animatedMinY by remember { mutableStateOf(Double.NaN) }
  var animatedMaxY by remember { mutableStateOf(Double.NaN) }
  var targetMinY by remember { mutableStateOf(Double.NaN) }
  var targetMaxY by remember { mutableStateOf(Double.NaN) }

  val firstScrollAwareLayer = remember(chart) {
    chart.layers.firstOrNull {
      (it as? LineCartesianLayer)?.internalRangeProvider is ScrollAwareRangeProvider
    } as? LineCartesianLayer
  }
  val firstScrollAwareProvider = firstScrollAwareLayer?.internalRangeProvider as? ScrollAwareRangeProvider

  val effectiveMinY = if (!animatedMinY.isNaN()) animatedMinY
    else firstScrollAwareProvider?.seedMinY?.takeIf { !it.isNaN() } ?: Double.NaN
  val effectiveMaxY = if (!animatedMaxY.isNaN()) animatedMaxY
    else firstScrollAwareProvider?.seedMaxY?.takeIf { !it.isNaN() } ?: Double.NaN

  val effectiveRanges = if (!effectiveMinY.isNaN() && !effectiveMaxY.isNaN()) {
    AnimatedYCartesianChartRanges(
      delegate = baseRanges,
      animMinY = effectiveMinY,
      animMaxY = effectiveMaxY,
      targetMinY = if (!targetMinY.isNaN()) targetMinY else effectiveMinY,
      targetMaxY = if (!targetMaxY.isNaN()) targetMaxY else effectiveMaxY,
      targetAxisPosition = firstScrollAwareLayer?.internalVerticalAxisPosition,
    )
  } else baseRanges

  if (firstScrollAwareProvider != null) {
    ScrollAwareRangeEffect(
      chart = chart,
      model = model,
      onAnimatedRange = { minY, maxY ->
        animatedMinY = minY
        animatedMaxY = maxY
      },
      onTargetRange = { minY, maxY ->
        targetMinY = minY
        targetMaxY = maxY
      },
    )
  }

  CartesianChartHostBox(modifier) {
    CartesianChartHostImpl(
      chart,
      model,
      scrollState,
      zoomState,
      effectiveRanges,
      consumeMoveEvents,
      onScrollStopped,
    )
  }
}

@Composable
internal fun CartesianChartHostImpl(
  chart: CartesianChart,
  model: CartesianChartModel,
  scrollState: VicoScrollState,
  zoomState: VicoZoomState,
  ranges: CartesianChartRanges,
  consumeMoveEvents: Boolean,
  onScrollStopped: ((VisibleRange?) -> Unit)? = null,
  previousModel: CartesianChartModel? = null,
  extraStore: ExtraStore = ExtraStore.Empty,
) {
  val canvasBounds = remember { RectF() }
  val pointerPosition = remember { mutableStateOf<Point?>(null) }
  var isPointerSelectionInProgress by remember { mutableStateOf(false) }
  val measuringContext =
    rememberCartesianMeasuringContext(
      canvasBounds = canvasBounds,
      extraStore = extraStore,
      model = model,
      ranges = ranges,
      scrollEnabled = scrollState.scrollEnabled,
      zoomEnabled = scrollState.scrollEnabled && zoomState.zoomEnabled,
      layerPadding =
        remember(chart.layerPadding, model.extraStore) { chart.layerPadding(model.extraStore) },
      pointerPosition = pointerPosition.value,
      initialScroll = scrollState.initialScroll,
      scroll = scrollState.value,
      isInitializedScroll = scrollState.initialScrollHandled
    )

  val coroutineScope = rememberCoroutineScope()
  var previousModelID by remember { ValueWrapper<Int?>(null) }
  val layerDimensions = remember { MutableCartesianLayerDimensions() }

  // Cached scroll-aware providers + dedup state for the Canvas-side ScrollInfo emit below.
  // Same identity rules as ScrollAwareRangeEffect — distinctBy provider, since one provider
  // can be shared by multiple layers.
  val scrollAwareProviders = remember(chart) {
    chart.layers.mapNotNull { layer ->
      (layer as? LineCartesianLayer)?.internalRangeProvider as? ScrollAwareRangeProvider
    }.distinct()
  }
  var lastEmittedScroll by remember { mutableStateOf(Float.NaN) }
  var lastEmittedXSpacing by remember { mutableStateOf(0f) }
  var lastEmittedChartWidth by remember { mutableStateOf(0f) }

  LaunchedEffect(scrollState.pointerXDeltas) {
    scrollState.pointerXDeltas.collect { delta ->
      pointerPosition.value?.let { point -> pointerPosition.value = point.copy(point.x + delta) }
    }
  }

  LaunchedEffect(zoomState, scrollState) {
    zoomState.pendingScroll.collect { scrollState.scroll(it) }
  }

  // Monitor scroll changes and trigger callback when scrolling stops
  LaunchedEffect(Unit) {
    scrollState.visibleRange
      .debounce(300) // Wait 300ms after scroll stops
      .distinctUntilChanged()
      .collect { scrollValue ->
        // Only invoke callback after auto-scroll is complete (user-initiated scrolling)
        if (scrollState.isAutoScrollComplete) {
          onScrollStopped?.invoke(scrollState.currentVisibleRange)
        }
      }
  }

  DisposableEffect(scrollState) { onDispose { scrollState.clearUpdated() } }

  val layerBounds = rememberUpdatedState(chart.layerBounds)
  Canvas(
    modifier =
      Modifier.fillMaxSize()
        .pointerInput(
          scrollState = scrollState,
          consumeMoveEvents = consumeMoveEvents,
          isPointerSelectionInProgress = isPointerSelectionInProgress,
          onSelectionStateChange = { isPointerSelectionInProgress = it },
          onPointerPositionChange =
            remember(chart.marker == null) {
              if (chart.marker != null) pointerPosition.component2() else null
            },
          scope = rememberCoroutineScope(),
          onZoom =
            remember(zoomState, scrollState, coroutineScope) {
              if (zoomState.zoomEnabled) {
                { factor, centroid ->
                  coroutineScope.launch {
                    zoomState.zoom(factor, centroid.x, scrollState.value, layerBounds.value)
                  }
                }
              } else {
                null
              }
            },
        )
  ) {
    val canvas = drawContext.canvas.nativeCanvas
    if (canvas.width == 0 || canvas.height == 0) return@Canvas
    canvasBounds.set(left = 0, top = 0, right = size.width, bottom = size.height)

    layerDimensions.clear()
    chart.prepare(measuringContext, layerDimensions)

    if (chart.layerBounds.isEmpty) return@Canvas

    zoomState.update(measuringContext, layerDimensions, chart.layerBounds, scrollState.value)
    scrollState.update(measuringContext, chart.layerBounds, layerDimensions)

    // Emit ScrollInfo to ScrollAwareRangeProviders (cached + deduplicated). Always emit on
    // change — the collector side debounces / skips during initial frames.
    if (scrollAwareProviders.isNotEmpty() &&
      layerDimensions.xSpacing > 0f &&
      !chart.layerBounds.isEmpty
    ) {
      val sp = scrollState.value
      val xs = layerDimensions.xSpacing
      val cw = chart.layerBounds.width()
      if (sp != lastEmittedScroll || xs != lastEmittedXSpacing || cw != lastEmittedChartWidth) {
        lastEmittedScroll = sp
        lastEmittedXSpacing = xs
        lastEmittedChartWidth = cw
        val visible = measuringContext.getVisibleXRange(chart.layerBounds, layerDimensions, sp)
        val info = ScrollAwareRangeProvider.ScrollInfo(
          scrollPixels = sp,
          xSpacing = xs,
          chartWidth = cw,
          visibleXStart = visible.start,
          visibleXEnd = visible.endInclusive,
        )
        scrollAwareProviders.forEach { it.scrollUpdates.tryEmit(info) }
      }
    }

    if (model.id != previousModelID) {
      coroutineScope.launch { scrollState.autoScroll(model, previousModel) }
      previousModelID = model.id
    }

    val drawingContext =
      CartesianDrawingContext(
        measuringContext,
        canvas,
        layerDimensions,
        chart.layerBounds,
        scrollState.value,
        zoomState.value,
        layerMargins = chart.layerMarginsForDrawing,
      )

    // Update the scroll state with the drawing context for getVisibleAxisLabels
    scrollState.updateDrawingContext(drawingContext)

    chart.draw(drawingContext)
    measuringContext.reset()
  }
}

@Composable
private fun CartesianChartHostBox(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
  Box(modifier = modifier.height(CHART_HEIGHT.dp).fillMaxWidth(), content = content)
}

/**
 * Manages [ScrollAwareRangeProvider] lifecycle following an iOS-like animation pattern:
 * tick labels swap instantly (cross-fade approximation), chart content animates positionally
 * via Y-range interpolation (~300ms by default).
 *
 * Three coroutines per provider, scoped under `key(provider)` so Compose tracks state per
 * provider identity even if the layer order changes between recompositions:
 *
 *   1. `LaunchedEffect(model)` — rebuild cache on model change. Wait for first scroll info,
 *      snap (first ever render) or animate (subsequent model changes such as metric switch).
 *   2. `LaunchedEffect(provider)` — observe `scrollUpdates`, debounce, animate Y range,
 *      swap ticks instantly.
 *   3. `LaunchedEffect(Unit)` — `snapshotFlow` from the two `Animatable`s into the provider's
 *      plain fields and back to the host's Compose state via [onAnimatedRange].
 */
@OptIn(FlowPreview::class)
@Composable
private fun ScrollAwareRangeEffect(
  chart: CartesianChart,
  model: CartesianChartModel,
  onAnimatedRange: (minY: Double, maxY: Double) -> Unit,
  onTargetRange: (minY: Double, maxY: Double) -> Unit = { _, _ -> },
) {
  val providers = remember(chart) {
    chart.layers.mapNotNull { layer ->
      if (layer is LineCartesianLayer) {
        (layer.internalRangeProvider as? ScrollAwareRangeProvider)?.let { it to layer }
      } else {
        null
      }
    }.distinctBy { it.first }
  }
  if (providers.isEmpty()) return

  providers.forEach { (provider, layer) ->
    key(provider) {
      val layerIndex = remember(chart, layer) { chart.layers.indexOf(layer) }

      val animMinY = remember { Animatable(Float.NaN) }
      val animMaxY = remember { Animatable(Float.NaN) }
      var isFirstScrollUpdate by remember { mutableStateOf(true) }

      // (1) Rebuild cache on model change. Animatables are NOT reset — they animate
      // smoothly from old range to new range when data changes.
      LaunchedEffect(model) {
        val layerModel =
          model.models.getOrNull(layerIndex) as? com.patrykandpatrick.vico.core.cartesian.data.LineCartesianLayerModel
            ?: return@LaunchedEffect
        provider.buildCache(layerModel.series)

        isFirstScrollUpdate = true
        val firstInfo = provider.scrollUpdates.first()
        val visibleEntries = provider.computeVisibleEntries(firstInfo)
        val xRange = firstInfo.visibleXStart..firstInfo.visibleXEnd
        val result = visibleEntries?.let { provider.computeDisplayRange(it, xRange) }
        if (result != null) {
          val (range, ticks) = result
          provider.currentMinY = range.start
          provider.currentMaxY = range.endInclusive
          provider.currentTicks = ticks
          provider.targetMinY = range.start
          provider.targetMaxY = range.endInclusive
          onTargetRange(range.start, range.endInclusive)
          if (animMinY.value.isNaN()) {
            animMinY.snapTo(range.start.toFloat())
            animMaxY.snapTo(range.endInclusive.toFloat())
            onAnimatedRange(range.start, range.endInclusive)
          } else {
            launch { animMinY.animateTo(range.start.toFloat(), tween(provider.animDurationMs)) }
            launch { animMaxY.animateTo(range.endInclusive.toFloat(), tween(provider.animDurationMs)) }
          }
        } else if (provider.currentTicks.isEmpty() &&
          !provider.seedMinY.isNaN() &&
          !provider.seedMaxY.isNaN() &&
          provider.seedMaxY > provider.seedMinY
        ) {
          // First-pass fallback: visible-entries lookup returned null (e.g. cached ScrollInfo
          // from before the model loaded, or no entry inside the bracketed window). Seed the
          // tick list from `seedMinY/MaxY` so the axis renders labels and downstream code
          // (e.g. `ListItemPlacer`) doesn't see an empty list. Subsequent scroll/data emits
          // will replace these via block (2).
          val span = provider.seedMaxY - provider.seedMinY
          val count = 4
          provider.currentTicks =
            (0 until count).map { i -> provider.seedMinY + span * i / (count - 1) }
        }
        isFirstScrollUpdate = false
      }

      // (2) Subsequent scroll events — debounced + animated.
      LaunchedEffect(provider) {
        provider.scrollUpdates
          .debounce(provider.debounceMs)
          .collect { info ->
            if (!provider.isCacheReady || isFirstScrollUpdate) return@collect
            if (animMinY.value.isNaN()) return@collect

            val visible = provider.computeVisibleEntries(info) ?: return@collect
            val xRange = info.visibleXStart..info.visibleXEnd
            val (range, newTicks) =
              provider.computeDisplayRange(visible, xRange) ?: return@collect
            val targetMin = range.start.toFloat()
            val targetMax = range.endInclusive.toFloat()

            if (abs(targetMin - animMinY.value) < 0.01f &&
              abs(targetMax - animMaxY.value) < 0.01f
            ) return@collect

            // iOS cross-fade approximation — swap ticks instantly while line slides.
            provider.currentTicks = newTicks
            provider.targetMinY = range.start
            provider.targetMaxY = range.endInclusive
            onTargetRange(range.start, range.endInclusive)

            val minJob = launch { animMinY.animateTo(targetMin, tween(provider.animDurationMs)) }
            val maxJob = launch { animMaxY.animateTo(targetMax, tween(provider.animDurationMs)) }
            minJob.join()
            maxJob.join()
          }
      }

      // (3) Mirror Animatable values into the provider's plain fields and the host's Compose
      // state every animation frame.
      LaunchedEffect(Unit) {
        snapshotFlow { animMinY.value to animMaxY.value }.collect { (minY, maxY) ->
          if (minY.isNaN() || maxY.isNaN()) return@collect
          provider.currentMinY = minY.toDouble()
          provider.currentMaxY = maxY.toDouble()
          onAnimatedRange(minY.toDouble(), maxY.toDouble())
        }
      }
    }
  }
}

/**
 * A lightweight [CartesianChartRanges] wrapper that overrides Y range with animated values
 * (and exposes the animation target separately) while delegating X bounds + other queries
 * to the original ranges. Reused across frames — no reset/rebuild required.
 *
 * `getYRange` returns the live (animated) range; `getTargetYRange` returns the value the
 * animation is heading toward. Layers that cache work keyed on target (e.g. `LineCartesianLayer`'s
 * yTransform) only invalidate when the target changes, not on every animation frame.
 */
private class AnimatedYCartesianChartRanges(
  private val delegate: CartesianChartRanges,
  animMinY: Double,
  animMaxY: Double,
  targetMinY: Double = animMinY,
  targetMaxY: Double = animMaxY,
  /**
   * The axis position the animated range applies to. Queries for any other axis (e.g. a
   * dual-axis chart with a separate `End` axis range) delegate to the underlying ranges so
   * the second axis is unaffected by the live animation. `null` means "primary/default
   * axis only" — non-null requested positions still delegate. Pass `null` here for the
   * common single-axis case where the chart only ever queries with a `null` axisPosition.
   */
  private val targetAxisPosition: Axis.Position.Vertical? = null,
) : CartesianChartRanges {
  override val minX: Double get() = delegate.minX
  override val maxX: Double get() = delegate.maxX
  override val xStep: Double get() = delegate.xStep

  private val yRange = object : CartesianChartRanges.YRange {
    override val minY: Double = animMinY
    override val maxY: Double = animMaxY
    override val length: Double = (animMaxY - animMinY).coerceAtLeast(1e-6)
  }

  private val targetRange = object : CartesianChartRanges.YRange {
    override val minY: Double = targetMinY
    override val maxY: Double = targetMaxY
    override val length: Double = (targetMaxY - targetMinY).coerceAtLeast(1e-6)
  }

  private fun appliesTo(axisPosition: Axis.Position.Vertical?): Boolean =
    axisPosition == targetAxisPosition

  override fun getYRange(axisPosition: Axis.Position.Vertical?): CartesianChartRanges.YRange =
    if (appliesTo(axisPosition)) yRange else delegate.getYRange(axisPosition)

  override fun getTargetYRange(axisPosition: Axis.Position.Vertical?): CartesianChartRanges.YRange =
    if (appliesTo(axisPosition)) targetRange else delegate.getTargetYRange(axisPosition)
}
