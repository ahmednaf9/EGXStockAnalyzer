package com.multimarket.analyzer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class ScorePart(val name: String, val points: Int, val maxPoints: Int)

data class CandlestickPattern(
    val name: String,
    val date: String,
    val direction: String,
    val confirmation: String,
    val explanation: String,
    val patternOpen: Double,
    val patternHigh: Double,
    val patternLow: Double,
    val patternClose: Double,
    val confirmationDate: String?,
    val confirmationPrice: Double?,
    val entryPrice: Double?,
    val stopPrice: Double?,
    val targets: List<Double>
)

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
    val recentPatterns: List<CandlestickPattern>,
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
    val marketStateInterpretation: String,
    val scoreInterpretation: String,
    val technicalClassificationReason: String,
    val executionRecommendationReason: String,
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
        val recentPatterns = detectRecentPatterns(rows)
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
                "تشير مقارنة حركة السعر وأحجام التداول إلى ميل نسبي للشراء. درجة وضوح القراءة: $marketStateStrength. " +
                "مؤشر تقديري يقارن ضغط الشراء بضغط البيع: ${oneDecimal(flowRatio * 100.0)}%، " +
                "توازن أحجام الشموع الصاعدة والهابطة: ${oneDecimal(volumeBalance * 100.0)}%، " +
                "تغير السعر خلال 20 جلسة: ${oneDecimal(priceChange20 * 100.0)}%."
            "تصريف محتمل" ->
                "تشير مقارنة حركة السعر وأحجام التداول إلى ميل نسبي للبيع. درجة وضوح القراءة: $marketStateStrength. " +
                "مؤشر تدفق الأموال التقديري: ${oneDecimal(flowRatio * 100.0)}%، " +
                "توازن أحجام الشموع الصاعدة والهابطة: ${oneDecimal(volumeBalance * 100.0)}%، " +
                "تغير السعر خلال 20 جلسة: ${oneDecimal(priceChange20 * 100.0)}%."
            "غير محسوم" ->
                "بيانات حجم التداول المتاحة أقل من 10 جلسات؛ لذلك لا يمكن تكوين قراءة موثوقة لاحتمال التجميع أو التصريف."
            else ->
                "لا تتفق مؤشرات تدفق الأموال وأحجام الشموع وحركة السعر بدرجة كافية لتحديد ميل واضح نحو التجميع أو التصريف. " +
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
            rsi in 55.0..70.0 -> "زخم السعر يميل إلى الصعود؛ أي أن المكاسب الأخيرة أقوى نسبيًا من التراجعات"
            rsi > 70.0 && rsi <= 80.0 -> "حركة صاعدة قوية، لكن السعر قد يكون ارتفع بسرعة وأصبح قريبًا من التشبع الشرائي"
            rsi > 80.0 -> "قوة صعود مرتفعة جدًا؛ لكنها لا تعني وحدها أن وقت البيع قد حان"
            rsi in 50.0..55.0 -> "إشارة إيجابية بسيطة، لكنها لا تؤكد الصعود وحدها"
            rsi in 32.0..45.0 -> "زخم السعر يميل إلى الهبوط؛ أي أن التراجعات الأخيرة أقوى نسبيًا من المكاسب"
            rsi < 32.0 -> "ضعف واضح في الحركة؛ وقد يحدث ارتداد مؤقت، لكنه يحتاج إلى تأكيد من السعر"
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
                macdSignalStrength >= 1.0 -> "إشارة إيجابية: MACD أعلى من خط الإشارة وفوق مستوى الصفر، ما يدعم الزخم الصاعد"
                macdSignalStrength > 0.0 -> "تقاطع صاعد: MACD أعلى من خط الإشارة، لكنه ما زال أسفل مستوى الصفر"
                macdSignalStrength <= -1.0 -> "إشارة سلبية: MACD أسفل خط الإشارة وتحت مستوى الصفر، ما يدعم ضعف الزخم"
                macdSignalStrength < 0.0 -> "الزخم يتراجع: MACD أسفل خط الإشارة، لكنه ما زال فوق مستوى الصفر"
                else -> "MACD قريب من خط الإشارة ولا يظهر اختلافًا واضحًا عنه"
            }
        )

        s20?.let { average ->
            val signal = when {
                last.close > average * 1.003 -> 1.0
                last.close < average * 0.997 -> -1.0
                else -> 0.0
            }
            addRatingSignal("SMA 20", signal, 1.0, when {
                signal > 0.0 -> "سعر الإغلاق أعلى من متوسط الإغلاق خلال آخر 20 جلسة؛ وهذا يميل إلى دعم الاتجاه الصاعد"
                signal < 0.0 -> "سعر الإغلاق أقل من متوسط الإغلاق خلال آخر 20 جلسة؛ وهذا يميل إلى دعم الاتجاه الهابط"
                else -> "سعر الإغلاق قريب من متوسط الإغلاق خلال آخر 20 جلسة؛ ولا تظهر أفضلية واضحة"
            })
        }
        s50?.let { average ->
            val signal = when {
                last.close > average * 1.003 -> 1.0
                last.close < average * 0.997 -> -1.0
                else -> 0.0
            }
            addRatingSignal("SMA 50", signal, 1.0, when {
                signal > 0.0 -> "سعر إغلاق السهم أعلى من المتوسط المتحرك"
                signal < 0.0 -> "سعر إغلاق السهم أقل من المتوسط المتحرك"
                else -> "سعر إغلاق السهم قريب من المتوسط المتحرك"
            })
        }
        if (s20 != null && s50 != null) {
            val signal = when {
                s20 > s50 * 1.002 -> 1.0
                s20 < s50 * 0.998 -> -1.0
                else -> 0.0
            }
            addRatingSignal("ترتيب المتوسطات", signal, 1.0, when {
                signal > 0.0 -> "المتوسط المتحرك لـ20 جلسة أعلى من متوسط 50 جلسة؛ وهذا ترتيب يميل إلى دعم الاتجاه الصاعد"
                signal < 0.0 -> "المتوسط المتحرك لـ20 جلسة أقل من متوسط 50 جلسة؛ وهذا ترتيب يميل إلى دعم الاتجاه الهابط"
                else -> "المتوسطان المتحركان لـ20 و50 جلسة متقاربان، ولا يظهر بينهما اتجاه واضح"
            })
        }

        if (avgVolume > 0.0 && last.volume > 0.0) {
            val volumeSignal = when {
                volumeRatio >= 1.3 && last.close > previous.close -> 1.0
                volumeRatio >= 1.3 && last.close < previous.close -> -1.0
                else -> 0.0
            }
            addRatingSignal("حجم التداول", volumeSignal, 1.0, when {
                volumeSignal > 0.0 -> "حجم التداول ارتفع مع صعود السعر؛ أي أن نشاط التداول يدعم الحركة الصاعدة نسبيًا"
                volumeSignal < 0.0 -> "حجم التداول ارتفع مع هبوط السعر؛ أي أن نشاط التداول يدعم الحركة الهابطة نسبيًا"
                else -> "حجم التداول لا يقدم تأكيدًا واضحًا لحركة السعر"
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
                structureSignal > 0.0 -> "السعر يقترب من منطقة دعم وظهرت شمعة صاعدة؛ وقد يشير ذلك إلى محاولة ارتداد تحتاج إلى تأكيد"
                structureSignal < 0.0 -> "السعر يقترب من منطقة مقاومة قد يواجه عندها صعوبة في مواصلة الصعود"
                else -> "السعر ليس قريبًا من مستوى دعم أو مقاومة بما يكفي لإظهار إشارة واضحة"
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
            ratingFactors.add("ملاحظة: يلزم توفر 50 جلسة تداول على الأقل لحساب المتوسط المتحرك SMA 50 وإتاحة التقييم الفني الكامل.")
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
        val marketStateInterpretation = when (marketState) {
            "تجميع محتمل" -> "تُظهر بيانات السعر وحجم التداول ميلًا نسبيًا للشراء، وقد يشير ذلك إلى تجميع محتمل، لكنه تقدير فني لا يثبت دخول محافظ أو مؤسسات كبيرة."
            "تصريف محتمل" -> "تُظهر بيانات السعر وحجم التداول ميلًا نسبيًا للبيع، وقد يشير ذلك إلى تصريف محتمل، لكنه تقدير فني وليس دليلًا قاطعًا على خروج محافظ أو مؤسسات كبيرة."
            "غير محسوم" -> "بيانات حجم التداول غير كافية؛ لذلك لا يمكن تحديد ما إذا كانت الحركة تشير إلى تجميع أم تصريف."
            else -> "الحركة متذبذبة، ولا يوجد حتى الآن توافق كافٍ يثبت سيطرة المشترين أو البائعين."
        }
        val scoreInterpretation = when {
            score >= 75 && trend == "صاعد" -> "درجة التقييم مرتفعة مع اتجاه صاعد؛ راقب تأكيد الاختراق وحجم التداول، فالدرجة وحدها لا تضمن استمرار الصعود."
            score >= 75 -> "درجة التقييم مرتفعة، لكن اتجاه السعر لا يؤكد صعودًا واضحًا؛ انتظر إشارة إضافية قبل الاستنتاج."
            score >= 62 -> "توجد نقاط إيجابية، لكنها غير كافية وحدها لاعتبار السهم فرصة شراء مؤكدة."
            score <= 35 && clearlyBearish -> "النتيجة منخفضة مع اتجاه وزخم هابطين؛ الأولوية لحماية رأس المال ومراقبة الدعم."
            score <= 35 -> "النتيجة منخفضة، لكن إشارات الهبوط ليست متوافقة بالكامل؛ تجنب الحكم من الدرجة وحدها."
            else -> "الإشارات مختلطة؛ لا توجد أفضلية فنية واضحة حاليًا، والأفضل انتظار تأكيد جديد."
        }
        val technicalClassificationReason = buildString {
            append("التصنيف الفني مبني على درجة التقييم ").append(score).append(" من 100 واتجاه السهم ").append(trend).append("؛ وهو وصف للإشارات الحالية وليس نسبة لاحتمال الربح. ")
            append(when (technicalClassification) {
                "قوي جداً" -> "تجتمع درجة مرتفعة مع اتجاه صاعد واضح؛ ومع ذلك يلزم فحص المقاومة والعائد إلى المخاطرة."
                "قوي" -> "المؤشرات تميل بقوة للإيجابية، لكن شروط التصنيف الأقوى غير مكتملة."
                "إيجابي" -> "هناك تفوق نسبي للإشارات الإيجابية، مع بقاء بعض عوامل التأكيد أو المخاطر."
                "محايد" -> "الإشارات متوازنة أو متعارضة؛ لا توجد أفضلية كافية للمشترين أو البائعين."
                "ضعيف" -> "النتيجة تميل للسلبية، لكن لا تتوافر كل شروط الضعف الشديد المتزامن."
                else -> "النتيجة منخفضة جدًا؛ راقب الاتجاه والزخم والدعوم، ولا تعتمد على التصنيف وحده."
            })
            append(" RSI=").append(oneDecimal(rsi)).append("، وحجم التداول إلى متوسطه=").append(oneDecimal(volumeRatio)).append(" مرة.")
        }
        val executionRecommendationReason = when (executionRecommendation) {
            "شراء" -> "التصنيف قوي جدًا والعائد إلى المخاطرة مناسب وفق الحساب الحالي؛ لا تدخل إلا بعد تحقق شروط السعر والسيولة ووقف الخسارة."
            "شراء تدريجي" -> "الإشارات تميل إلى الإيجابية، لكن التأكيد غير مكتمل؛ وإذا اتُخذ قرار بالدخول، فمن الأفضل توزيع الكمية على مراحل بدلًا من الشراء دفعة واحدة."
            "مراقبة" -> "لا توجد إشارة دخول مكتملة الآن؛ راقب اختراق المقاومة أو ارتدادًا مؤكدًا من الدعم مع تحسن الحجم."
            "انتظار" -> "الإشارات محايدة؛ انتظر اتجاهًا أوضح وتوافقًا أفضل بين المؤشرات قبل اتخاذ قرار."
            else -> "المخاطر الفنية مرتفعة أو الاتجاه غير مناسب؛ تجنب فتح مركز جديد حتى تتحسن الإشارات ويثبت السعر دعمه."
        }
        return Analysis(
            last, supports, resistances, rsi, s20, s50, macd, macdSignal,
            atr, bbLower, bbUpper, trend, patterns, recentPatterns,
            listOf(shortTarget, mediumTarget, longTarget), stop, score, label,
            technicalClassification, executionRecommendation,
            volumeRatio, rr, parts, rows.size, avgVolume > 0.0 && last.volume > 0.0,
            marketState, marketStateScore, marketStateStrength, marketStateReason,
            marketStateInterpretation, scoreInterpretation, technicalClassificationReason,
            executionRecommendationReason, finalRating, finalRatingScore,
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

    private fun detectRecentPatterns(rows: List<Candle>): List<CandlestickPattern> {
        if (rows.size < 2) return emptyList()
        val start = (rows.lastIndex - 9).coerceAtLeast(1)
        val found = mutableListOf<CandlestickPattern>()

        for (i in start..rows.lastIndex) {
            val candle = rows[i]
            val previous = rows[i - 1]
            val range = (candle.high - candle.low).coerceAtLeast(0.000001)
            val body = abs(candle.close - candle.open)
            val upperShadow = (candle.high - maxOf(candle.open, candle.close)).coerceAtLeast(0.0)
            val lowerShadow = (minOf(candle.open, candle.close) - candle.low).coerceAtLeast(0.0)

            val candidates = mutableListOf<Pair<String, String>>()
            if (body / range <= 0.10) {
                candidates.add("دوجي" to "محايد")
            } else {
                if (lowerShadow >= body * 2.0 && upperShadow <= body * 0.8) {
                    candidates.add("مطرقة محتملة" to "إيجابي")
                }
                if (upperShadow >= body * 2.0 && lowerShadow <= body * 0.8) {
                    candidates.add("شهاب محتمل" to "سلبي")
                }
            }
            if (candle.close > candle.open && previous.close < previous.open &&
                candle.close >= previous.open && candle.open <= previous.close) {
                candidates.add("ابتلاع شرائي" to "إيجابي")
            }
            if (candle.close < candle.open && previous.close > previous.open &&
                candle.open >= previous.close && candle.close <= previous.open) {
                candidates.add("ابتلاع بيعي" to "سلبي")
            }

            for ((name, direction) in candidates) {
                val next = rows.getOrNull(i + 1)
                val confirmation = when {
                    next == null -> "لم تتأكد بعد — ننتظر الجلسة التالية"
                    direction == "إيجابي" && next.close > candle.high -> "تأكدت: إغلاق الجلسة التالية أعلى قمة النموذج"
                    direction == "إيجابي" && next.close < candle.low -> "أُبطلت: إغلاق الجلسة التالية أسفل قاع النموذج"
                    direction == "سلبي" && next.close < candle.low -> "تأكدت: إغلاق الجلسة التالية أسفل قاع النموذج"
                    direction == "سلبي" && next.close > candle.high -> "أُبطلت: إغلاق الجلسة التالية أعلى قمة النموذج"
                    direction == "محايد" && next.close > candle.high -> "تحول إلى إشارة إيجابية: إغلاق أعلى قمة الدوجي"
                    direction == "محايد" && next.close < candle.low -> "تحول إلى إشارة سلبية: إغلاق أسفل قاع الدوجي"
                    else -> "لم تتأكد: الإغلاق التالي لم يتجاوز حدود النموذج"
                }
                val explanation = when (name) {
                    "دوجي" -> "سعرا الافتتاح والإغلاق متقاربان، ما يعني أن المشترين والبائعين لم يحسموا الاتجاه خلال الجلسة. حركة الجلسات التالية تساعد في تأكيد الاتجاه."
                    "مطرقة محتملة" -> "لشمعة المطرقة ظل سفلي طويل، ما قد يعني أن المشترين دفعوا السعر للصعود بعد هبوطه خلال الجلسة. تكون دلالتها أهم إذا ظهرت بعد اتجاه هابط، وتحتاج إلى تأكيد."
                    "شهاب محتمل" -> "لشمعة الشهاب ظل علوي طويل، ما قد يعني أن البائعين ضغطوا على السعر بعد ارتفاعه خلال الجلسة. تكون دلالتها أهم إذا ظهرت بعد اتجاه صاعد، وتحتاج إلى تأكيد."
                    "ابتلاع شرائي" -> "جسم الشمعة الصاعدة أكبر ويغطي جسم الشمعة الهابطة السابقة؛ وقد يشير ذلك إلى زيادة قوة المشترين، خاصة إذا ظهر بعد هبوط."
                    else -> "جسم الشمعة الهابطة أكبر ويغطي جسم الشمعة الصاعدة السابقة؛ وقد يشير ذلك إلى زيادة قوة البائعين، خاصة إذا ظهر بعد صعود."
                }
                val isDirectional = direction != "محايد"
                val entry = when (direction) {
                    "إيجابي" -> candle.high
                    "سلبي" -> candle.low
                    else -> null
                }
                val stop = when (direction) {
                    "إيجابي" -> candle.low
                    "سلبي" -> candle.high
                    else -> null
                }
                val patternRange = (candle.high - candle.low).coerceAtLeast(0.0)
                val targets = if (isDirectional && entry != null) {
                    val sign = if (direction == "إيجابي") 1.0 else -1.0
                    listOf(1.0, 1.618, 2.618).map { entry + sign * patternRange * it }
                } else emptyList()
                found.add(
                    CandlestickPattern(
                        name = name,
                        date = candle.date,
                        direction = direction,
                        confirmation = confirmation,
                        explanation = explanation,
                        patternOpen = candle.open,
                        patternHigh = candle.high,
                        patternLow = candle.low,
                        patternClose = candle.close,
                        confirmationDate = next?.date,
                        confirmationPrice = next?.close,
                        entryPrice = entry,
                        stopPrice = stop,
                        targets = targets
                    )
                )
            }
        }
        return found.sortedByDescending { dateSortKey(it.date) }
    }

    private fun detectPatterns(rows: List<Candle>): List<String> {
        val c = rows.last()
        val p = rows[rows.lastIndex - 1]
        val range = (c.high - c.low).coerceAtLeast(0.000001)
        val body = abs(c.close - c.open)
        val upperShadow = c.high - maxOf(c.open, c.close)
        val lowerShadow = minOf(c.open, c.close) - c.low
        val result = mutableListOf<String>()
        if (body / range <= 0.10) result.add("محايد: شمعة دوجي — سعرا الافتتاح والإغلاق متقاربان، ما يعكس ترددًا في تحديد الاتجاه")
        if (lowerShadow >= body * 2.0 && upperShadow <= body * 0.8 && c.close > c.open)
            result.add("إيجابي: شمعة مطرقة محتملة — قد تشير إلى محاولة ارتداد بعد هبوط، وتحتاج إلى تأكيد")
        if (upperShadow >= body * 2.0 && lowerShadow <= body * 0.8 && c.close < c.open)
            result.add("سلبي: شمعة شهاب محتملة — قد تشير إلى ضعف الصعود بعد ارتفاع، وتحتاج إلى تأكيد")
        if (c.close > c.open && p.close < p.open && c.close >= p.open && c.open <= p.close)
            result.add("إيجابي: نموذج ابتلاع شرائي محتمل — قد يعكس زيادة قوة المشترين")
        if (c.close < c.open && p.close > p.open && c.open >= p.close && c.close <= p.open)
            result.add("سلبي: نموذج ابتلاع بيعي محتمل — قد يعكس زيادة قوة البائعين")
        if (result.isEmpty()) result.add("لم يرصد البرنامج نموذج شموع أساسيًا واضحًا في آخر جلسة")
        return result
    }

    private fun oneDecimal(value: Double): String =
        String.format(java.util.Locale.US, "%.1f", value)
}
