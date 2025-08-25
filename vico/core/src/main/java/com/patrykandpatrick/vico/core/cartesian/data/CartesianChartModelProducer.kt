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

package com.patrykandpatrick.vico.core.cartesian.data

import androidx.annotation.RestrictTo
import androidx.compose.runtime.saveable.Saver
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.data.MutableExtraStore
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Creates [CartesianChartModel]s and handles difference animations. */
public class CartesianChartModelProducer {
  private var lastPartials = emptyList<CartesianLayerModel.Partial>()
  private var lastTransactionExtraStore = MutableExtraStore()
  private var cachedModel: CartesianChartModel? = null
  private var cachedModelPartialHashCode: Int? = null
  private val mutex = Mutex()
  private val updateReceivers = ConcurrentHashMap<Any, UpdateReceiver>()

  private suspend fun update(
    partials: List<CartesianLayerModel.Partial>,
    transactionExtraStore: MutableExtraStore,
  ) {
    coroutineScope {
      mutex.withLock {
        val immutablePartials = partials.toList()
        if (
          immutablePartials == this@CartesianChartModelProducer.lastPartials &&
            transactionExtraStore == this@CartesianChartModelProducer.lastTransactionExtraStore
        ) {
          return@coroutineScope
        }
        updateReceivers.values
          .map { launch { it.handleUpdate(immutablePartials, transactionExtraStore) } }
          .joinAll()
        lastPartials = immutablePartials
        lastTransactionExtraStore = transactionExtraStore
      }
    }
  }

  private fun getModel(partials: List<CartesianLayerModel.Partial>, extraStore: ExtraStore) =
    if (partials.hashCode() == cachedModelPartialHashCode) {
      cachedModel?.copy(extraStore)
    } else {
      if (partials.isNotEmpty()) {
          CartesianChartModel(partials.map { it.complete(extraStore) }, extraStore)
        } else {
          null
        }
        .also { model ->
          cachedModel = model
          cachedModelPartialHashCode = partials.hashCode()
        }
    }

  private suspend fun transform(
    key: Any,
    fraction: Float,
    model: CartesianChartModel?,
    ranges: CartesianChartRanges,
  ) {
    with(updateReceivers[key] ?: return) {
      withContext(getDispatcher()) {
        transform(hostExtraStore, fraction)
        currentCoroutineContext().ensureActive()
        onUpdate(model, ranges, hostExtraStore.copy())
      }
    }
  }

  /** @suppress */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  public suspend fun registerForUpdates(
    key: Any,
    cancelAnimation: suspend () -> Unit,
    startAnimation: (transformModel: suspend (key: Any, fraction: Float) -> Unit) -> Unit,
    prepareForTransformation:
      (CartesianChartModel?, MutableExtraStore, CartesianChartRanges) -> Unit,
    transform: suspend (MutableExtraStore, Float) -> Unit,
    hostExtraStore: MutableExtraStore,
    updateRanges: (CartesianChartModel?) -> CartesianChartRanges,
    onUpdate: (CartesianChartModel?, CartesianChartRanges, ExtraStore) -> Unit,
  ) {
    withContext(getDispatcher()) {
      val receiver =
        UpdateReceiver(
          cancelAnimation,
          startAnimation,
          onUpdate,
          hostExtraStore,
          prepareForTransformation,
          transform,
          updateRanges,
        )
      mutex.withLock {
        updateReceivers[key] = receiver
        receiver.handleUpdate(lastPartials, lastTransactionExtraStore)
      }
    }
  }

  /** @suppress */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  public fun isRegistered(key: Any): Boolean = updateReceivers.containsKey(key)

  /** @suppress */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  public fun unregisterFromUpdates(key: Any) {
    updateReceivers.remove(key)
  }

  /**
   * (1) Creates a [Transaction], (2) invokes [block], and (3) runs a data update, returning once
   * the update is complete. Between steps 2 and 3, if there’s already an update in progress, the
   * current coroutine is suspended until the ongoing update’s completion.
   */
  public suspend fun runTransaction(block: Transaction.() -> Unit) {
    withContext(Dispatchers.Default) { Transaction().also(block).commit() }
  }

  /** Handles data updates. This is used via [runTransaction]. */
  public inner class Transaction internal constructor() {
    private val newPartials = mutableListOf<CartesianLayerModel.Partial>()
    private val newExtraStore = MutableExtraStore()

    /** Adds a [CartesianLayerModel.Partial]. */
    public fun add(partial: CartesianLayerModel.Partial) {
      newPartials.add(partial)
    }

    /**
     * Allows for adding auxiliary values, which can later be retrieved via
     * [CartesianChartModel.extraStore].
     */
    public fun extras(block: (MutableExtraStore) -> Unit) {
      block(newExtraStore)
    }

    internal suspend fun commit() {
      update(newPartials, newExtraStore)
    }
  }

