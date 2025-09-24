/*
 * Copyright 2024 by Patryk Goworowski and Patrick Michalik.
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

package com.patrykandpatrick.vico.core.cartesian.axis

import android.opengl.ETC1.getWidth
import android.util.Log
import androidx.annotation.RestrictTo
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis.HorizontalLabelPosition.Inside
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis.HorizontalLabelPosition.Outside
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.formatForAxis
import com.patrykandpatrick.vico.core.cartesian.getMaxScrollDistance
import com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayer
import com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayerDimensions
import com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayerMargins
import com.patrykandpatrick.vico.core.cartesian.layer.HorizontalCartesianLayerMargins
import com.patrykandpatrick.vico.core.cartesian.layer.MutableCartesianLayerDimensions
import com.patrykandpatrick.vico.core.common.Position
import com.patrykandpatrick.vico.core.common.component.LineComponent
import com.patrykandpatrick.vico.core.common.component.TextComponent
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.half
import com.patrykandpatrick.vico.core.common.orZero
import com.patrykandpatrick.vico.core.common.translate
import java.text.DecimalFormat
import kotlin.math.ceil
import kotlin.math.max

private const val TITLE_ABS_ROTATION_DEGREES = 90f

/**
 * Draws vertical axes. See the [BaseAxis] documentation for descriptions of the inherited
 * properties.
 *
 * @property itemPlacer determines for what _y_ values the [VerticalAxis] displays labels, ticks,
 *   and guidelines.
 * @property horizontalLabelPosition defines the horizontal position of the labels relative to the
 *   axis line.
 * @property verticalLabelPosition defines the vertical positions of the labels relative to their
 *   ticks.
 */
