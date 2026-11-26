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
        val data = input.filter { it.isValidForAnalysis() }
        if (data.size < 40) return emptyList()

        val detections = mutableListOf<HistoricalClassicPattern>()
        for (end in 39 until data.size) {
            val from = (end - MAX_SCAN_WINDOW + 1).coerceAtLeast(0)
            val window = data.subList(from, end + 1)
            val candidates = ClassicPatternScanner.scan(window)

            candidates.asSequence()
                .filter { it.bias == "صاعد" || it.bias == "هابط" }
                .forEach { candidate ->
                    val duplicate = detections.lastOrNull { previous ->
                        previous.name == candidate.name &&
                            end - previous.detectionIndex <= DUPLICATE_COOLDOWN &&
                            relativeDistance(previous.trigger, candidate.trigger) <= 0.006
                    }
                    if (duplicate == null) {
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
            if (!activated) {
                activated = if (signal.bias == "صاعد") {
                    candle.close > signal.trigger
                } else {
                    candle.close < signal.trigger
                }
                if (!activated) continue
            }

            val failed = if (signal.bias == "صاعد") {
                candle.low <= signal.targetFailure
            } else {
                candle.high >= signal.targetFailure
            }
            val succeeded = if (signal.bias == "صاعد") {
                candle.high >= signal.targetSuccess
            } else {
                candle.low <= signal.targetSuccess
            }

            // If both levels fall inside one candle and their order is unknown,
            // use the conservative classification: failure takes precedence.
            if (failed) return signal.copy(status = "فشل", outcomeDate = candle.date)
            if (succeeded) return signal.copy(status = "نجح", outcomeDate = candle.date)
        }

        return signal.copy(status = "لم يتأكد بعد", outcomeDate = null)
    }

    private fun relativeDistance(a: Double, b: Double): Double {
        return abs(a - b) / maxOf(abs(a), abs(b), 0.0000001)
    }
}