  private inner class UpdateReceiver(
    val cancelAnimation: suspend () -> Unit,
    val startAnimation: (transformModel: suspend (key: Any, fraction: Float) -> Unit) -> Unit,
    val onUpdate: (CartesianChartModel?, CartesianChartRanges, ExtraStore) -> Unit,
    val hostExtraStore: MutableExtraStore,
    val prepareForTransformation:
      (CartesianChartModel?, MutableExtraStore, CartesianChartRanges) -> Unit,
    val transform: suspend (MutableExtraStore, Float) -> Unit,
    val updateRanges: (CartesianChartModel?) -> CartesianChartRanges,
  ) {
    suspend fun handleUpdate(
      partials: List<CartesianLayerModel.Partial>,
      transactionExtraStore: ExtraStore,
    ) {
      cancelAnimation()
      val model = getModel(partials, transactionExtraStore)
      val ranges = updateRanges(model)
      prepareForTransformation(model, hostExtraStore, ranges)
      startAnimation { key, fraction -> transform(key, fraction, model, ranges) }
    }
  }

  private suspend fun getDispatcher(): CoroutineDispatcher {
    val context = currentCoroutineContext()
    return if (context[PreviewContextKey] != null) Dispatchers.Unconfined else Dispatchers.Default
  }

    public companion object {
    /**
     * Creates a [Saver] for [CartesianChartModelProducer] to be used with `rememberSaveable`.
     *
     * This saver preserves the producer instance and its current state across recompositions
     * and configuration changes. It now saves the actual partials and extra store data,
     * allowing the producer to be fully restored without requiring runTransaction.
     *
     * @return A [Saver] instance for [CartesianChartModelProducer]
     */
    public fun Saver(): Saver<CartesianChartModelProducer, SavedState> =
      Saver(
        save = { producer ->
          SavedState(
            serializedPartials = producer.lastPartials.map { SerializablePartial.from(it) },
            serializedExtraStore = SerializableExtraStore.from(producer.lastTransactionExtraStore),
            hasData = producer.lastPartials.isNotEmpty()
          )
        },
        restore = { savedState ->
          CartesianChartModelProducer().apply {
            if (savedState.hasData && savedState.serializedPartials.isNotEmpty()) {
              // Restore the data directly without requiring runTransaction
              val restoredPartials = savedState.serializedPartials.mapNotNull { it.toPartial() }
              val restoredExtraStore = savedState.serializedExtraStore.toMutableExtraStore()

              // Update internal state directly
              lastPartials = restoredPartials
              lastTransactionExtraStore = restoredExtraStore

              // Clear cached model to force regeneration with restored data
              cachedModel = null
              cachedModelPartialHashCode = null
            }
          }
        }
      )

    /**
     * Serializable wrapper for CartesianLayerModel.Partial implementations
     */
    public sealed class SerializablePartial : Serializable {
      public abstract fun toPartial(): CartesianLayerModel.Partial?

      public companion object {
        public fun from(partial: CartesianLayerModel.Partial): SerializablePartial {
          return when (partial::class.java.name) {
            "com.patrykandpatrick.vico.core.cartesian.data.LineCartesianLayerModel\$Partial" -> {
              SerializableLinePartial.from(partial)
            }
            "com.patrykandpatrick.vico.core.cartesian.data.ColumnCartesianLayerModel\$Partial" -> {
              SerializableColumnPartial.from(partial)
            }
            "com.patrykandpatrick.vico.core.cartesian.data.CandlestickCartesianLayerModel\$Partial" -> {
              SerializableCandlestickPartial.from(partial)
            }
            else -> SerializableGenericPartial(partial::class.java.name)
          }
        }
      }
    }

    /**
     * Serializable version of LineCartesianLayerModel.Partial
     */
    public data class SerializableLinePartial(
      private val serializedSeries: List<List<SerializableEntry>>
    ) : SerializablePartial() {

      override fun toPartial(): CartesianLayerModel.Partial? {
        return try {
          val series = serializedSeries.map { seriesList ->
            seriesList.map { entry ->
              LineCartesianLayerModel.Entry(entry.x, entry.y)
            }
          }
          LineCartesianLayerModel.Partial(series)
        } catch (e: Exception) {
          null
        }
      }

      public companion object {
        public fun from(partial: CartesianLayerModel.Partial): SerializableLinePartial {
          val linePartial = partial as LineCartesianLayerModel.Partial
          val seriesField = linePartial::class.java.getDeclaredField("series")
          seriesField.isAccessible = true
          @Suppress("UNCHECKED_CAST")
          val series = seriesField.get(linePartial) as List<List<LineCartesianLayerModel.Entry>>

          val serializedSeries = series.map { seriesList ->
            seriesList.map { entry -> SerializableEntry(entry.x, entry.y) }
          }
          return SerializableLinePartial(serializedSeries)
        }
      }
    }

    /**
     * Serializable version of ColumnCartesianLayerModel.Partial
     */
    public data class SerializableColumnPartial(
      private val serializedSeries: List<List<SerializableEntry>>
    ) : SerializablePartial() {

      override fun toPartial(): CartesianLayerModel.Partial? {
        return try {
          val series = serializedSeries.map { seriesList ->
            seriesList.map { entry ->
              ColumnCartesianLayerModel.Entry(entry.x, entry.y)
            }
          }
          ColumnCartesianLayerModel.Partial(series)
        } catch (e: Exception) {
          null
        }
      }

      public companion object {
        public fun from(partial: CartesianLayerModel.Partial): SerializableColumnPartial {
          val columnPartial = partial as ColumnCartesianLayerModel.Partial
          val seriesField = columnPartial::class.java.getDeclaredField("series")
          seriesField.isAccessible = true
          @Suppress("UNCHECKED_CAST")
          val series = seriesField.get(columnPartial) as List<List<ColumnCartesianLayerModel.Entry>>

          val serializedSeries = series.map { seriesList ->
            seriesList.map { entry -> SerializableEntry(entry.x, entry.y) }
          }
          return SerializableColumnPartial(serializedSeries)
        }
      }
    }

    /**
     * Serializable version of CandlestickCartesianLayerModel.Partial
     */
    public data class SerializableCandlestickPartial(
      private val serializedEntries: List<SerializableCandlestickEntry>
    ) : SerializablePartial() {

      override fun toPartial(): CartesianLayerModel.Partial? {
        return try {
          val entries = serializedEntries.map { entry ->
            CandlestickCartesianLayerModel.Entry(
              x = entry.x,
              opening = entry.opening,
              closing = entry.closing,
              low = entry.low,
              high = entry.high,
                              absoluteChange = entry.absoluteChange,
                relativeChange = entry.relativeChange
            )
          }
          CandlestickCartesianLayerModel.Partial(entries)
        } catch (e: Exception) {
          null
        }
      }

      public companion object {
        public fun from(partial: CartesianLayerModel.Partial): SerializableCandlestickPartial {
          val candlestickPartial = partial as CandlestickCartesianLayerModel.Partial
          val seriesField = candlestickPartial::class.java.getDeclaredField("series")
          seriesField.isAccessible = true
          @Suppress("UNCHECKED_CAST")
          val entries = seriesField.get(candlestickPartial) as List<CandlestickCartesianLayerModel.Entry>

          val serializedEntries = entries.map { entry ->
            SerializableCandlestickEntry(
              x = entry.x,
              opening = entry.opening,
              closing = entry.closing,
              low = entry.low,
              high = entry.high,
                              absoluteChange = entry.absoluteChange,
                relativeChange = entry.relativeChange
            )
          }
          return SerializableCandlestickPartial(serializedEntries)
        }
      }
    }

    /**
     * Fallback for unknown partial types
     */
    public data class SerializableGenericPartial(
      private val className: String
    ) : SerializablePartial() {
      override fun toPartial(): CartesianLayerModel.Partial? = null
    }

    /**
     * Serializable version of basic chart entries
     */
    public data class SerializableEntry(
      val x: Double,
      val y: Double
    ) : Serializable

    /**
     * Serializable version of candlestick entries
     */
    public data class SerializableCandlestickEntry(
      val x: Double,
      val opening: Double,
      val closing: Double,
      val low: Double,
      val high: Double,
                    val absoluteChange: CandlestickCartesianLayerModel.Change,
      val relativeChange: CandlestickCartesianLayerModel.Change
    ) : Serializable

    /**
     * Serializable version of ExtraStore
     */
    public data class SerializableExtraStore(
      private val data: Map<String, Any>
    ) : Serializable {

      public fun toMutableExtraStore(): MutableExtraStore {
        val store = MutableExtraStore()
        // Note: We can only restore basic serializable data from ExtraStore
        // Complex objects that aren't serializable will be lost
        return store
      }

      public companion object {
        public fun from(extraStore: ExtraStore): SerializableExtraStore {
          // For now, we'll create an empty serializable store since ExtraStore
          // may contain complex non-serializable objects
          return SerializableExtraStore(emptyMap())
        }
      }
    }

    /**
     * Internal data class to hold saveable state
     */
    public data class SavedState(
      val serializedPartials: List<SerializablePartial>,
      val serializedExtraStore: SerializableExtraStore,
      val hasData: Boolean
    ) : Serializable
  }
}

internal object PreviewContextKey : CoroutineContext.Key<PreviewContext>

/** @suppress */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object PreviewContext : AbstractCoroutineContextElement(PreviewContextKey)