public open class VerticalAxis<P : Axis.Position.Vertical>
protected constructor(
  override val position: P,
  line: LineComponent?,
  label: TextComponent?,
  labelRotationDegrees: Float,
  public val horizontalLabelPosition: HorizontalLabelPosition,
  public val verticalLabelPosition: Position.Vertical,
  valueFormatter: CartesianValueFormatter,
  tick: LineComponent?,
  tickLengthDp: Float,
  guideline: LineComponent?,
  public val itemPlacer: ItemPlacer,
  size: Size,
  titleComponent: TextComponent?,
  title: CharSequence?,
  public val markerDecoration: MarkerDecoration? = null,
) :
  BaseAxis<P>(
    line,
    label,
    labelRotationDegrees,
    valueFormatter,
    tick,
    tickLengthDp,
    guideline,
    size,
    titleComponent,
    title,
  ) {
  protected val areLabelsOutsideAtStartOrInsideAtEnd: Boolean
    get() =
      position == Axis.Position.Vertical.Start && horizontalLabelPosition == Outside ||
        position == Axis.Position.Vertical.End && horizontalLabelPosition == Inside

  protected val textHorizontalPosition: Position.Horizontal
    get() =
      if (areLabelsOutsideAtStartOrInsideAtEnd) {
        Position.Horizontal.Start
      } else {
        Position.Horizontal.End
      }

  protected var maxLabelWidth: Float? = null
  private var maxScroll : Float = 0f

  /** @suppress */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  public constructor(
    position: P,
    line: LineComponent?,
    label: TextComponent?,
    labelRotationDegrees: Float,
    horizontalLabelPosition: HorizontalLabelPosition,
    verticalLabelPosition: Position.Vertical,
    tick: LineComponent?,
    tickLengthDp: Float,
    guideline: LineComponent?,
    itemPlacer: ItemPlacer,
    titleComponent: TextComponent?,
    title: CharSequence?,
  ) : this(
    position,
    line,
    label,
    labelRotationDegrees,
    horizontalLabelPosition,
    verticalLabelPosition,
    CartesianValueFormatter.decimal(),
    tick,
    tickLengthDp,
    guideline,
    itemPlacer,
    Size.Auto(),
    titleComponent,
    title,
    null,
  )

  override fun drawUnderLayers(context: CartesianDrawingContext) {
    with(context) {
      val saveCount = canvas.save()
      var centerY: Float
      val yRange = ranges.getYRange(position)
      val maxLabelHeight = getMaxLabelHeight()
      val lineValues =
        itemPlacer.getLineValues(this, bounds.height(), maxLabelHeight, position)
          ?: itemPlacer.getLabelValues(this, bounds.height(), maxLabelHeight, position)

      lineValues.forEach { lineValue ->
        centerY =
          bounds.bottom - bounds.height() * ((lineValue - yRange.minY) / yRange.length).toFloat() +
            getLineCanvasYCorrection(guidelineThickness, lineValue)

        if (size !is Size.Scroll) {
          // Enhanced clipping behavior for Scroll size type
          guideline
            ?.takeIf {
              isNotInRestrictedBounds(
                left = layerBounds.left,
                top = centerY - guidelineThickness.half,
                right = layerBounds.right,
                bottom = centerY + guidelineThickness.half,
              )
            }
            ?.drawHorizontal(
              context = context,
              left = layerBounds.left,
              right = layerBounds.right,
              y = centerY,
            )
        } else {
          // Normal behavior for other size types
          guideline?.drawHorizontal(
            context = context,
            left = layerBounds.left,
            right = layerBounds.right,
            y = centerY,
          )
        }
      }



      val lineExtensionLength = if (itemPlacer.getShiftTopLines(this)) tickThickness else 0f
      val lineX = if (position.isLeft(this)) {
        bounds.right - lineThickness.half
      } else {
        bounds.left - lineThickness.half
      }
      if (size is Size.Scroll) {
        // Enhanced clipping for axis line
        canvas.clipRect(
          layerBounds.left - lineThickness,
          bounds.top,
          layerBounds.right + lineThickness,
          bounds.bottom,
        )
      }

      this@VerticalAxis.maxScroll = context.getMaxScrollDistance()
      val effectiveScroll = if (size is Size.Scroll) {
        if (position.isLeft(this))
         - scroll
        else
          maxScroll - scroll
      } else
       0f




      line?.drawVertical(
        context = context,
        x = lineX + effectiveScroll,
        top = bounds.top - lineExtensionLength,
        bottom = bounds.bottom + lineExtensionLength,
      )
      canvas.restoreToCount(saveCount)
    }
  }

  override fun drawOverLayers(context: CartesianDrawingContext) {
    with(context) {
      val label = label
      val labelValues =
        itemPlacer.getLabelValues(this, bounds.height(), getMaxLabelHeight(), position)
      val tickLeftX = getTickLeftX()
      val tickRightX = tickLeftX + lineThickness + tickLength

      // Calculate label position considering axis size, alignment, and text minWidth
      val axisWidth = when (size) {
        is Size.Fixed -> size.valueDp.pixels
        is Size.Auto -> bounds.width()
        is Size.Fraction -> canvasBounds.width() * size.fraction
        is Size.Text -> bounds.width()
        is Size.Scroll -> size.valueDp.pixels
      }
      // Calculate dynamic offset based on tick length and line thickness
      val offset = (tickLength + lineThickness).half
      val labelX = if (areLabelsOutsideAtStartOrInsideAtEnd == isLtr) {
        // Outside labels - position relative to axis bounds with dynamic offset
        when (horizontalLabelPosition) {
          HorizontalLabelPosition.Outside -> bounds.right - axisWidth.half - offset
          HorizontalLabelPosition.Inside -> bounds.left + axisWidth.half + offset
        }
      } else {
        // Inside labels - position relative to axis bounds with dynamic offset
        when (horizontalLabelPosition) {
          HorizontalLabelPosition.Outside -> bounds.left + axisWidth.half + offset
          HorizontalLabelPosition.Inside -> bounds.right - axisWidth.half - offset
        }
      }
      var tickCenterY: Float
      val yRange = ranges.getYRange(position)

      labelValues.forEach { labelValue ->
        tickCenterY =
          bounds.bottom - bounds.height() * ((labelValue - yRange.minY) / yRange.length).toFloat() +
            getLineCanvasYCorrection(tickThickness, labelValue)

        tick?.drawHorizontal(
          context = context,
          left = tickLeftX,
          right = tickRightX,
          y = tickCenterY,
        )

        label ?: return@forEach
        drawLabelWithCenteredAlignment(
          context = this,
          labelComponent = label,
          label = valueFormatter.formatForAxis(this, labelValue, position),
          labelX = labelX ,
          tickCenterY = tickCenterY,
        )
      }

      title?.let { title ->
        titleComponent?.draw(
          context = this,
          text = title,
          x = if (position.isLeft(this)) bounds.left else bounds.right,
          y = bounds.centerY(),
          horizontalPosition =
            if (position == Axis.Position.Vertical.Start) {
              Position.Horizontal.End
            } else {
              Position.Horizontal.Start
            },
          verticalPosition = Position.Vertical.Center,
          rotationDegrees =
            if (position == Axis.Position.Vertical.Start) {
              -TITLE_ABS_ROTATION_DEGREES
            } else {
              TITLE_ABS_ROTATION_DEGREES
            },
          maxHeight = bounds.height().toInt(),
        )
      }

      // Draw marker decoration if present
      markerDecoration?.let { marker ->
        val markerY = marker.y(model.extraStore)
        val markerLabel = marker.label(model.extraStore)
        val yRange = ranges.getYRange(position)

        // Check if marker Y value is within the visible range
        val isWithinRange = markerY >= yRange.minY && markerY <= yRange.maxY

        val markerCanvasY = if (isWithinRange) {
          // Normal positioning within range using the same method as labels
          bounds.bottom - bounds.height() * ((markerY - yRange.minY) / yRange.length).toFloat() +
            getLineCanvasYCorrection(tickThickness, markerY)
        } else {
          // If outside range, position at top or bottom with offset
          if (markerY < yRange.minY) {
            // Below range - position at bottom with offset
            bounds.bottom + marker.outsideRangeOffset
          } else {
            // Above range - position at top with offset
            bounds.bottom - bounds.height() - marker.outsideRangeOffset
          }
        }

        // Draw marker component if present
        marker.markerComponent?.let { markerComponent ->
          val tickLeftX = getTickLeftX()
          val tickRightX = tickLeftX + lineThickness + tickLength

          // Use the same positioning logic as label component, considering text minWidth
          val axisWidth = when (size) {
            is Size.Fixed -> size.valueDp.pixels
            is Size.Auto -> bounds.width()
            is Size.Fraction -> canvasBounds.width() * size.fraction
            is Size.Text -> bounds.width()
            is Size.Scroll -> size.valueDp.pixels
          }
          // Calculate dynamic offset based on tick length and line thickness
          val offset = (tickLength + lineThickness).half
          val markerX = if (areLabelsOutsideAtStartOrInsideAtEnd == isLtr) {
            // Outside labels - position relative to axis bounds with dynamic offset
            when (marker.horizontalLabelPosition) {
              HorizontalLabelPosition.Outside -> bounds.right - axisWidth.half - offset
              HorizontalLabelPosition.Inside -> bounds.left + axisWidth.half + offset
            }
          } else {
            // Inside labels - position relative to axis bounds with dynamic offset
            when (marker.horizontalLabelPosition) {
              HorizontalLabelPosition.Outside -> bounds.left + axisWidth.half + offset
              HorizontalLabelPosition.Inside -> bounds.right - axisWidth.half - offset
            }
          }

          markerComponent.draw(
            context = this,
            text = markerLabel,
            x = markerX,
            y = markerCanvasY,
            horizontalPosition = Position.Horizontal.Center,
            verticalPosition = marker.verticalLabelPosition,
            rotationDegrees = marker.labelRotationDegrees,
            maxHeight = bounds.height().toInt(),
          )
        }

        // Draw label component if present
        marker.labelComponent?.let { labelComponent ->
          val tickLeftX = getTickLeftX()
          val tickRightX = tickLeftX + lineThickness + tickLength

          // Use the same centering logic as regular axis labels, considering text minWidth
          val axisWidth = when (size) {
            is Size.Fixed -> size.valueDp.pixels
            is Size.Auto -> bounds.width()
            is Size.Fraction -> canvasBounds.width() * size.fraction
            is Size.Text -> bounds.width()
            is Size.Scroll -> size.valueDp.pixels
          }
          // Calculate dynamic offset based on tick length and line thickness
          val offset = (tickLength + lineThickness).half
          val labelX = if (areLabelsOutsideAtStartOrInsideAtEnd == isLtr) {
            // Outside labels - position relative to axis bounds with dynamic offset
            when (marker.horizontalLabelPosition) {
              HorizontalLabelPosition.Outside -> bounds.right - axisWidth.half - offset
              HorizontalLabelPosition.Inside -> bounds.left + axisWidth.half + offset
            }
          } else {
            // Inside labels - position relative to axis bounds with dynamic offset
            when (marker.horizontalLabelPosition) {
              HorizontalLabelPosition.Outside -> bounds.left + axisWidth.half + offset
              HorizontalLabelPosition.Inside -> bounds.right - axisWidth.half - offset
            }
          }

          labelComponent.draw(
            context = this,
            text = markerLabel,
            x = labelX,
            y = markerCanvasY,
            horizontalPosition = Position.Horizontal.Center,
            verticalPosition = marker.verticalLabelPosition,
            rotationDegrees = marker.labelRotationDegrees,
            maxWidth = (maxLabelWidth ?: (layerBounds.width().half - tickLength)).toInt(),
          )
        }
      }
    }
  }

  override fun updateLayerDimensions(
    context: CartesianMeasuringContext,
    layerDimensions: MutableCartesianLayerDimensions,
  ): Unit = Unit

  protected open fun drawLabel(
    context: CartesianDrawingContext,
    labelComponent: TextComponent,
    label: CharSequence,
    labelX: Float,
    tickCenterY: Float,
  ): Unit =
    with(context) {
      val textBounds =
        labelComponent
          .getBounds(context = this, text = label, rotationDegrees = labelRotationDegrees)
          .apply { translate(labelX, tickCenterY - centerY()) }

      if (
        horizontalLabelPosition == Outside ||
          isNotInRestrictedBounds(
            left = textBounds.left,
            top = textBounds.top,
            right = textBounds.right,
            bottom = textBounds.bottom,
          )
      ) {
        labelComponent.draw(
          context = this,
          text = label,
          x = labelX,
          y = tickCenterY,
          horizontalPosition = textHorizontalPosition,
          verticalPosition = verticalLabelPosition,
          rotationDegrees = labelRotationDegrees,
          maxWidth = (maxLabelWidth ?: (layerBounds.width().half - tickLength)).toInt(),
        )
      }
    }

  protected open fun drawLabelWithCenteredAlignment(
    context: CartesianDrawingContext,
    labelComponent: TextComponent,
    label: CharSequence,
    labelX: Float,
    tickCenterY: Float,
  ): Unit =
    with(context) {
      val textBounds =
        labelComponent
          .getBounds(context = this, text = label, rotationDegrees = labelRotationDegrees)
          .apply { translate(labelX, tickCenterY - centerY()) }

      if (
        horizontalLabelPosition == Outside ||
          isNotInRestrictedBounds(
            left = textBounds.left,
            top = textBounds.top,
            right = textBounds.right,
            bottom = textBounds.bottom,
          )
      ) {
        labelComponent.draw(
          context = this,
          text = label,
          x = labelX,
          y = tickCenterY,
          horizontalPosition = Position.Horizontal.Center,
          verticalPosition = verticalLabelPosition,
          rotationDegrees = labelRotationDegrees,
          maxWidth = (maxLabelWidth ?: (layerBounds.width().half - tickLength)).toInt(),
        )
      }
    }

  protected fun CartesianMeasuringContext.getTickLeftX(): Float {
    val onLeft = position.isLeft(this)
    val base = if (onLeft) bounds.right else bounds.left
    return when {
      onLeft && horizontalLabelPosition == Outside -> base - lineThickness - tickLength
      onLeft && horizontalLabelPosition == Inside -> base - lineThickness
      horizontalLabelPosition == Outside -> base
      horizontalLabelPosition == Inside -> base - tickLength
      else -> error("Unexpected combination of axis position and label position")
    }
  }

  override fun updateHorizontalLayerMargins(
    context: CartesianMeasuringContext,
    horizontalLayerMargins: HorizontalCartesianLayerMargins,
    layerHeight: Float,
    model: CartesianChartModel,
  ) {
    val width = getWidth(context, layerHeight)
    val effectiveScroll = if (size is Size.Scroll && size.isLabelScrollable){
      if (position == Axis.Position.Vertical.Start) context.scroll else this.maxScroll - context.scroll } else 0f
    when (position) {
      Axis.Position.Vertical.Start -> horizontalLayerMargins.ensureValuesAtLeast(start = width - effectiveScroll)
      Axis.Position.Vertical.End -> horizontalLayerMargins.ensureValuesAtLeast(end = width - effectiveScroll)
    }
  }

  override fun updateLayerMargins(
    context: CartesianMeasuringContext,
    layerMargins: CartesianLayerMargins,
    layerDimensions: CartesianLayerDimensions,
    model: CartesianChartModel,
  ): Unit =
    with(context) {
      val maxLabelHeight = getMaxLabelHeight()
      val maxLineThickness = max(lineThickness, tickThickness)
      layerMargins.ensureValuesAtLeast(
        top =
          itemPlacer.getTopLayerMargin(
            context,
            verticalLabelPosition,
            maxLabelHeight,
            maxLineThickness,
          ),
        bottom =
          itemPlacer.getBottomLayerMargin(
            context,
            verticalLabelPosition,
            maxLabelHeight,
            maxLineThickness,
          ),
      )
    }

  protected open fun getWidth(context: CartesianMeasuringContext, freeHeight: Float): Float =
    with(context) {
      when (size) {
        is Size.Auto -> {
          val titleComponentWidth =
            title
              ?.let { title ->
                titleComponent?.getWidth(
                  context = this,
                  text = title,
                  rotationDegrees = TITLE_ABS_ROTATION_DEGREES,
                  maxHeight = bounds.height().toInt(),
                )
              }
              .orZero
          val labelSpace =
            when (horizontalLabelPosition) {
              Outside -> ceil(getMaxLabelWidth(freeHeight)).also { maxLabelWidth = it } + tickLength
              Inside -> 0f
            }
          (labelSpace + titleComponentWidth + lineThickness).coerceIn(
            size.minDp.pixels,
            size.maxDp.pixels,
          )
        }
        is Size.Fixed -> size.valueDp.pixels
        is Size.Fraction -> canvasBounds.width() * size.fraction
        is Size.Text ->
          label
            ?.getWidth(context = this, text = size.text, rotationDegrees = labelRotationDegrees)
            .orZero + tickLength + lineThickness.half
        is Size.Scroll -> size.valueDp.pixels
      }
    }

  protected fun CartesianMeasuringContext.getMaxLabelHeight(): Float =
    label
      ?.let { label ->
        itemPlacer.getHeightMeasurementLabelValues(this, position).maxOfOrNull { value ->
          label.getHeight(
            context = this,
            text = valueFormatter.formatForAxis(this, value, position),
            rotationDegrees = labelRotationDegrees,
          )
        }
      }
      .orZero

  protected fun CartesianMeasuringContext.getMaxLabelWidth(axisHeight: Float): Float =
    label
      ?.let { label ->
        itemPlacer
          .getWidthMeasurementLabelValues(this, axisHeight, getMaxLabelHeight(), position)
          .maxOfOrNull { value ->
            label.getWidth(
              context = this,
              text = valueFormatter.formatForAxis(this, value, position),
              rotationDegrees = labelRotationDegrees,
            )
          }
      }
      .orZero

  protected fun CartesianDrawingContext.getLineCanvasYCorrection(
    thickness: Float,
    y: Double,
  ): Float =
    if (y == ranges.getYRange(position).maxY && itemPlacer.getShiftTopLines(this)) {
      -thickness.half
    } else {
      thickness.half
    }

  /** Creates a new [VerticalAxis] based on this one. */
  public fun copy(
    line: LineComponent? = this.line,
    label: TextComponent? = this.label,
    labelRotationDegrees: Float = this.labelRotationDegrees,
    horizontalLabelPosition: HorizontalLabelPosition = this.horizontalLabelPosition,
    verticalLabelPosition: Position.Vertical = this.verticalLabelPosition,
    valueFormatter: CartesianValueFormatter = this.valueFormatter,
    tick: LineComponent? = this.tick,
    tickLengthDp: Float = this.tickLengthDp,
    guideline: LineComponent? = this.guideline,
    itemPlacer: ItemPlacer = this.itemPlacer,
    size: Size = this.size,
    titleComponent: TextComponent? = this.titleComponent,
    title: CharSequence? = this.title,
    markerDecoration: MarkerDecoration? = this.markerDecoration,
  ): VerticalAxis<P> =
    VerticalAxis(
      position,
      line,
      label,
      labelRotationDegrees,
      horizontalLabelPosition,
      verticalLabelPosition,
      valueFormatter,
      tick,
      tickLengthDp,
      guideline,
      itemPlacer,
      size,
      titleComponent,
      title,
      markerDecoration,
    )

  override fun equals(other: Any?): Boolean =
    super.equals(other) &&
      other is VerticalAxis<*> &&
      horizontalLabelPosition == other.horizontalLabelPosition &&
      verticalLabelPosition == other.verticalLabelPosition &&
      itemPlacer == other.itemPlacer &&
      markerDecoration == other.markerDecoration

  override fun hashCode(): Int {
    var result = super.hashCode()
    result = 31 * result + horizontalLabelPosition.hashCode()
    result = 31 * result + verticalLabelPosition.hashCode()
    result = 31 * result + itemPlacer.hashCode()
    result = 31 * result + markerDecoration.hashCode()
    return result
  }

  /**
   * Defines the horizontal position of each of a vertical axis’s labels relative to the axis line.
   */
  public enum class HorizontalLabelPosition {
    Outside,
    Inside,
  }

  /** Determines for what _y_ values a [VerticalAxis] displays labels, ticks, and guidelines. */
  public interface ItemPlacer {
    /**
     * Returns a boolean indicating whether to shift the lines whose _y_ values are equal to
     * [CartesianChartRanges.YRange.maxY], if such lines are present, such that they’re immediately
     * above the [CartesianLayer] bounds. If a top [HorizontalAxis] is present, the shifted tick
     * will then be aligned with the axis line, and the shifted guideline will be hidden.
     */
    public fun getShiftTopLines(context: CartesianDrawingContext): Boolean = true

    /** Returns, as a list, the _y_ values for which labels are to be displayed. */
    public fun getLabelValues(
      context: CartesianDrawingContext,
      axisHeight: Float,
      maxLabelHeight: Float,
      position: Axis.Position.Vertical,
    ): List<Double>

    /**
     * Returns, as a list, the _y_ values for which the [VerticalAxis] is to create labels and
     * measure their widths during the measuring phase. This affects how much horizontal space the
     * [VerticalAxis] requests.
     */
    public fun getWidthMeasurementLabelValues(
      context: CartesianMeasuringContext,
      axisHeight: Float,
      maxLabelHeight: Float,
      position: Axis.Position.Vertical,
    ): List<Double>

    /**
     * Returns, as a list, the _y_ values for which the [VerticalAxis] is to create labels and
     * measure their heights during the measuring phase. The height of the tallest label is passed
     * to other functions.
     */
    public fun getHeightMeasurementLabelValues(
      context: CartesianMeasuringContext,
      position: Axis.Position.Vertical,
    ): List<Double>

    /** Returns, as a list, the _y_ values for which ticks and guidelines are to be displayed. */
    public fun getLineValues(
      context: CartesianDrawingContext,
      axisHeight: Float,
      maxLabelHeight: Float,
      position: Axis.Position.Vertical,
    ): List<Double>? = null

    /** Returns the top [CartesianLayer]-area margin required by the [VerticalAxis]. */
    public fun getTopLayerMargin(
      context: CartesianMeasuringContext,
      verticalLabelPosition: Position.Vertical,
      maxLabelHeight: Float,
      maxLineThickness: Float,
    ): Float

    /** Returns the bottom [CartesianLayer]-area margin required by the [VerticalAxis]. */
    public fun getBottomLayerMargin(
      context: CartesianMeasuringContext,
      verticalLabelPosition: Position.Vertical,
      maxLabelHeight: Float,
      maxLineThickness: Float,
    ): Float

    /** Houses [ItemPlacer] factory functions. */
    public companion object {
      /**
       * Creates a step-based [ItemPlacer] implementation. [step] returns the difference between the
       * _y_ values of neighboring labels (and their corresponding line pairs). A multiple of this
       * may be used for overlap prevention. If `null` is returned, the step will be determined
       * automatically. [shiftTopLines] is used as the return value of
       * [ItemPlacer.getShiftTopLines].
       */
      public fun step(
        step: (ExtraStore) -> Double? = { null },
        shiftTopLines: Boolean = true,
      ): ItemPlacer =
        DefaultVerticalAxisItemPlacer(DefaultVerticalAxisItemPlacer.Mode.Step(step), shiftTopLines)

      /**
       * Creates a count-based [ItemPlacer] implementation. [count] returns the number of labels
       * (and their corresponding line pairs) to be displayed. This may be reduced for overlap
       * prevention. If `null` is returned, the [VerticalAxis] will display as many items as
       * possible. [shiftTopLines] is used as the return value of [ItemPlacer.getShiftTopLines].
       */
      public fun count(
        count: (ExtraStore) -> Int? = { null },
        shiftTopLines: Boolean = true,
      ): ItemPlacer =
        DefaultVerticalAxisItemPlacer(
          DefaultVerticalAxisItemPlacer.Mode.Count(count),
          shiftTopLines,
        )
    }
  }

  /** Houses [VerticalAxis] factory functions. */
  public companion object {
    /** Creates a start [VerticalAxis]. */
    public fun start(
      line: LineComponent? = null,
      label: TextComponent? = null,
      labelRotationDegrees: Float = 0f,
      horizontalLabelPosition: HorizontalLabelPosition = Outside,
      verticalLabelPosition: Position.Vertical = Position.Vertical.Center,
      valueFormatter: CartesianValueFormatter = CartesianValueFormatter.decimal(),
      tick: LineComponent? = null,
      tickLengthDp: Float = 0f,
      guideline: LineComponent? = null,
      itemPlacer: ItemPlacer = ItemPlacer.step(),
      size: Size = Size.Auto(),
      titleComponent: TextComponent? = null,
      title: CharSequence? = null,
      markerDecoration: MarkerDecoration? = null,
    ): VerticalAxis<Axis.Position.Vertical.Start> =
      VerticalAxis(
        Axis.Position.Vertical.Start,
        line,
        label,
        labelRotationDegrees,
        horizontalLabelPosition,
        verticalLabelPosition,
        valueFormatter,
        tick,
        tickLengthDp,
        guideline,
        itemPlacer,
        size,
        titleComponent,
        title,
        markerDecoration,
      )

    /** Creates an end [VerticalAxis]. */
    public fun end(
      line: LineComponent? = null,
      label: TextComponent? = null,
      labelRotationDegrees: Float = 0f,
      horizontalLabelPosition: HorizontalLabelPosition = Outside,
      verticalLabelPosition: Position.Vertical = Position.Vertical.Center,
      valueFormatter: CartesianValueFormatter = CartesianValueFormatter.decimal(),
      tick: LineComponent? = null,
      tickLengthDp: Float = 0f,
      guideline: LineComponent? = null,
      itemPlacer: ItemPlacer = ItemPlacer.step(),
      size: Size = Size.Auto(),
      titleComponent: TextComponent? = null,
      title: CharSequence? = null,
      markerDecoration: MarkerDecoration? = null,
    ): VerticalAxis<Axis.Position.Vertical.End> =
      VerticalAxis(
        Axis.Position.Vertical.End,
        line,
        label,
        labelRotationDegrees,
        horizontalLabelPosition,
        verticalLabelPosition,
        valueFormatter,
        tick,
        tickLengthDp,
        guideline,
        itemPlacer,
        size,
        titleComponent,
        title,
        markerDecoration,
      )
  }

  /**
   * A marker decoration that can be positioned at specific Y values on the vertical axis.
   *
   * @property y returns the Y value where the marker should be positioned.
   * @property markerComponent the component to draw at the marker position.
   * @property labelComponent the label component for the marker.
   * @property label returns the label text for the marker.
   * @property horizontalLabelPosition defines the horizontal position of the marker label.
   * @property verticalLabelPosition defines the vertical position of the marker label.
   * @property labelRotationDegrees the rotation of the marker label (in degrees).
   * @property outsideRangeOffset the offset in pixels when the marker is positioned outside the visible range.
   */
  public data class MarkerDecoration(
    public val y: (ExtraStore) -> Double,
    public val markerComponent: TextComponent? = null,
    public val labelComponent: TextComponent? = null,
    public val label: (ExtraStore) -> CharSequence = { getMarkerLabel(y(it)) },
    public val horizontalLabelPosition: HorizontalLabelPosition = HorizontalLabelPosition.Outside,
    public val verticalLabelPosition: Position.Vertical = Position.Vertical.Center,
    public val labelRotationDegrees: Float = 0f,
    public val outsideRangeOffset: Float = 60f,
  ) {
    /** @suppress */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public companion object {
      private val decimalFormat: DecimalFormat = DecimalFormat("#.##;−#.##")

      public fun getMarkerLabel(y: Double): String = decimalFormat.format(y)
    }
  }
}
