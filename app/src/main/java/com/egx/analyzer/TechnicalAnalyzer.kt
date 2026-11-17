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
    val technicalClassification: String,
    val executionRecommendation: String,
    val volumeRatio: Double,
    val riskReward: Double?,
    val scoreParts: List<ScorePart>,
    val sessions: Int,
    val volumeAvailable: Boolean,
    val marketState: String,
    val marketStateScore: Int,
    val marketStateStrength: String,
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
        require(input.size >= 35) { "At least 35 valid candles are required for reliable RSI and MACD analysis." }
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
        var atrValue = trueRanges.take(14).average()
        for (i in 14 until trueRanges.size) {
            atrValue = (atrValue * 13.0 + trueRanges[i]) / 14.0
        }
        val atr = atrValue.coerceAtLeast(0.000001)
        val s20 = sma(closes, 20)
        val s50 = sma(closes, 50)
        val last20 = closes.takeLast(20)
        val bbMean = last20.average()
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
        val stop = ((nearestSupport?.minus(atr * 0.2)) ?: (last.close - atr * 1.5))
            .coerceAtLeast(0.000001)
        val shortTarget = nearestResistance ?: (last.close + atr * 1.5)
        val mediumTarget = resistances.getOrNull(1)
            ?: max(shortTarget + atr * 1.5, last.close + atr * 3.0)
        val longTarget = resistances.getOrNull(2)
            ?: max(mediumTarget + atr * 2.0, last.close + atr * 5.0)
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
            rsi in 52.0..70.0 && macdHistogram > 0.0 -> 15
            rsi in 45.0..75.0 && macdHistogram >= 0.0 -> 11
            rsi < 30.0 && macdHistogram < 0.0 -> 3
            rsi in 35.0..50.0 -> 7
            rsi > 75.0 -> 8
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
            nearestSupport != null && (last.close - nearestSupport) / last.close <= 0.025 &&
                last.close > last.open && trend != "ضغط هابط" && trend != "هابط" -> 15
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
        // Estimate accumulation/distribution using price and volume together.
        val flowRows = rows.takeLast(20)
        val flowRowsWithVolume = flowRows.filter { it.volume > 0.0 }
        fun moneyFlowMultiplier(c: Candle): Double {
            val range = (c.high - c.low).coerceAtLeast(0.000001)
            return ((2.0 * c.close - c.high - c.low) / range).coerceIn(-1.0, 1.0)
        }
        val signedFlow = flowRowsWithVolume.sumOf { moneyFlowMultiplier(it) * it.volume }
        val totalFlowVolume = flowRowsWithVolume.sumOf { it.volume }
        val flowRatio = if (totalFlowVolume > 0.0) signedFlow / totalFlowVolume else 0.0
        val priceChange20 = if (flowRows.size >= 2 && flowRows.first().close > 0.0)
            (last.close - flowRows.first().close) / flowRows.first().close else 0.0

        val directionalRows = flowRowsWithVolume.filter { it.close != it.open }
        val upVolume = directionalRows.filter { it.close > it.open }.sumOf { it.volume }
        val downVolume = directionalRows.filter { it.close < it.open }.sumOf { it.volume }
        val directionalVolume = upVolume + downVolume
        val volumeBalance = if (directionalVolume > 0.0)
            (upVolume - downVolume) / directionalVolume else 0.0

        val recentFlowRows = flowRowsWithVolume.takeLast(5)
        val recentFlowVolume = recentFlowRows.sumOf { it.volume }
        val recentFlowRatio = if (recentFlowVolume > 0.0)
            recentFlowRows.sumOf { moneyFlowMultiplier(it) * it.volume } / recentFlowVolume else 0.0

        val marketStateScore = if (flowRowsWithVolume.size >= 10 && totalFlowVolume > 0.0) {
            val priceComponent = (priceChange20 / 0.08 * 100.0).coerceIn(-100.0, 100.0)
            (flowRatio * 45.0 + volumeBalance * 25.0 +
                priceComponent * 0.20 + recentFlowRatio * 10.0)
                .toInt().coerceIn(-100, 100)
        } else 0

        val marketState = when {
            flowRowsWithVolume.size < 10 -> "غير محسوم"
            marketStateScore >= 22 && flowRatio >= 0.05 -> "تجميع محتمل"
            marketStateScore <= -22 && flowRatio <= -0.05 -> "تصريف محتمل"
            else -> "تذبذب / حياد"
        }
        val marketStateStrength = when {
            marketState == "غير محسوم" -> "غير متاحة"
            abs(marketStateScore) >= 65 -> "قوية نسبيًا"
            abs(marketStateScore) >= 40 -> "متوسطة"
            abs(marketStateScore) >= 22 -> "محدودة"
            else -> "لا توجد إشارة حاسمة"
        }
        val marketStateReason = when (marketState) {
            "تجميع محتمل" ->
                "تدفق السيولة يميل للشراء. قوة الإشارة: $marketStateStrength. " +
                "تدفق الأموال: ${oneDecimal(flowRatio * 100.0)}%، " +
                "توازن أحجام الشموع الصاعدة والهابطة: ${oneDecimal(volumeBalance * 100.0)}%، " +
                "تغير السعر خلال 20 جلسة: ${oneDecimal(priceChange20 * 100.0)}%."
            "تصريف محتمل" ->
                "تدفق السيولة يميل للبيع. قوة الإشارة: $marketStateStrength. " +
                "تدفق الأموال: ${oneDecimal(flowRatio * 100.0)}%، " +
                "توازن أحجام الشموع الصاعدة والهابطة: ${oneDecimal(volumeBalance * 100.0)}%، " +
                "تغير السعر خلال 20 جلسة: ${oneDecimal(priceChange20 * 100.0)}%."
            "غير محسوم" ->
                "بيانات الحجم المتاحة أقل من 10 جلسات؛ لا يمكن الحكم على التجميع أو التصريف."
            else ->
                "لا يوجد توافق كافٍ بين تدفق الأموال وحجم الشموع وحركة السعر لتأكيد التجميع أو التصريف. " +
                "تدفق الأموال: ${oneDecimal(flowRatio * 100.0)}%، " +
                "تغير السعر خلال 20 جلسة: ${oneDecimal(priceChange20 * 100.0)}%."
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
            rsi in 55.0..70.0 -> 1.0
            rsi > 70.0 && rsi <= 80.0 -> 0.35
            rsi > 80.0 -> 0.10
            rsi in 50.0..55.0 -> 0.25
            rsi in 32.0..45.0 -> -0.75
            rsi < 32.0 -> -0.30
            else -> 0.0
        }
        val rsiText = when {
            rsi in 55.0..70.0 -> "زخم إيجابي"
            rsi > 70.0 && rsi <= 80.0 -> "زخم قوي مع ارتفاع مخاطر التشبع الشرائي"
            rsi > 80.0 -> "زخم مرتفع جدًا؛ لا يُعد وحده إشارة بيع"
            rsi in 50.0..55.0 -> "ميل إيجابي محدود"
            rsi in 32.0..45.0 -> "زخم سلبي"
            rsi < 32.0 -> "ضعف واضح مع احتمال ارتداد فني"
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
                    last.close > last.open && trend != "هابط" && trend != "ضغط هابط" -> 1.0
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
        val enoughForStrongRating = rows.size >= 50 &&
            avgVolume > 0.0 && last.volume > 0.0 &&
            (nearestSupport != null || nearestResistance != null)
        val strongBullishConfirmation = enoughForStrongRating &&
            trend == "صاعد" && macd > macdSignal && macd > 0.0 &&
            volumeRatio >= 1.3 && last.close > previous.close
        val strongBearishConfirmation = enoughForStrongRating &&
            trend == "هابط" && macd < macdSignal && macd < 0.0 &&
            volumeRatio >= 1.3 && last.close < previous.close
        val finalRating = when {
            ratingWeight < 3.0 -> "محايد / انتظار"
            finalRatingScore >= 60 && strongBullishConfirmation -> "شراء قوي"
            finalRatingScore >= 22 -> "شراء بحذر"
            finalRatingScore <= -60 && strongBearishConfirmation -> "بيع قوي"
            finalRatingScore <= -22 -> "بيع بحذر"
            else -> "محايد / انتظار"
        }
        val finalRatingReason = when {
            ratingWeight < 3.0 -> "البيانات المتاحة لا تكفي لتأكيد اتجاه واضح؛ انتظر اكتمال المؤشرات."
            finalRating == "شراء قوي" -> "اتفاق الاتجاه والزخم وحجم التداول يدعم السيناريو الصاعد، مع ضرورة الالتزام بوقف الخسارة."
            finalRating == "شراء بحذر" -> "الإشارات تميل للصعود لكنها غير مكتملة أو يوجد بينها بعض الاختلاف."
            finalRating == "بيع قوي" -> "اتفاق الاتجاه والزخم وحجم التداول يدعم السيناريو الهابط؛ راقب الدعوم وإدارة المخاطر."
            finalRating == "بيع بحذر" -> "الإشارات تميل للهبوط لكنها غير مكتملة أو يوجد بينها بعض الاختلاف."
            else -> "الإشارات متضاربة أو متوازنة؛ الانتظار حتى ظهور تأكيد أوضح هو القراءة الأكثر تحفظًا."
        }
        if (ratingWeight < 5.0) {
            ratingFactors.add("تنبيه: بعض المؤشرات غير متاحة لعدم كفاية عدد الجلسات أو بيانات الحجم.")
        }
        if (rows.size < 50) {
            ratingFactors.add("ملاحظة: يلزم 50 جلسة على الأقل لإتاحة SMA 50 والتصنيف القوي.")
        }
        if (avgVolume <= 0.0 || last.volume <= 0.0) {
            ratingFactors.add("ملاحظة: بيانات حجم التداول غير كافية لتأكيد الاتجاه.")
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
        val technicalClassification = when {
            score >= 90 && trend == "صاعد" -> "قوي جداً"
            score >= 80 && trend != "هابط" && trend != "ضغط هابط" -> "قوي"
            score >= 65 && trend != "هابط" -> "إيجابي"
            score >= 45 -> "محايد"
            score >= 25 -> "ضعيف"
            else -> "ضعيف جداً"
        }
        val executionRecommendation = when {
            technicalClassification == "قوي جداً" && (rr ?: 0.0) >= 2.0 -> "شراء"
            technicalClassification == "قوي جداً" && (rr ?: 0.0) >= 1.2 -> "شراء تدريجي"
            technicalClassification in listOf("قوي", "إيجابي") &&
                trend != "هابط" && (rr ?: 0.0) >= 1.2 -> "شراء تدريجي"
            technicalClassification in listOf("قوي", "إيجابي") -> "مراقبة"
            technicalClassification == "محايد" && trend == "تحسن صاعد" -> "مراقبة"
            technicalClassification == "محايد" -> "انتظار"
            else -> "تجنب"
        }
        return Analysis(
            last, supports, resistances, rsi, s20, s50, macd, macdSignal,
            atr, bbLower, bbUpper, trend, patterns,
            listOf(shortTarget, mediumTarget, longTarget), stop, score, label,
            technicalClassification, executionRecommendation,
            volumeRatio, rr, parts, rows.size, avgVolume > 0.0 && last.volume > 0.0,
            marketState, marketStateScore, marketStateStrength, marketStateReason,
            finalRating, finalRatingScore,
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

    private fun oneDecimal(value: Double): String =
        String.format(java.util.Locale.US, "%.1f", value)
}
