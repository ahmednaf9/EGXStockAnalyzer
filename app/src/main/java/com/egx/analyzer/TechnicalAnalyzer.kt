package com.egx.analyzer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class ScorePart(val name: String, val points: Int, val maxPoints: Int)

data class Analysis(
    val last: Candle,
    val supports: List<Double>,
    val resistances: List<Double>,
    val rsi: Double,
    val sma20: Double?,
    val sma50: Double?,
    val macd: Double?,
    val macdSignal: Double?,
    val atr: Double,
    val bbLower: Double,
    val bbUpper: Double,
    val trend: String,
    val patterns: List<String>,
    val targets: List<Double>,
    val invalidation: Double,
    val score: Int,
    val scoreLabel: String,
    val volumeRatio: Double,
    val riskReward: Double?,
    val scoreParts: List<ScorePart>
)

object TechnicalAnalyzer {
    fun analyze(input: List<Candle>): Analysis {
        require(input.size >= 26) { "At least 26 valid sessions are required." }
        val rows = input.sortedBy { it.date }
        val closes = rows.map { it.close }
        val last = rows.last()
        val previous = rows[rows.lastIndex - 1]

        fun sma(values: List<Double>, period: Int): Double? =
            if (values.size >= period) values.takeLast(period).average() else null

        fun emaSeries(values: List<Double>, period: Int): List<Double> {
            if (values.isEmpty()) return emptyList()
            val k = 2.0 / (period + 1.0)
            val result = MutableList(values.size) { values.first() }
            var current = values.first()
            for (i in 1 until values.size) {
                current = values[i] * k + current * (1.0 - k)
                result[i] = current
            }
            return result
        }

        val changes = closes.zipWithNext { a, b -> b - a }
        val gains = changes.map { max(it, 0.0) }
        val losses = changes.map { max(-it, 0.0) }
        var avgGain = gains.take(14).average()
        var avgLoss = losses.take(14).average()
        for (i in 14 until gains.size) {
            avgGain = (avgGain * 13.0 + gains[i]) / 14.0
            avgLoss = (avgLoss * 13.0 + losses[i]) / 14.0
        }
        val rsi = when {
            avgLoss == 0.0 && avgGain == 0.0 -> 50.0
            avgLoss == 0.0 -> 100.0
            else -> 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
        }

        val ema12 = emaSeries(closes, 12)
        val ema26 = emaSeries(closes, 26)
        val macdLine = ema12.indices.map { ema12[it] - ema26[it] }
        val signalLine = emaSeries(macdLine, 9)
        val macd = macdLine.last()
        val macdSignal = signalLine.last()
        val macdHistogram = macd - macdSignal

        val trueRanges = rows.indices.map { i ->
            val c = rows[i]
            if (i == 0) c.high - c.low else {
                val prevClose = rows[i - 1].close
                max(c.high - c.low, max(abs(c.high - prevClose), abs(c.low - prevClose)))
            }
        }
        val atr = trueRanges.takeLast(14).average().coerceAtLeast(0.000001)
        val s20 = sma(closes, 20)
        val s50 = sma(closes, 50)
        val bbMean = s20 ?: closes.average()
        val last20 = closes.takeLast(20)
        val variance = last20.map { (it - bbMean) * (it - bbMean) }.average()
        val deviation = sqrt(variance)
        val bbUpper = bbMean + 2.0 * deviation
        val bbLower = (bbMean - 2.0 * deviation).coerceAtLeast(0.0)

        val trend = when {
            s20 != null && s50 != null && last.close > s20 && s20 > s50 -> "صاعد"
            s20 != null && s50 != null && last.close < s20 && s20 < s50 -> "هابط"
            s20 != null && last.close > s20 && macdHistogram > 0.0 -> "تحسن صاعد"
            s20 != null && last.close < s20 && macdHistogram < 0.0 -> "ضغط هابط"
            else -> "عرضي / غير مؤكد"
        }

        val recent = rows.takeLast(120)
        val pivotLows = mutableListOf<Double>()
        val pivotHighs = mutableListOf<Double>()
        for (i in 2 until recent.size - 2) {
            val low = recent[i].low
            val high = recent[i].high
            if ((i - 2 until i).all { recent[it].low >= low } &&
                (i + 1..i + 2).all { recent[it].low >= low }) pivotLows.add(low)
            if ((i - 2 until i).all { recent[it].high <= high } &&
                (i + 1..i + 2).all { recent[it].high <= high }) pivotHighs.add(high)
        }
        val supports = clusterLevels(pivotLows.filter { it < last.close }, descending = true)
            .take(3)
        val resistances = clusterLevels(pivotHighs.filter { it > last.close }, descending = false)
            .take(3)

        val patterns = detectPatterns(rows)
        val priorVolumes = rows.dropLast(1).takeLast(20).map { it.volume }.filter { it > 0.0 }
        val avgVolume = priorVolumes.average()
        val volumeRatio = if (avgVolume > 0.0 && last.volume > 0.0) last.volume / avgVolume else 0.0

        val nearestSupport = supports.firstOrNull()
        val nearestResistance = resistances.firstOrNull()
        val stop = (nearestSupport ?: (last.close - atr * 1.5)).coerceAtLeast(0.0)
        val shortTarget = nearestResistance ?: (last.close + atr * 1.5)
        val mediumTarget = max(shortTarget, last.close + atr * 3.0)
        val longTarget = max(mediumTarget, last.close + atr * 5.0)
        val reward = shortTarget - last.close
        val risk = last.close - stop
        val rr = if (reward > 0.0 && risk > 0.0) reward / risk else null

        val trendPoints = when (trend) {
            "صاعد" -> 20
            "تحسن صاعد" -> 15
            "عرضي / غير مؤكد" -> 10
            else -> 3
        }
        val momentumPoints = when {
            rsi in 52.0..68.0 && macdHistogram > 0.0 -> 15
            rsi in 45.0..70.0 && macdHistogram >= 0.0 -> 11
            rsi in 35.0..50.0 -> 7
            rsi > 75.0 -> 3
            else -> 5
        }
        val volumePoints = when {
            volumeRatio >= 1.8 -> 15
            volumeRatio >= 1.3 -> 12
            volumeRatio >= 0.8 -> 8
            volumeRatio > 0.0 -> 4
            else -> 7
        }
        val structurePoints = when {
            nearestSupport != null && (last.close - nearestSupport) / last.close <= 0.025 && trend != "ضغط هابط" -> 15
            nearestResistance != null && (nearestResistance - last.close) / last.close <= 0.02 -> 4
            last.close > bbMean -> 11
            else -> 7
        }
        val patternPoints = when {
            patterns.any { it.startsWith("إيجابي:") } -> 15
            patterns.any { it.startsWith("سلبي:") } -> 3
            else -> 8
        }
        val riskPoints = when {
            rr == null -> 5
            rr >= 3.0 -> 20
            rr >= 2.0 -> 16
            rr >= 1.5 -> 12
            rr >= 1.0 -> 8
            else -> 3
        }
        val parts = listOf(
            ScorePart("الاتجاه", trendPoints, 20),
            ScorePart("الزخم", momentumPoints, 15),
            ScorePart("السيولة", volumePoints, 15),
            ScorePart("مناطق السعر", structurePoints, 15),
            ScorePart("النماذج", patternPoints, 15),
            ScorePart("العائد إلى المخاطرة", riskPoints, 20)
        )
        val score = parts.sumOf { it.points }.coerceIn(0, 100)
        val label = when {
            score >= 80 -> "إيجابي فنيًا"
            score >= 65 -> "يميل للإيجابية"
            score >= 45 -> "محايد / مراقبة"
            else -> "ضعيف فنيًا"
        }
        return Analysis(
            last, supports, resistances, rsi, s20, s50, macd, macdSignal,
            atr, bbLower, bbUpper, trend, patterns,
            listOf(shortTarget, mediumTarget, longTarget), stop, score, label,
            volumeRatio, rr, parts
        )
    }

