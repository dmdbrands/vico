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

package com.patrykandpatrick.vico.core.cartesian.layer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import androidx.annotation.RestrictTo
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.annotation.FloatRange
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.core.cartesian.getVisibleXRange
import com.patrykandpatrick.vico.core.cartesian.axis.Axis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartRanges
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.LineCartesianLayerDrawingModel
import com.patrykandpatrick.vico.core.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.core.cartesian.data.MutableCartesianChartRanges
import com.patrykandpatrick.vico.core.cartesian.data.forEachIn
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer.Line
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer.PointConnector
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.core.cartesian.marker.MutableLineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.core.common.Defaults
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.Position
import com.patrykandpatrick.vico.core.common.component.Component
import com.patrykandpatrick.vico.core.common.component.TextComponent
import com.patrykandpatrick.vico.core.common.data.CacheStore
import com.patrykandpatrick.vico.core.common.data.CartesianLayerDrawingModelInterpolator
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.data.MutableExtraStore
import com.patrykandpatrick.vico.core.common.doubled
import com.patrykandpatrick.vico.core.common.getBitmap
import com.patrykandpatrick.vico.core.common.getRepeating
import com.patrykandpatrick.vico.core.common.getStart
import com.patrykandpatrick.vico.core.common.half
import com.patrykandpatrick.vico.core.common.inBounds
import com.patrykandpatrick.vico.core.common.orZero
import com.patrykandpatrick.vico.core.common.saveLayer
import java.util.Objects
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the content of line charts.
 *
 * @property lineProvider provides the [Line]s.
 * @property pointSpacingDp the point spacing (in dp).
 * @property rangeProvider overrides the _x_ and _y_ ranges.
 * @property verticalAxisPosition the position of the [VerticalAxis] with which the
 *   [LineCartesianLayer] should be associated. Use this for independent [CartesianLayer] scaling.
 * @property drawingModelInterpolator interpolates the [LineCartesianLayerDrawingModel]s.
 */
