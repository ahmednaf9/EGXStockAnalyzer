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
    val scoreParts: List<ScorePart>,
    val sessions: Int,
    val volumeAvailable: Boolean,
    val marketState: String,
    val marketStateReason: String,
    val finalRating: String,
    val finalRatingScore: Int,
    val finalRatingReason: String,
    val finalRatingFactors: List<String>
)

object TechnicalAnalyzer {

    private fun dateSortKey(value: String): String {
        val s = value.trim()
        val iso = Regex("^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})").find(s)
        if (iso != null) return "${iso.groupValues[1].padStart(4, '0')}-${iso.groupValues[2].padStart(2, '0')}-${iso.groupValues[3].padStart(2, '0')}"
        val dmy = Regex("^(\\d{1,2})[-/](\\d{1,2})[-/](\\d{4})").find(s)
        if (dmy != null) return "${dmy.groupValues[3]}-${dmy.groupValues[2].padStart(2, '0')}-${dmy.groupValues[1].padStart(2, '0')}"
        return s
    }
    fun analyze(input: List<Candle>): Analysis {
        require(input.size >= 12) { "At least 12 valid candles are required for technical analysis." }
        // Use the same chronological normalization as CSV import. Raw string sorting
        // misorders dates such as 16/11/2026 and can make the summary use an older close.
        val rows = input.sortedBy { dateSortKey(it.date) }
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
            else -> 8
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
            rr == null -> 8
            rr >= 3.0 -> 20
            rr >= 2.0 -> 16
            rr >= 1.5 -> 12
            rr >= 1.0 -> 8
            else -> 3
        }
        // Estimate accumulation/distribution from the last 20 sessions using
        // volume-weighted close location within each candle's range.
        val flowRows = rows.takeLast(20)
        val flowRowsWithVolume = flowRows.filter { it.volume > 0.0 }
        val signedFlow = flowRowsWithVolume.sumOf { c ->
            val range = (c.high - c.low).coerceAtLeast(0.000001)
            ((2.0 * c.close - c.high - c.low) / range) * c.volume
        }
        val totalFlowVolume = flowRowsWithVolume.sumOf { it.volume }
        val flowRatio = if (totalFlowVolume > 0.0) signedFlow / totalFlowVolume else 0.0
        val priceChange20 = if (flowRows.size >= 2 && flowRows.first().close > 0.0)
            (last.close - flowRows.first().close) / flowRows.first().close else 0.0
        val marketState = when {
            flowRowsWithVolume.size < 10 -> "غير محسوم"
            flowRatio >= 0.12 && priceChange20 >= -0.03 -> "تجميع محتمل"
            flowRatio <= -0.12 && priceChange20 <= 0.03 -> "تصريف محتمل"
            else -> "تذبذب / حياد"
        }
        val marketStateReason = when (marketState) {
            "تجميع محتمل" -> "ضغط السيولة يميل للشراء خلال الجلسات الأخيرة مع عدم وجود هبوط سعري حاد."
            "تصريف محتمل" -> "ضغط السيولة يميل للبيع خلال الجلسات الأخيرة؛ راقب كسر الدعوم."
            "غير محسوم" -> "بيانات الحجم غير كافية لتقدير التجميع أو التصريف بثقة."
            else -> "تدفق السيولة وحركة السعر لا يعطيان إشارة حاسمة للتجميع أو التصريف."
        }

        // Independent multi-factor rating: each available indicator contributes
        // a weighted bullish, neutral, or bearish signal. Missing data is excluded.
        var ratingNet = 0.0
        var ratingWeight = 0.0
        val ratingFactors = mutableListOf<String>()
        fun addRatingSignal(name: String, signal: Double, weight: Double, explanation: String) {
            ratingNet += signal * weight
            ratingWeight += weight
            ratingFactors.add("$name: $explanation")
        }

        val rsiSignal = when {
            rsi in 55.0..68.0 -> 1.0
            rsi > 68.0 && rsi <= 75.0 -> 0.35
            rsi > 75.0 -> -1.0
            rsi in 32.0..45.0 -> -1.0
            rsi < 32.0 -> -0.5
            else -> 0.0
        }
        val rsiText = when {
            rsi in 55.0..68.0 -> "زخم إيجابي"
            rsi > 68.0 && rsi <= 75.0 -> "إيجابي مع اقتراب التشبع الشرائي"
            rsi > 75.0 -> "تشبع شرائي مرتفع ومخاطر تصحيح"
            rsi in 32.0..45.0 -> "زخم سلبي"
            rsi < 32.0 -> "ضعف مع احتمال تشبع بيعي"
            else -> "محايد"
        }
        addRatingSignal("RSI 14", rsiSignal, 1.0, "$rsiText ($rsi)")

        val macdSignalStrength = when {
            macd > macdSignal && macd > 0.0 -> 1.0
            macd > macdSignal -> 0.5
            macd < macdSignal && macd < 0.0 -> -1.0
            macd < macdSignal -> -0.5
            else -> 0.0
        }
        addRatingSignal(
            "MACD",
            macdSignalStrength,
            2.0,
            when {
                macdSignalStrength >= 1.0 -> "إيجابي وفوق خط الإشارة والصفر"
                macdSignalStrength > 0.0 -> "تقاطع إيجابي لكن دون الصفر"
                macdSignalStrength <= -1.0 -> "سلبي وتحت خط الإشارة والصفر"
                macdSignalStrength < 0.0 -> "أضعف من خط الإشارة رغم بقائه فوق الصفر"
                else -> "متوافق مع خط الإشارة"
            }
        )

