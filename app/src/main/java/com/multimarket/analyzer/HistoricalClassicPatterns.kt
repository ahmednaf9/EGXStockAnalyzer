package com.multimarket.analyzer

import kotlin.math.abs

data class HistoricalClassicPattern(
    val name: String,
    val bias: String,
    val detectionIndex: Int,
    val detectionDate: String,
    val trigger: Double,
    val targetSuccess: Double,
    val targetFailure: Double,
    val status: String,
    val outcomeDate: String?
)

/**
 * Replays the existing pattern scanner chronologically. Each scan sees only
 * candles available on that date; later candles are used solely to grade the
 * already-recorded signal.
 */
object HistoricalClassicPatternScanner {
    private const val MAX_SCAN_WINDOW = 180
    private const val DUPLICATE_COOLDOWN = 12

    fun scan(input: List<Candle>): List<HistoricalClassicPattern> {
        val data = input.filter {
            it.date.isNotBlank() && it.open.isFinite() && it.high.isFinite() &&
                it.low.isFinite() && it.close.isFinite() && it.volume.isFinite() &&
                it.open > 0.0 && it.high > 0.0 && it.low > 0.0 && it.close > 0.0 &&
                it.volume >= 0.0 && it.high >= maxOf(it.open, it.close, it.low) &&
                it.low <= minOf(it.open, it.close)
        }
        if (data.size < 40) return emptyList()

        val detections = mutableListOf<HistoricalClassicPattern>()
        val recentByName = mutableMapOf<String, Pair<Int, Double>>()
        for (end in 39 until data.size) {
            val from = (end - MAX_SCAN_WINDOW + 1).coerceAtLeast(0)
            val window = data.subList(from, end + 1)
            val candidates = ClassicPatternScanner.scan(window)

            candidates.asSequence()
                .filter { it.bias == "صاعد" || it.bias == "هابط" }
                .forEach { candidate ->
                    val previous = recentByName[candidate.name]
                    val duplicate = previous != null &&
                        end - previous.first <= DUPLICATE_COOLDOWN &&
                        relativeDistance(previous.second, candidate.trigger) <= 0.006
                    recentByName[candidate.name] = end to candidate.trigger
                    if (!duplicate) {
                        detections += HistoricalClassicPattern(
                            name = candidate.name,
                            bias = candidate.bias,
                            detectionIndex = end,
                            detectionDate = data[end].date,
                            trigger = candidate.trigger,
                            targetSuccess = candidate.targetSuccess,
                            targetFailure = candidate.targetFailure,
                            status = "لم يتأكد بعد",
                            outcomeDate = null
                        )
                    }
                }
        }

        return detections.map { signal ->
            grade(signal, data)
        }
    }

    private fun grade(
        signal: HistoricalClassicPattern,
        data: List<Candle>
    ): HistoricalClassicPattern {
        var activated = if (signal.bias == "صاعد") {
            data[signal.detectionIndex].close > signal.trigger
        } else {
            data[signal.detectionIndex].close < signal.trigger
        }

        for (i in signal.detectionIndex + 1 until data.size) {
            val candle = data[i]
            val failed = if (signal.bias == "صاعد") {
                candle.low <= signal.targetFailure
            } else {
                candle.high >= signal.targetFailure
            }
            // Invalidation can fail a setup even before its breakout is confirmed.
            if (failed) return signal.copy(status = "فشل", outcomeDate = candle.date)

            if (!activated) {
                activated = if (signal.bias == "صاعد") {
                    candle.close > signal.trigger
                } else {
                    candle.close < signal.trigger
                }
                if (!activated) continue
            }

            val succeeded = if (signal.bias == "صاعد") {
                candle.high >= signal.targetSuccess
            } else {
                candle.low <= signal.targetSuccess
            }
            if (succeeded) return signal.copy(status = "نجح", outcomeDate = candle.date)
        }

        return signal.copy(status = "لم يتأكد بعد", outcomeDate = null)
    }

    private fun relativeDistance(a: Double, b: Double): Double {
        return abs(a - b) / maxOf(abs(a), abs(b), 0.0000001)
    }
}