    private fun clusterLevels(values: List<Double>, descending: Boolean): List<Double> {
        val sorted = if (descending) values.sortedDescending() else values.sorted()
        val groups = mutableListOf<MutableList<Double>>()
        for (value in sorted) {
            val group = groups.lastOrNull()
            val center = group?.average() ?: value
            if (group == null || abs(value - center) / center.coerceAtLeast(0.000001) > 0.006) {
                groups.add(mutableListOf(value))
            } else group.add(value)
        }
        return groups.map { it.average() }
    }

    private fun detectPatterns(rows: List<Candle>): List<String> {
        val c = rows.last()
        val p = rows[rows.lastIndex - 1]
        val range = (c.high - c.low).coerceAtLeast(0.000001)
        val body = abs(c.close - c.open)
        val upperShadow = c.high - maxOf(c.open, c.close)
        val lowerShadow = minOf(c.open, c.close) - c.low
        val result = mutableListOf<String>()
        if (body / range <= 0.10) result.add("محايد: دوجي — تردد في الجلسة")
        if (lowerShadow >= body * 2.0 && upperShadow <= body * 0.8 && c.close > c.open)
            result.add("إيجابي: مطرقة محتملة — تحتاج تأكيدًا")
        if (upperShadow >= body * 2.0 && lowerShadow <= body * 0.8 && c.close < c.open)
            result.add("سلبي: شهاب محتمل — يحتاج تأكيدًا")
        if (c.close > c.open && p.close < p.open && c.close >= p.open && c.open <= p.close)
            result.add("إيجابي: ابتلاع شرائي محتمل")
        if (c.close < c.open && p.close > p.open && c.open >= p.close && c.close <= p.open)
            result.add("سلبي: ابتلاع بيعي محتمل")
        if (result.isEmpty()) result.add("لم يتم رصد نموذج شموع أساسي واضح في آخر جلسة")
        return result
    }
}