        s20?.let { average ->
            val signal = when {
                last.close > average * 1.003 -> 1.0
                last.close < average * 0.997 -> -1.0
                else -> 0.0
            }
            addRatingSignal("SMA 20", signal, 1.0, when {
                signal > 0.0 -> "الإغلاق أعلى المتوسط"
                signal < 0.0 -> "الإغلاق أسفل المتوسط"
                else -> "الإغلاق قريب من المتوسط"
            })
        }
        s50?.let { average ->
            val signal = when {
                last.close > average * 1.003 -> 1.0
                last.close < average * 0.997 -> -1.0
                else -> 0.0
            }
            addRatingSignal("SMA 50", signal, 1.0, when {
                signal > 0.0 -> "الإغلاق أعلى المتوسط"
                signal < 0.0 -> "الإغلاق أسفل المتوسط"
                else -> "الإغلاق قريب من المتوسط"
            })
        }
        if (s20 != null && s50 != null) {
            val signal = when {
                s20 > s50 * 1.002 -> 1.0
                s20 < s50 * 0.998 -> -1.0
                else -> 0.0
            }
            addRatingSignal("ترتيب المتوسطات", signal, 1.0, when {
                signal > 0.0 -> "SMA 20 أعلى SMA 50"
                signal < 0.0 -> "SMA 20 أسفل SMA 50"
                else -> "المتوسطان متقاربان"
            })
        }

        if (avgVolume > 0.0 && last.volume > 0.0) {
            val volumeSignal = when {
                volumeRatio >= 1.3 && last.close > previous.close -> 1.0
                volumeRatio >= 1.3 && last.close < previous.close -> -1.0
                else -> 0.0
            }
            addRatingSignal("حجم التداول", volumeSignal, 1.0, when {
                volumeSignal > 0.0 -> "ارتفاع الحجم مع صعود السعر"
                volumeSignal < 0.0 -> "ارتفاع الحجم مع هبوط السعر"
                else -> "لا يوجد تأكيد واضح من الحجم"
            })
        }

        if (nearestSupport != null || nearestResistance != null) {
            val supportDistance = nearestSupport?.let { (last.close - it) / last.close }
            val resistanceDistance = nearestResistance?.let { (it - last.close) / last.close }
            val structureSignal = when {
                resistanceDistance != null && resistanceDistance in 0.0..0.025 -> -1.0
                supportDistance != null && supportDistance in 0.0..0.03 &&
                    last.close >= last.open -> 1.0
                else -> 0.0
            }
            addRatingSignal("الدعم والمقاومة", structureSignal, 1.0, when {
                structureSignal > 0.0 -> "السعر قرب دعم مع شمعة صاعدة"
                structureSignal < 0.0 -> "السعر قريب من مقاومة"
                else -> "لا توجد ملامسة قريبة تعطي إشارة واضحة"
            })
        }

        val finalRatingScore = if (ratingWeight > 0.0)
            (ratingNet / ratingWeight * 100.0).toInt().coerceIn(-100, 100) else 0
        val finalRating = when {
            ratingWeight < 3.0 -> "محايد / انتظار"
            ratingWeight >= 5.0 && finalRatingScore >= 55 -> "شراء قوي"
            finalRatingScore >= 22 -> "شراء بحذر"
            ratingWeight >= 5.0 && finalRatingScore <= -55 -> "بيع قوي"
            finalRatingScore <= -22 -> "بيع بحذر"
            else -> "محايد / انتظار"
        }
        val finalRatingReason = when {
            ratingWeight < 3.0 -> "البيانات المتاحة لا تكفي لتأكيد اتجاه قوي؛ انتظر اكتمال المؤشرات."
            finalRating == "شراء قوي" -> "توافق مرتفع بين عدة مؤشرات إيجابية، مع ضرورة تأكيد الحركة بالسعر والسيولة."
            finalRating == "شراء بحذر" -> "الإشارات تميل للصعود لكنها غير مكتملة أو يوجد بينها بعض الاختلاف."
            finalRating == "بيع قوي" -> "توافق مرتفع بين عدة مؤشرات سلبية؛ راقب الدعوم وإدارة المخاطر."
            finalRating == "بيع بحذر" -> "الإشارات تميل للهبوط لكنها غير مكتملة أو يوجد بينها بعض الاختلاف."
            else -> "الإشارات متضاربة أو متوازنة؛ الانتظار حتى ظهور تأكيد أوضح هو القراءة الأكثر تحفظًا."
        }
        if (ratingWeight < 5.0) {
            ratingFactors.add("تنبيه: بعض المؤشرات غير متاحة لعدم كفاية عدد الجلسات أو بيانات الحجم.")
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
        // A low score alone is not enough to call a stock bearish. Require several
        // independent bearish signals; otherwise classify mixed/uncertain readings as neutral.
        val clearlyBearish = trend == "هابط" && rsi < 45.0 && macdHistogram < 0.0
        val label = when {
            score >= 75 && trend != "ضغط هابط" && trend != "هابط" -> "إيجابي فنيًا"
            score >= 62 -> "يميل للإيجابية"
            score <= 35 && clearlyBearish -> "ضعيف فنيًا"
            else -> "محايد / مراقبة"
        }
        return Analysis(
            last, supports, resistances, rsi, s20, s50, macd, macdSignal,
            atr, bbLower, bbUpper, trend, patterns,
            listOf(shortTarget, mediumTarget, longTarget), stop, score, label,
            volumeRatio, rr, parts, rows.size, avgVolume > 0.0 && last.volume > 0.0,
            marketState, marketStateReason, finalRating, finalRatingScore,
            finalRatingReason, ratingFactors
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