@Stable
public open class LineCartesianLayer
protected constructor(
  protected val lineProvider: LineProvider,
  protected val pointSpacingDp: Float = Defaults.POINT_SPACING,
  protected val rangeProvider: CartesianLayerRangeProvider = CartesianLayerRangeProvider.auto(),
  protected val verticalAxisPosition: Axis.Position.Vertical? = null,
  protected val drawingModelInterpolator:
    CartesianLayerDrawingModelInterpolator<
      LineCartesianLayerDrawingModel.Entry,
      LineCartesianLayerDrawingModel,
    > =
    CartesianLayerDrawingModelInterpolator.default(),
  protected val drawingModelKey: ExtraStore.Key<LineCartesianLayerDrawingModel>,
  /**
   * Optional render-time Y transform. Receives the full series, the chart's animation-target
   * `yRange` (from `chartRanges.getTargetYRange(verticalAxisPosition)`), and the current visible
   * X range. Returns a `DoubleArray` of Y values (same length as series) that the layer uses
   * instead of `entry.y` when drawing.
   *
   * The transform is invoked **only on series change or target yRange change** — never per
   * draw frame. Cached output is rendered against the *live* (animated) yRange in `getDrawY`,
   * so the transformed line scales naturally with the primary layer's range animation.
   *
   * Use this for a secondary metric line that needs to render in the primary's Y space — the
   * transform projects raw values into the primary's range space, eliminating any VM-side
   * renormalization round-trip on every scroll.
   */
  protected val yTransform: ((
    series: List<LineCartesianLayerModel.Entry>,
    yRange: com.patrykandpatrick.vico.core.cartesian.data.CartesianChartRanges.YRange,
    visibleXRange: ClosedFloatingPointRange<Double>,
  ) -> DoubleArray?)? = null,
) : BaseCartesianLayer<LineCartesianLayerModel>() {
  /**
   * Library-internal accessor for `CartesianChartHost` to detect scroll-aware range
   * providers attached to this layer (e.g. `ScrollAwareRangeProvider`). Not part of the
   * public API — restricted to vico's own modules.
   */
  /** @suppress */
  public val internalRangeProvider: CartesianLayerRangeProvider
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    get() = rangeProvider

  /**
   * Library-internal accessor for the layer's vertical-axis position so
   * `CartesianChartHost` can scope live-range animation to only the matching axis. Not part
   * of the public API.
   */
  /** @suppress */
  public val internalVerticalAxisPosition: Axis.Position.Vertical?
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    get() = verticalAxisPosition

  /**
   * Defines the appearance of a line in a line chart.
   *
   * @property fill draws the line fill.
   * @property stroke defines the style of the stroke.
   * @property areaFill draws the area fill.
   * @property pointProvider provides the [Point]s.
   * @property pointConnector connects the line’s points, thus defining its shape.
   * @property dataLabel used for the data labels.
   * @property dataLabelPosition the vertical position of the data labels relative to the points.
   * @property dataLabelValueFormatter formats the data-label values.
   * @property dataLabelRotationDegrees the data-label rotation (in degrees).
   */
  public open class Line(
    protected val fill: LineFill,
    public val stroke: LineStroke = LineStroke.Continuous(),
    protected val areaFill: AreaFill? = null,
    public val pointProvider: PointProvider? = null,
    public val pointConnector: PointConnector = PointConnector.Sharp,
    public val connectionCondition: ((LineCartesianLayerModel.Entry, LineCartesianLayerModel.Entry?) -> Boolean)? = null,
    public val dataLabel: TextComponent? = null,
    public val dataLabelPosition: Position.Vertical = Position.Vertical.Top,
    public val dataLabelValueFormatter: CartesianValueFormatter = CartesianValueFormatter.decimal(),
    public val dataLabelRotationDegrees: Float = 0f,
  ) {
    protected val linePaint: Paint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Draws the line. */
    public fun draw(
      context: CartesianDrawingContext,
      path: Path,
      lineCanvas: Canvas,
      fillCanvas: Canvas,
      verticalAxisPosition: Axis.Position.Vertical?,
    ) {
      with(context) {
        stroke.apply(this, linePaint)
        val halfThickness = stroke.thicknessDp.pixels.half
        areaFill?.draw(context, path, halfThickness, verticalAxisPosition)
        lineCanvas.drawPath(path, linePaint)
        withCanvas(fillCanvas) { fill.draw(context, halfThickness, verticalAxisPosition) }
      }
    }
  }

  /** Draws a [LineCartesianLayer] line’s fill. */
  public interface LineFill {
    /** Draws the line fill. */
    public fun draw(
      context: CartesianDrawingContext,
      halfLineThickness: Float,
      verticalAxisPosition: Axis.Position.Vertical?,
    )

    /** Houses [LineFill] factory functions. */
    public companion object {
      /** Uses a single [Fill]. */
      public fun single(fill: Fill): LineFill = SingleLineFill(fill)

      /**
       * Uses [topFill] for the portions of the line that are above the [splitY] line, and
       * analogously for [bottomFill]. (The [splitY] line is an imaginary horizontal line whose _y_
       * value is determined by [splitY].)
       */
      public fun double(
        topFill: Fill,
        bottomFill: Fill,
        splitY: (ExtraStore) -> Number = { 0 },
      ): LineFill = DoubleLineFill(topFill, bottomFill, splitY)
    }
  }

  /** Defines the style of a [LineCartesianLayer] line’s stroke. */
  @Immutable
  public sealed interface LineStroke {

    /** The stroke thickness (in dp). */
    public val thicknessDp: Float

    /** Applies the stroke style to [paint]. */
    public fun apply(context: CartesianDrawingContext, paint: Paint)

    /**
     * Produces a continuous stroke.
     *
     * @property cap the stroke cap.
     */
    public data class Continuous(
      override val thicknessDp: Float = Defaults.LINE_SPEC_THICKNESS_DP,
      public val cap: Paint.Cap = Paint.Cap.BUTT,
    ) : LineStroke {
      override fun apply(context: CartesianDrawingContext, paint: Paint) {
        with(context) {
          paint.strokeWidth = thicknessDp.pixels
          paint.strokeCap = cap
          paint.pathEffect = null
        }
      }
    }

    /**
     * Produces a dashed stroke.
     *
     * @property cap the stroke cap.
     * @property dashLengthDp the dash length (in dp).
     * @property gapLengthDp the gap length (in dp).
     */
    public data class Dashed(
      public override val thicknessDp: Float = Defaults.LINE_SPEC_THICKNESS_DP,
      public val cap: Paint.Cap = Paint.Cap.BUTT,
      public val dashLengthDp: Float = Defaults.LINE_DASH_LENGTH,
      public val gapLengthDp: Float = Defaults.LINE_GAP_LENGTH,
    ) : LineStroke {
      override fun apply(context: CartesianDrawingContext, paint: Paint) {
        with(context) {
          paint.strokeWidth = thicknessDp.pixels
          paint.strokeCap = cap
          paint.pathEffect =
            DashPathEffect(floatArrayOf(dashLengthDp.pixels, gapLengthDp.pixels), 0f)
        }
      }
    }

    /** Provides access to [LineStroke] factory functions. */
    public companion object
  }

  /** Draws a [LineCartesianLayer] line’s area fill. */
  public interface AreaFill {
    /** Draws the area fill. */
    public fun draw(
      context: CartesianDrawingContext,
      linePath: Path,
      halfLineThickness: Float,
      verticalAxisPosition: Axis.Position.Vertical?,
    )

    /** Houses [AreaFill] factory functions. */
    public companion object {
      /**
       * Uses [fill] for the areas bounded by the [LineCartesianLayer] line and the [splitY] line.
       * (The [splitY] line is an imaginary horizontal line whose _y_ value is determined by
       * [splitY].)
       */
      public fun single(fill: Fill, splitY: (ExtraStore) -> Number = { 0 }): AreaFill =
        SingleAreaFill(fill, splitY)

      /**
       * Uses [topFill] for those areas bounded by the [LineCartesianLayer] line and the [splitY]
       * line that are above the [splitY] line, and analogously for [bottomFill]. (The [splitY] line
       * is an imaginary horizontal line whose _y_ value is determined by [splitY].)
       */
      public fun double(
        topFill: Fill,
        bottomFill: Fill,
        splitY: (ExtraStore) -> Number = { 0 },
      ): AreaFill = DoubleAreaFill(topFill, bottomFill, splitY)
    }
  }

  /** Connects a [LineCartesianLayer] line's points, thus defining its shape. */
  public interface PointConnector {
    /** Connects ([x1], [y1]) and ([x2], [y2]). */
    public fun connect(
      context: CartesianDrawingContext,
      path: Path,
      x1: Float,
      y1: Float,
      x2: Float,
      y2: Float,
      entry1: LineCartesianLayerModel.Entry,
      entry2: LineCartesianLayerModel.Entry,
      series: List<LineCartesianLayerModel.Entry>,
    )

    /** Houses a [PointConnector] factory function. */
    public companion object {
      /** Uses line segments. */
      public val Sharp: PointConnector = object : PointConnector {
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
          path.lineTo(x2, y2)
        }
      }

      /**
       * Uses cubic Bézier curves. [curvature], which must be in ([0, 1]], defines their strength.
       */
      public fun cubic(
        @FloatRange(from = 0.0, to = 1.0, fromInclusive = false) curvature: Float = 0.5f
      ): PointConnector = CubicPointConnector(curvature)

      /**
       * Uses monotone cubic interpolation (Fritsch-Carlson algorithm).
       *
       * This produces iOS-style smooth curves similar to SwiftUI's `.monotone` interpolation:
       * - No overshoot at local extrema (peaks and valleys are preserved)
       * - Smooth, continuous first derivative
       * - Data-driven tangents, not heuristic-based
       * - GPU-friendly cubic Bézier output
       *
       * Ideal for health/fitness charts, stock charts, or any data where overshoots
       * would misrepresent the data.
       */
      public fun monotone(): PointConnector = MonotonePointConnector(0.5f)

      /**
       * Connects points only when the condition is met. If condition is false, moves to the new point instead of connecting.
       * @param condition Function that determines whether to connect two points based on their coordinates and context
       * @param fallbackConnector The connector to use when condition is true (defaults to Sharp)
       */
      public fun conditional(
        condition: (CartesianDrawingContext, Float, Float, Float, Float) -> Boolean,
        fallbackConnector: PointConnector = Sharp
      ): PointConnector = ConditionalPointConnector(condition, fallbackConnector)

      /**
       * Connects points only when the condition is met. If condition is false, moves to the new point instead of connecting.
       * @param condition Function that determines whether to connect two points based on their coordinates
       * @param fallbackConnector The connector to use when condition is true (defaults to Sharp)
       */
      public fun conditional(
        condition: (Float, Float, Float, Float) -> Boolean,
        fallbackConnector: PointConnector = Sharp
      ): PointConnector = ConditionalPointConnector({ _, x1, y1, x2, y2 -> condition(x1, y1, x2, y2) }, fallbackConnector)
    }
  }

  /**
   * A PointConnector that only connects points when a condition is met.
   * If the condition is false, it moves to the new point instead of connecting.
   */
  private class ConditionalPointConnector(
    private val condition: (CartesianDrawingContext, Float, Float, Float, Float) -> Boolean,
    private val fallbackConnector: PointConnector
  ) : PointConnector {
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
      if (condition(context, x1, y1, x2, y2)) {
        fallbackConnector.connect(context, path, x1, y1, x2, y2, entry1, entry2, series)
      } else {
        // Move to the new point instead of connecting
        path.moveTo(x2, y2)
      }
    }
  }

  /** Provides [Line]s to [LineCartesianLayer]s. */
  public fun interface LineProvider {
    /** Returns the [Line] for the specified series. */
    public fun getLine(seriesIndex: Int, extraStore: ExtraStore): Line

    /** Houses [LineProvider] factory functions. */
    public companion object {
      private data class Series(private val lines: List<Line>) : LineProvider {
        override fun getLine(seriesIndex: Int, extraStore: ExtraStore) =
          lines.getRepeating(seriesIndex)
      }

      /**
       * Uses the provided [Line]s. The [Line]s and series are associated by index. If there are
       * more series than [Line]s, [lines] is iterated multiple times.
       */
      public fun series(lines: List<Line>): LineProvider = Series(lines)

      /**
       * Uses the provided [Line]s. The [Line]s and series are associated by index. If there are
       * more series than [Line]s, the [Line] list is iterated multiple times.
       */
      public fun series(vararg lines: Line): LineProvider = series(lines.toList())
    }
  }

  /**
   * Defines a point style.
   *
   * @param component the point [Component].
   * @property sizeDp the point size (in dp).
   */
  @Immutable
  public data class Point(
    private val component: Component,
    public val sizeDp: Float = Defaults.POINT_SIZE,
  ) {
    /** Draws a point at ([x], [y]). */
    public fun draw(context: CartesianDrawingContext, x: Float, y: Float) {
      val halfSize = context.run { sizeDp.half.pixels }
      component.draw(
        context = context,
        left = x - halfSize,
        top = y - halfSize,
        right = x + halfSize,
        bottom = y + halfSize,
      )
    }
  }

  /** Provides [Point]s to [LineCartesianLayer]s. */
  @Immutable
  public interface PointProvider {
    /** Returns the [Point] for the point with the given properties. */
    public fun getPoint(
      entry: LineCartesianLayerModel.Entry,
      seriesIndex: Int,
      extraStore: ExtraStore,
    ): Point?

    /** Returns the largest [Point]. */
    public fun getLargestPoint(extraStore: ExtraStore): Point?

    /** Houses a [PointProvider] factory function. */
    public companion object {
      private data class Single(private val point: Point) : PointProvider {
        override fun getPoint(
          entry: LineCartesianLayerModel.Entry,
          seriesIndex: Int,
          extraStore: ExtraStore,
        ) = point

        override fun getLargestPoint(extraStore: ExtraStore) = point
      }

      /** Uses [point] for each point. */
      public fun single(point: Point): PointProvider = Single(point)
    }
  }

  private val _markerTargets = mutableMapOf<Double, List<MutableLineCartesianLayerMarkerTarget>>()

  protected val linePath: Path = Path()

  protected val lineCanvas: Canvas = Canvas()

  protected val lineFillCanvas: Canvas = Canvas()

  private val srcInPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }

  protected val cacheKeyNamespace: CacheStore.KeyNamespace = CacheStore.KeyNamespace()

  override val markerTargets: Map<Double, List<CartesianMarker.Target>> = _markerTargets

  /**
   * When `true`, the layer skips its cached drawing model and recomputes line positions from
   * the live `chartRanges.getYRange(...)` on every frame. Set this for layers that need to
   * follow another layer's animated Y range — typically the secondary line in a two-layer
   * chart that pairs with `ScrollAwareRangeProvider` on the primary. Has no effect when the
   * layer's own range provider is already live (e.g. `ScrollAwareRangeProvider` itself).
   */
  public var alwaysUseLiveRange: Boolean = false

  // yTransform cache. Mirrors vico 4: yTransform is invoked ONLY on series change or when the
  // animation **target** yRange changes — never per draw frame. During the actual animation
  // frames, the cached transform output is rendered against the current animated yRange, so
  // the secondary line scales naturally with the primary's animation instead of being pinned
  // to a single screen position.
  private var transformCacheResult: DoubleArray? = null
  private var transformIndexMap: Map<Double, Int>? = null
  private var transformLastSeriesKey: Long? = null
  private var transformLastTargetKey: Long = 0L

  // Cross-fade state machine (mirrors vico 4): when the animation target yRange changes, the
  // line fades out the old transformed positions over [LINE_FADE_OUT_MS], swaps to the pending
  // result, then fades the new positions back in over [LINE_FADE_IN_MS]. transformFadeOpacity is
  // Compose-observable — writes trigger redraws so the fade frames advance themselves.
  // Phase: 0 = idle, 1 = fading out, 2 = fading in.
  private var transformFadePhase: Int = 0
  private var transformFadeStartNanos: Long = 0L
  private val _transformFadeOpacity = androidx.compose.runtime.mutableFloatStateOf(1f)
  internal var transformFadeOpacity: Float
    get() = _transformFadeOpacity.floatValue
    private set(value) { _transformFadeOpacity.floatValue = value }
  private var transformPendingResult: DoubleArray? = null
  private var transformPendingIndexMap: Map<Double, Int>? = null

  /** Creates a [LineCartesianLayer]. */
  public constructor(
    lineProvider: LineProvider,
    pointSpacingDp: Float = Defaults.POINT_SPACING,
    rangeProvider: CartesianLayerRangeProvider = CartesianLayerRangeProvider.auto(),
    verticalAxisPosition: Axis.Position.Vertical? = null,
    drawingModelInterpolator:
      CartesianLayerDrawingModelInterpolator<
        LineCartesianLayerDrawingModel.Entry,
        LineCartesianLayerDrawingModel,
      > =
      CartesianLayerDrawingModelInterpolator.default(),
    yTransform: ((
      series: List<LineCartesianLayerModel.Entry>,
      yRange: com.patrykandpatrick.vico.core.cartesian.data.CartesianChartRanges.YRange,
      visibleXRange: ClosedFloatingPointRange<Double>,
    ) -> DoubleArray?)? = null,
  ) : this(
    lineProvider,
    pointSpacingDp,
    rangeProvider,
    verticalAxisPosition,
    drawingModelInterpolator,
    ExtraStore.Key(),
    yTransform,
  )

  override fun drawInternal(context: CartesianDrawingContext, model: LineCartesianLayerModel) {
    with(context) {
      resetTempData()

      // Live-range providers (e.g. ScrollAwareRangeProvider) need the line redrawn from the
      // raw model + current chart ranges every frame — the cached drawing model from
      // `prepareForTransformation` is computed against a snapshot range and would freeze
      // the line at the range that was active when the model arrived. The layer-level
      // `alwaysUseLiveRange` flag opts a layer in even when its own provider isn't live
      // (typical for a secondary layer that pairs with primary's scroll-aware range).
      val drawingModel =
        if (rangeProvider.alwaysUseLiveRange || alwaysUseLiveRange) null
        else extraStore.getOrNull(drawingModelKey)

      // Refresh yTransform cache once per draw call, before any forEachPointInBounds use.
      // Cache invalidates only on series-identity change or animation target change, so the
      // cached output renders against an animated live yRange without per-frame recomputation.
      refreshTransformCache(model)

      model.series.forEachIndexed { seriesIndex, series ->
        val pointInfoMap = drawingModel?.getOrNull(seriesIndex)

        linePath.rewind()
        val line = lineProvider.getLine(seriesIndex, model.extraStore)

        var prevX = layerBounds.getStart(isLtr = isLtr)
        var prevY = layerBounds.bottom

        val drawingStartAlignmentCorrection =
          layoutDirectionMultiplier * layerDimensions.startPadding

        val drawingStart =
          layerBounds.getStart(isLtr = isLtr) + drawingStartAlignmentCorrection - scroll

        var previousEntry: LineCartesianLayerModel.Entry? = null

        forEachPointInBounds(
          series = series,
          drawingStart = drawingStart,
          pointInfoMap = pointInfoMap,
          drawFullLineLength = line.stroke is LineStroke.Dashed,
        ) { entry, x, y, _, _ ->
          if (linePath.isEmpty) {
            linePath.moveTo(x, y)
          } else {
            // Check connection condition if provided
            val shouldConnect = line.connectionCondition?.invoke(previousEntry!!, entry) ?: true

            if (shouldConnect) {
              line.pointConnector.connect(this, linePath, prevX, prevY, x, y, previousEntry!!, entry, series)
            } else {
              // Move to the new point without connecting (creates a gap)
              linePath.moveTo(x, y)
            }
          }
          prevX = x
          prevY = y
          previousEntry = entry
        }

                canvas.saveLayer(opacity = (drawingModel?.opacity ?: 1f) * transformFadeOpacity)

        val lineBitmap = getBitmap(cacheKeyNamespace, seriesIndex, "line")
        lineCanvas.setBitmap(lineBitmap)
        val lineFillBitmap = getBitmap(cacheKeyNamespace, seriesIndex, "lineFill")
        lineFillCanvas.setBitmap(lineFillBitmap)
        line.draw(context, linePath, lineCanvas, lineFillCanvas, verticalAxisPosition)
        lineCanvas.drawBitmap(lineFillBitmap, 0f, 0f, srcInPaint)

        // Extend line horizontally (left/right) to match points and avoid jumps; keep vertical clip at layerBounds.
        val marginStart = context.layerMargins?.getLeft(isLtr) ?: 0f
        val marginEnd = context.layerMargins?.getRight(isLtr) ?: 0f
        canvas.save()
        canvas.clipRect(
          layerBounds.left - marginStart,
          layerBounds.top,
          layerBounds.right + marginEnd,
          layerBounds.bottom,
        )
        canvas.drawBitmap(lineBitmap, 0f, 0f, null)
        canvas.restore()

        forEachPointInBounds(series, drawingStart, pointInfoMap) { entry, x, y, _, _ ->
          updateMarkerTargets(entry, x, y, lineFillBitmap)
        }

        drawPointsAndDataLabels(line, series, seriesIndex, drawingStart, pointInfoMap)

        canvas.restore()
      }
    }
  }

  protected open fun CartesianDrawingContext.updateMarkerTargets(
    entry: LineCartesianLayerModel.Entry,
    canvasX: Float,
    canvasY: Float,
    lineFillBitmap: Bitmap,
  ) {
    if (canvasX <= layerBounds.left - 1 || canvasX >= layerBounds.right + 1) return
    val limitedCanvasY = canvasY.coerceIn(layerBounds.top, layerBounds.bottom)
    _markerTargets
      .getOrPut(entry.x) { listOf(MutableLineCartesianLayerMarkerTarget(entry.x, canvasX)) }
      .first()
      .points +=
      LineCartesianLayerMarkerTarget.Point(
        entry,
        limitedCanvasY,
        lineFillBitmap.getPixel(
          canvasX
            .roundToInt()
            .coerceIn(ceil(layerBounds.left).toInt(), layerBounds.right.toInt() - 1),
          limitedCanvasY.roundToInt(),
        ),
      )
  }

  protected open fun CartesianDrawingContext.drawPointsAndDataLabels(
    line: Line,
    series: List<LineCartesianLayerModel.Entry>,
    seriesIndex: Int,
    drawingStart: Float,
    pointInfoMap: Map<Double, LineCartesianLayerDrawingModel.Entry>?,
  ) {
    forEachPointInBounds(
      series = series,
      drawingStart = drawingStart,
      pointInfoMap = pointInfoMap,
    ) { chartEntry, x, y, previousX, nextX ->
      val point = line.pointProvider?.getPoint(chartEntry, seriesIndex, model.extraStore)
      point?.draw(this, x, y)

      line.dataLabel
        .takeIf {
          chartEntry.x != ranges.minX && chartEntry.x != ranges.maxX ||
            chartEntry.x == ranges.minX && layerDimensions.startPadding > 0 ||
            chartEntry.x == ranges.maxX && layerDimensions.endPadding > 0
        }
        ?.let { textComponent ->
          val distanceFromLine = max(line.stroke.thicknessDp, point?.sizeDp.orZero).half.pixels

          val text = line.dataLabelValueFormatter.format(this, chartEntry.y, verticalAxisPosition)
          val maxWidth = getMaxDataLabelWidth(chartEntry, x, previousX, nextX)
          val verticalPosition =
            line.dataLabelPosition.inBounds(
              bounds = layerBounds,
              componentHeight =
                textComponent.getHeight(
                  context = this,
                  text = text,
                  maxWidth = maxWidth,
                  rotationDegrees = line.dataLabelRotationDegrees,
                ),
              referenceY = y,
              referenceDistance = distanceFromLine,
            )
          val dataLabelY =
            y +
              when (verticalPosition) {
                Position.Vertical.Top -> -distanceFromLine
                Position.Vertical.Center -> 0f
                Position.Vertical.Bottom -> distanceFromLine
              }
          textComponent.draw(
            context = this,
            x = x,
            y = dataLabelY,
            text = text,
            verticalPosition = verticalPosition,
            maxWidth = maxWidth,
            rotationDegrees = line.dataLabelRotationDegrees,
          )
        }
    }
  }

  protected fun CartesianDrawingContext.getMaxDataLabelWidth(
    entry: LineCartesianLayerModel.Entry,
    x: Float,
    previousX: Float?,
    nextX: Float?,
  ): Int =
    when {
      previousX != null && nextX != null -> min(x - previousX, nextX - x)
      previousX == null && nextX == null ->
        min(layerDimensions.startPadding, layerDimensions.endPadding).doubled
      nextX != null -> {
        ((entry.x - ranges.minX) / ranges.xStep * layerDimensions.xSpacing +
            layerDimensions.startPadding)
          .doubled
          .toFloat()
          .coerceAtMost(nextX - x)
      }
      else -> {
        ((ranges.maxX - entry.x) / ranges.xStep * layerDimensions.xSpacing +
            layerDimensions.endPadding)
          .doubled
          .toFloat()
          .coerceAtMost(x - previousX!!)
      }
    }.toInt()

  protected fun resetTempData() {
    _markerTargets.clear()
    linePath.rewind()
  }

  /**
   * Refreshes the [yTransform] cache and advances the cross-fade state machine. Invoked once
   * per `drawInternal` call. Mirrors vico 4's behavior exactly:
   *
   *  - **First compute / series change**: invoke transform with the *current* `yRange` and
   *    snap (no fade). `transformFadePhase = 0`, `transformFadeOpacity = 1f`.
   *  - **Animation target changes while phase=0**: invoke transform with the *target* yRange
   *    (so by the time the animation lands, the secondary's positions match), save as
   *    pending, kick off phase 1 (fade out).
   *  - **Phase 1 (fade out)**: `opacity = 1 − t` over [LINE_FADE_OUT_MS]; on completion, swap
   *    pending → cache, advance to phase 2, opacity = 0.
   *  - **Phase 2 (fade in)**: `opacity = t` over [LINE_FADE_IN_MS]; on completion, return to
   *    phase 0, opacity = 1.
   *
   * `transformFadeOpacity` is a Compose-observable `mutableFloatStateOf` — writes invalidate
   * the Canvas reading it (via `saveLayer(opacity = … * transformFadeOpacity)`), so the fade
   * frames advance via Compose's normal redraw path.
   */
  private fun CartesianDrawingContext.refreshTransformCache(model: LineCartesianLayerModel) {
    val transform = yTransform ?: return
    val series = model.series.firstOrNull() ?: return
    val seriesKey = series.hashCode().toLong()
    val seriesChanged = transformLastSeriesKey != null && transformLastSeriesKey != seriesKey

    val yRange = ranges.getYRange(verticalAxisPosition)
    val targetYRange = ranges.getTargetYRange(verticalAxisPosition)
    val targetKey = targetYRange.minY.toBits() xor (targetYRange.maxY.toBits() * 31)

    // First compute or series changed → snap (no fade), use CURRENT yRange.
    if (transformCacheResult == null || seriesChanged) {
      val visibleX = getVisibleXRange()
      transformCacheResult = transform.invoke(series, yRange, visibleX)
      transformIndexMap = series.withIndex().associate { (i, e) -> e.x to i }
      transformLastSeriesKey = seriesKey
      transformLastTargetKey = targetKey
      transformFadePhase = 0
      transformFadeOpacity = 1f
    }

    // Target changed while idle → recompute with TARGET yRange + start fade-out.
    if (targetKey != transformLastTargetKey && transformFadePhase == 0) {
      val visibleX = getVisibleXRange()
      transformPendingResult = transform.invoke(series, targetYRange, visibleX)
      transformPendingIndexMap = series.withIndex().associate { (i, e) -> e.x to i }
      transformLastTargetKey = targetKey
      transformFadePhase = 1
      transformFadeStartNanos = System.nanoTime()
    }

    // Fade state machine.
    when (transformFadePhase) {
      1 -> {
        val elapsedMs = (System.nanoTime() - transformFadeStartNanos) / 1_000_000.0
        val t = (elapsedMs / LINE_FADE_OUT_MS).coerceIn(0.0, 1.0)
        transformFadeOpacity = (1.0 - t).toFloat()
        if (t >= 1.0) {
          transformCacheResult = transformPendingResult
          transformIndexMap = transformPendingIndexMap
          transformPendingResult = null
          transformPendingIndexMap = null
          transformFadePhase = 2
          transformFadeStartNanos = System.nanoTime()
          transformFadeOpacity = 0f
        }
      }
      2 -> {
        val elapsedMs = (System.nanoTime() - transformFadeStartNanos) / 1_000_000.0
        val t = (elapsedMs / LINE_FADE_IN_MS).coerceIn(0.0, 1.0)
        transformFadeOpacity = t.toFloat()
        if (t >= 1.0) {
          transformFadePhase = 0
          transformFadeOpacity = 1f
        }
      }
      else -> transformFadeOpacity = 1f
    }
  }

  protected open fun CartesianDrawingContext.forEachPointInBounds(
    series: List<LineCartesianLayerModel.Entry>,
    drawingStart: Float,
    pointInfoMap: Map<Double, LineCartesianLayerDrawingModel.Entry>?,
    drawFullLineLength: Boolean = false,
    action:
      (
        entry: LineCartesianLayerModel.Entry, x: Float, y: Float, previousX: Float?, nextX: Float?,
      ) -> Unit,
  ) {
    val minX = ranges.minX
    val maxX = ranges.maxX
    val xStep = ranges.xStep

    // Read cached transform results populated by refreshTransformCache(model).
    val transformedY = transformCacheResult
    val indexMap = transformIndexMap

    var x: Float? = null
    var nextX: Float? = null

    val boundsStart = layerBounds.getStart(isLtr = isLtr)
    val boundsEnd = boundsStart + layoutDirectionMultiplier * layerBounds.width()

    fun getDrawX(entry: LineCartesianLayerModel.Entry): Float =
      drawingStart +
        layoutDirectionMultiplier * layerDimensions.xSpacing * ((entry.x - minX) / xStep).toFloat()

    fun getDrawY(entry: LineCartesianLayerModel.Entry): Float {
      val yRange = ranges.getYRange(verticalAxisPosition)
      val cached = pointInfoMap?.get(entry.x)?.y
      if (cached != null) {
        return layerBounds.bottom - cached * layerBounds.height()
      }
      val rawY = indexMap?.get(entry.x)
        ?.let { idx -> transformedY?.get(idx) }
        ?: entry.y
      return layerBounds.bottom -
        ((rawY - yRange.minY) / yRange.length).toFloat() * layerBounds.height()
    }

    series.forEachIn(minX = minX, maxX = maxX, padding = 2) { entry, next ->
      val previousX = x
      val immutableX = nextX ?: getDrawX(entry)
      val immutableNextX = next?.let(::getDrawX)
      x = immutableX
      nextX = immutableNextX
      if (
        drawFullLineLength.not() &&
          immutableNextX != null &&
          (isLtr && immutableX < boundsStart || !isLtr && immutableX > boundsStart) &&
          (isLtr && immutableNextX < boundsStart || !isLtr && immutableNextX > boundsStart)
      ) {
        return@forEachIn
      }
      action(entry, immutableX, getDrawY(entry), previousX, nextX)
      if (isLtr && immutableX > boundsEnd || isLtr.not() && immutableX < boundsEnd) return
    }
  }

  override fun updateDimensions(
    context: CartesianMeasuringContext,
    dimensions: MutableCartesianLayerDimensions,
    model: LineCartesianLayerModel,
  ) {
    with(context) {
      val maxPointSize =
        (0..<model.series.size)
          .maxOf {
            lineProvider
              .getLine(it, model.extraStore)
              .pointProvider
              ?.getLargestPoint(model.extraStore)
              ?.sizeDp
              .orZero
          }
          .pixels
      val xSpacing = maxPointSize + pointSpacingDp.pixels
      dimensions.ensureValuesAtLeast(
        xSpacing = xSpacing,
        scalableStartPadding = layerPadding.scalableStartDp.pixels,
        scalableEndPadding = layerPadding.scalableEndDp.pixels,
        unscalableStartPadding = layerPadding.unscalableStartDp.pixels,
        unscalableEndPadding =  layerPadding.unscalableEndDp.pixels,
      )
    }
  }

  override fun updateChartRanges(
    chartRanges: MutableCartesianChartRanges,
    model: LineCartesianLayerModel,
  ) {
    chartRanges.tryUpdate(
      rangeProvider.getMinX(model.minX, model.maxX, model.extraStore),
      rangeProvider.getMaxX(model.minX, model.maxX, model.extraStore),
      rangeProvider.getMinY(model.minY, model.maxY, model.extraStore),
      rangeProvider.getMaxY(model.minY, model.maxY, model.extraStore),
      verticalAxisPosition,
    )
  }

  override fun updateLayerMargins(
    context: CartesianMeasuringContext,
    layerMargins: CartesianLayerMargins,
    layerDimensions: CartesianLayerDimensions,
    model: LineCartesianLayerModel,
  ) {
    with(context) {
      val verticalMargin =
        (0..<model.series.size)
          .mapNotNull { lineProvider.getLine(it, model.extraStore) }
          .maxOf {
            max(
              it.stroke.thicknessDp,
              it.pointProvider?.getLargestPoint(model.extraStore)?.sizeDp.orZero,
            )
          }
          .half
          .pixels
      if (verticalAxisPosition != null) {
        // Ensure stored margin is pointSize.half per edge so points are not clipped.
        // CartesianLayerMargins.ensureValuesAtLeast halves top/bottom when storing, so pass 2x
        // to get stored value = verticalMargin (pointSize.half). Start/end are stored as-is.
        layerMargins.ensureValuesAtLeast(
          start = verticalMargin,
          end = verticalMargin,
          top = verticalMargin * 2,
          bottom = verticalMargin * 2,
        )
      } else {
        layerMargins.ensureValuesAtLeast(top = verticalMargin, bottom = verticalMargin)
      }
    }
  }

  override fun prepareForTransformation(
    model: LineCartesianLayerModel?,
    ranges: CartesianChartRanges,
    extraStore: MutableExtraStore,
  ) {
    drawingModelInterpolator.setModels(
      old = extraStore.getOrNull(drawingModelKey),
      new = model?.toDrawingModel(ranges),
    )
  }

  override suspend fun transform(extraStore: MutableExtraStore, fraction: Float) {
    drawingModelInterpolator.transform(fraction)?.let { extraStore[drawingModelKey] = it }
      ?: extraStore.remove(drawingModelKey)
  }

  private fun LineCartesianLayerModel.toDrawingModel(
    ranges: CartesianChartRanges
  ): LineCartesianLayerDrawingModel {
    val yRange = ranges.getYRange(verticalAxisPosition)
    return LineCartesianLayerDrawingModel(
      series.map { series ->
        series.associate { entry ->
          entry.x to
            LineCartesianLayerDrawingModel.Entry(
              ((entry.y - yRange.minY) / yRange.length).toFloat()
            )
        }
      }
    )
  }

  /** Creates a new [LineCartesianLayer] based on this one. */
  public fun copy(
    lineProvider: LineProvider = this.lineProvider,
    pointSpacingDp: Float = this.pointSpacingDp,
    rangeProvider: CartesianLayerRangeProvider = this.rangeProvider,
    verticalAxisPosition: Axis.Position.Vertical? = this.verticalAxisPosition,
    drawingModelInterpolator:
      CartesianLayerDrawingModelInterpolator<
        LineCartesianLayerDrawingModel.Entry,
        LineCartesianLayerDrawingModel,
      > =
      this.drawingModelInterpolator,
    yTransform: ((
      series: List<LineCartesianLayerModel.Entry>,
      yRange: com.patrykandpatrick.vico.core.cartesian.data.CartesianChartRanges.YRange,
      visibleXRange: ClosedFloatingPointRange<Double>,
    ) -> DoubleArray?)? = this.yTransform,
  ): LineCartesianLayer =
    LineCartesianLayer(
      lineProvider,
      pointSpacingDp,
      rangeProvider,
      verticalAxisPosition,
      drawingModelInterpolator,
      drawingModelKey,
      yTransform,
    ).also { it.alwaysUseLiveRange = this.alwaysUseLiveRange }

  override fun equals(other: Any?): Boolean =
    this === other ||
      other is LineCartesianLayer &&
        lineProvider == other.lineProvider &&
        pointSpacingDp == other.pointSpacingDp &&
        rangeProvider == other.rangeProvider &&
        verticalAxisPosition == other.verticalAxisPosition &&
        drawingModelInterpolator == other.drawingModelInterpolator &&
        yTransform === other.yTransform &&
        alwaysUseLiveRange == other.alwaysUseLiveRange

  override fun hashCode(): Int =
    Objects.hash(
      lineProvider,
      pointSpacingDp,
      rangeProvider,
      verticalAxisPosition,
      drawingModelInterpolator,
      System.identityHashCode(yTransform),
      alwaysUseLiveRange,
    )

  /** Provides access to [Line] and [Point] factory functions. */
  public companion object {
    /**
     * Creates a line with a connection condition that only connects points when the condition is true.
     * @param fill the line fill
     * @param stroke the line stroke
     * @param connectionCondition function that determines if two consecutive points should be connected
     * @param pointProvider optional point provider
     * @param pointConnector the point connector to use when condition is true
     * @param dataLabel optional data label
     * @param dataLabelPosition the data label position
     * @param dataLabelValueFormatter the data label value formatter
     * @param dataLabelRotationDegrees the data label rotation
     */
    public fun withConnectionCondition(
      fill: LineFill,
      stroke: LineStroke = LineStroke.Continuous(),
      connectionCondition: (LineCartesianLayerModel.Entry, LineCartesianLayerModel.Entry?) -> Boolean,
      pointProvider: PointProvider? = null,
      pointConnector: PointConnector = PointConnector.Sharp,
      dataLabel: TextComponent? = null,
      dataLabelPosition: Position.Vertical = Position.Vertical.Top,
      dataLabelValueFormatter: CartesianValueFormatter = CartesianValueFormatter.decimal(),
      dataLabelRotationDegrees: Float = 0f,
    ): Line = Line(
      fill = fill,
      stroke = stroke,
      pointProvider = pointProvider,
      pointConnector = pointConnector,
      connectionCondition = connectionCondition,
      dataLabel = dataLabel,
      dataLabelPosition = dataLabelPosition,
      dataLabelValueFormatter = dataLabelValueFormatter,
      dataLabelRotationDegrees = dataLabelRotationDegrees,
    )
  }
}

internal fun CartesianDrawingContext.getCanvasSplitY(
  splitY: (ExtraStore) -> Number,
  halfLineThickness: Float,
  verticalAxisPosition: Axis.Position.Vertical?,
): Float {
  val yRange = ranges.getYRange(verticalAxisPosition)
  val base =
    layerBounds.bottom -
      ((splitY(model.extraStore).toDouble() - yRange.minY) / yRange.length).toFloat() *
        layerBounds.height()
  return ceil(base).coerceIn(layerBounds.top..layerBounds.bottom) + ceil(halfLineThickness)
}

// Cross-fade durations for `yTransform` target-change transitions in [LineCartesianLayer].
// Total transition = LINE_FADE_OUT_MS + LINE_FADE_IN_MS (line fades old positions out, swaps
// to new positions, fades them back in).
private const val LINE_FADE_OUT_MS: Long = 100L
private const val LINE_FADE_IN_MS: Long = 150L
