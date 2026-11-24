package com.egx.analyzer

import kotlin.math.abs

data class ClassicPatternResult(
    val name: String,
    val bias: String,
    val status: String,
    val trigger: Double,
    val targetSuccess: Double,
    val targetFailure: Double,
    val invalidation: Double,
    val explanation: String,
    val confidence: Int,
    val confidenceDetails: String
)

/**
 * Heuristic classical-pattern scan using recent swing pivots and measured moves.
 * Signals are descriptive candidates, not guaranteed forecasts.
 */
object ClassicPatternScanner {
    fun scan(input: List<Candle>): List<ClassicPatternResult> {
        val data = input.filter {
            it.date.isNotBlank() && it.open.isFinite() && it.high.isFinite() &&
                it.low.isFinite() && it.close.isFinite() && it.volume.isFinite() &&
                it.open > 0.0 && it.high > 0.0 && it.low > 0.0 && it.close > 0.0 &&
                it.volume >= 0.0 && it.high >= maxOf(it.open, it.close, it.low) &&
                it.low <= minOf(it.open, it.close)
        }.takeLast(180)
        if (data.size < 40) return emptyList()
        // Detect local pivots, then suppress nearby same-type noise using a minimum spacing.
        val rawPivots = mutableListOf<Pair<Int, Boolean>>() // index, high?
        for (i in 2 until data.size - 2) {
            if (data[i].high >= data[i-1].high && data[i].high >= data[i-2].high &&
                data[i].high > data[i+1].high && data[i].high > data[i+2].high) rawPivots += i to true
            if (data[i].low <= data[i-1].low && data[i].low <= data[i-2].low &&
                data[i].low < data[i+1].low && data[i].low < data[i+2].low) rawPivots += i to false
        }
        val pivots = mutableListOf<Pair<Int, Boolean>>()
        rawPivots.sortedBy { it.first }.forEach { pivot ->
            val previousSame = pivots.lastOrNull { it.second == pivot.second }
            if (previousSame == null || pivot.first - previousSame.first >= 4) {
                pivots += pivot
            } else {
                val oldValue = if (previousSame.second) data[previousSame.first].high else data[previousSame.first].low
                val newValue = if (pivot.second) data[pivot.first].high else data[pivot.first].low
                val moreExtreme = if (pivot.second) newValue > oldValue else newValue < oldValue
                if (moreExtreme) pivots[pivots.indexOf(previousSame)] = pivot
            }
        }
        // Require pivots to alternate; if consecutive same-type pivots remain, retain the more extreme one.
        val alternating = mutableListOf<Pair<Int, Boolean>>()
        pivots.sortedBy { it.first }.forEach { pivot ->
            val previous = alternating.lastOrNull()
            if (previous == null || previous.second != pivot.second) alternating += pivot
            else {
                val oldValue = if (pivot.second) data[previous.first].high else data[previous.first].low
                val newValue = if (pivot.second) data[pivot.first].high else data[pivot.first].low
                if ((pivot.second && newValue > oldValue) || (!pivot.second && newValue < oldValue))
                    alternating[alternating.lastIndex] = pivot
            }
        }
        val out = mutableListOf<ClassicPatternResult>()
        val last = data.last().close
        val highs = alternating.filter { it.second }.takeLast(7).map { data[it.first].high }
        val lows = alternating.filter { !it.second }.takeLast(7).map { data[it.first].low }

        // Reversal formations are evaluated from consecutive chronological pivots.
        // This prevents unrelated highs/lows from being paired across different swings.
        val recent3 = alternating.takeLast(3)
        if (recent3.size == 3 && recent3[0].second && !recent3[1].second && recent3[2].second) {
            val firstTop = data[recent3[0].first].high
            val valley = data[recent3[1].first].low
            val secondTop = data[recent3[2].first].high
            val averageTop = (firstTop + secondTop) / 2.0
            val height = averageTop - valley
            if (height > 0.0 && abs(firstTop - secondTop) / maxOf(firstTop, secondTop) < 0.045) {
                out += candidate("القمة المزدوجة", "هابط", valley, valley - height, valley + height, last,
                    "قمتان متقاربتان يفصل بينهما قاع واضح؛ التأكيد الهابط بإغلاق أسفل القاع الفاصل.")
            }
        }
        if (recent3.size == 3 && !recent3[0].second && recent3[1].second && !recent3[2].second) {
            val firstBottom = data[recent3[0].first].low
            val peak = data[recent3[1].first].high
            val secondBottom = data[recent3[2].first].low
            val averageBottom = (firstBottom + secondBottom) / 2.0
            val height = peak - averageBottom
            if (height > 0.0 && abs(firstBottom - secondBottom) / maxOf(firstBottom, secondBottom) < 0.045) {
                out += candidate("القاع المزدوج", "صاعد", peak, peak + height, peak - height, last,
                    "قاعان متقاربان يفصل بينهما ارتفاع واضح؛ التأكيد الصاعد باختراق القمة الفاصلة.")
            }
        }

        val recent5 = alternating.takeLast(5)
        if (recent5.size == 5 && recent5.map { it.second } == listOf(true, false, true, false, true)) {
            val leftShoulder = data[recent5[0].first].high
            val firstNeck = data[recent5[1].first].low
            val head = data[recent5[2].first].high
            val secondNeck = data[recent5[3].first].low
            val rightShoulder = data[recent5[4].first].high
            val neck = (firstNeck + secondNeck) / 2.0
            val height = head - neck
            val shouldersSimilar = abs(leftShoulder - rightShoulder) / maxOf(leftShoulder, rightShoulder) < 0.08
            val necklineBalanced = abs(firstNeck - secondNeck) / maxOf(firstNeck, secondNeck) < 0.07
            val headDominant = head > maxOf(leftShoulder, rightShoulder) * 1.025
            val swingSpacingValid = recent5.zipWithNext().all { (a, b) -> b.first - a.first >= 4 }
            if (headDominant && shouldersSimilar && necklineBalanced && swingSpacingValid && height > 0.0) {
                out += candidate("الرأس والكتفين", "هابط", neck, neck - height, neck + height, last,
                    "قمم مرتبة زمنيًا: كتف أيسر ثم رأس أعلى ثم كتف أيمن متقارب؛ التأكيد بكسر خط الرقبة.")
            }
        }
        if (recent5.size == 5 && recent5.map { it.second } == listOf(false, true, false, true, false)) {
            val leftShoulder = data[recent5[0].first].low
            val firstNeck = data[recent5[1].first].high
            val head = data[recent5[2].first].low
            val secondNeck = data[recent5[3].first].high
            val rightShoulder = data[recent5[4].first].low
            val neck = (firstNeck + secondNeck) / 2.0
            val height = neck - head
            val shouldersSimilar = abs(leftShoulder - rightShoulder) / maxOf(leftShoulder, rightShoulder) < 0.08
            val necklineBalanced = abs(firstNeck - secondNeck) / maxOf(firstNeck, secondNeck) < 0.07
            val headDominant = head < minOf(leftShoulder, rightShoulder) * 0.975
            val swingSpacingValid = recent5.zipWithNext().all { (a, b) -> b.first - a.first >= 4 }
            if (headDominant && shouldersSimilar && necklineBalanced && swingSpacingValid && height > 0.0) {
                out += candidate("الرأس والكتفين المقلوب", "صاعد", neck, neck + height, neck - height, last,
                    "قيعان مرتبة زمنيًا: كتف أيسر ثم رأس أدنى ثم كتف أيمن متقارب؛ التأكيد باختراق خط الرقبة.")
            }
        }

        // Triple tops/bottoms require five consecutive alternating pivots.
        if (recent5.size == 5 && recent5.map { it.second } == listOf(true, false, true, false, true)) {
            val top1 = data[recent5[0].first].high
            val valley1 = data[recent5[1].first].low
            val top2 = data[recent5[2].first].high
            val valley2 = data[recent5[3].first].low
            val top3 = data[recent5[4].first].high
            val averageTop = (top1 + top2 + top3) / 3.0
            val valley = minOf(valley1, valley2)
            val height = averageTop - valley
            val topSpreadPct = (maxOf(top1, top2, top3) - minOf(top1, top2, top3)) / averageTop * 100.0
            val topSpacingValid = recent5.zipWithNext().all { (a, b) -> b.first - a.first >= 4 }
            if (height > 0.0 && topSpreadPct < 4.0 && topSpacingValid) {
                out += candidate("القمة الثلاثية", "هابط", valley, valley - height, valley + height, last,
                    "ثلاث قمم متقاربة يفصل بينها قاعان؛ التأكيد بكسر منطقة الدعم بين القمم.")
            }
        }
        if (recent5.size == 5 && recent5.map { it.second } == listOf(false, true, false, true, false)) {
            val bottom1 = data[recent5[0].first].low
            val peak1 = data[recent5[1].first].high
            val bottom2 = data[recent5[2].first].low
            val peak2 = data[recent5[3].first].high
            val bottom3 = data[recent5[4].first].low
            val averageBottom = (bottom1 + bottom2 + bottom3) / 3.0
            val peak = maxOf(peak1, peak2)
            val height = peak - averageBottom
            val bottomSpreadPct = (maxOf(bottom1, bottom2, bottom3) - minOf(bottom1, bottom2, bottom3)) / averageBottom * 100.0
            val bottomSpacingValid = recent5.zipWithNext().all { (a, b) -> b.first - a.first >= 4 }
            if (height > 0.0 && bottomSpreadPct < 4.0 && bottomSpacingValid) {
                out += candidate("القاع الثلاثي", "صاعد", peak, peak + height, peak - height, last,
                    "ثلاثة قيعان متقاربة تفصل بينها قمتان؛ التأكيد باختراق منطقة المقاومة بين القيعان.")
            }
        }
        // Cup-and-handle: validate a broad U-shaped cup, balanced rims and a shallow upper-half handle.
        if (data.size >= 55) {
            val window = data.takeLast(55)
            val cup = window.dropLast(10)
            val handle = window.takeLast(10)
            val leftSection = cup.take(12)
            val middleSection = cup.drop(12).take(21)
            val rightSection = cup.takeLast(12)
            val leftRim = leftSection.maxOf { it.high }
            val rightRim = rightSection.maxOf { it.high }
            val rim = (leftRim + rightRim) / 2.0
            val bottom = middleSection.minOf { it.low }
            val bottomIndex = middleSection.indices.minByOrNull { middleSection[it].low } ?: 10
            val depth = rim - bottom
            val leftCloseAvg = leftSection.map { it.close }.average()
            val centerCloseAvg = middleSection.map { it.close }.average()
            val rightCloseAvg = rightSection.map { it.close }.average()
            val handleLow = handle.minOf { it.low }
            val handleHigh = handle.maxOf { it.high }
            val handleRange = handleHigh - handleLow
            val rimsBalanced = rim > 0.0 && abs(leftRim - rightRim) / rim < 0.07
            val cupDepthValid = depth > rim * 0.08 && depth < rim * 0.40
            val bottomIsCentral = bottomIndex in 4..16
            val roundedShape = centerCloseAvg < leftCloseAvg && centerCloseAvg < rightCloseAvg &&
                centerCloseAvg < rim * 0.94
            val handleInUpperHalf = handleLow > bottom + depth * 0.50 && handleLow < rim * 0.99
            val handleCompact = handleRange < depth * 0.45 && handleHigh < rim * 1.04
            val handleNotRisingSharply = handle.last().close <= handle.first().close * 1.04
            if (rimsBalanced && cupDepthValid && bottomIsCentral && roundedShape &&
                handleInUpperHalf && handleCompact && handleNotRisingSharply) {
                out += candidate("الكوب والعروة", "صاعد", rim, rim + depth, handleLow, last,
                    "كوب بقاعدة واسعة وقاع مركزي وحافتين متقاربتين، تعقبه عروة ضحلة في النصف العلوي؛ التأكيد باختراق حافة الكوب.")
            }
        }
        // Wedges: use chronological swing pivots to validate both boundary slopes and convergence.
        if (data.size >= 30) {
            val startIndex = data.size - 30
            val windowPivots = alternating.filter { it.first >= startIndex }
            val swingHighs = windowPivots.filter { it.second }
            val swingLows = windowPivots.filter { !it.second }
            if (swingHighs.size >= 2 && swingLows.size >= 2) {
                val firstHigh = swingHighs.first()
                val lastHigh = swingHighs.last()
                val firstLow = swingLows.first()
                val lastLow = swingLows.last()
                val highStart = data[firstHigh.first].high
                val highEnd = data[lastHigh.first].high
                val lowStart = data[firstLow.first].low
                val lowEnd = data[lastLow.first].low
                val highSlope = (highEnd - highStart) / (lastHigh.first - firstHigh.first).toDouble()
                val lowSlope = (lowEnd - lowStart) / (lastLow.first - firstLow.first).toDouble()
                val initialWidth = highStart - lowStart
                val finalWidth = highEnd - lowEnd
                val converging = initialWidth > 0.0 && finalWidth > 0.0 &&
                    finalWidth < initialWidth * 0.82
                val enoughSwingSpacing = lastHigh.first - firstHigh.first >= 6 &&
                    lastLow.first - firstLow.first >= 6
                val risingWedge = highSlope > 0.0 && lowSlope > highSlope * 1.10
                val fallingWedge = lowSlope < 0.0 && highSlope < lowSlope * 1.10
                if (converging && enoughSwingSpacing && risingWedge) {
                    val trigger = lowEnd
                    val height = initialWidth.coerceAtLeast(last * 0.03)
                    out += candidate("الوتد الصاعد", "هابط", trigger, trigger - height, highEnd, last,
                        "قمم وقيعان محورية صاعدة مع ميل الحد السفلي أسرع من العلوي وتقلص واضح للنطاق؛ كسر الحد السفلي يؤكد النموذج.")
                } else if (converging && enoughSwingSpacing && fallingWedge) {
                    val trigger = highEnd
                    val height = initialWidth.coerceAtLeast(last * 0.03)
                    out += candidate("الوتد الهابط", "صاعد", trigger, trigger + height, lowEnd, last,
                        "قمم وقيعان محورية هابطة مع تقارب الحدين وتقلص واضح للنطاق؛ اختراق الحد العلوي يؤكد النموذج.")
                }
            }
        }
        // Range and triangle validation using contraction, horizontal-boundary tolerance and swing separation.
        val recent = data.takeLast(30)
        val firstHalf = recent.take(15)
        val secondHalf = recent.takeLast(15)
        val ceiling = recent.maxOf { it.high }
        val floor = recent.minOf { it.low }
        val range = ceiling - floor
        if (range > 0.0 && last > 0.0 && range / last < 0.22) {
            val firstHigh = firstHalf.maxOf { it.high }
            val secondHigh = secondHalf.maxOf { it.high }
            val firstLow = firstHalf.minOf { it.low }
            val secondLow = secondHalf.minOf { it.low }
            val upperContraction = firstHigh - secondHigh
            val lowerContraction = secondLow - firstLow
            val upperFlat = abs(secondHigh - firstHigh) / firstHigh < 0.025
            val lowerFlat = abs(secondLow - firstLow) / firstLow < 0.025
            val contracting = upperContraction > range * 0.04 && lowerContraction > range * 0.04
            val higherLows = secondLow > firstLow + range * 0.04
            val lowerHighs = secondHigh < firstHigh - range * 0.04
            val enoughSwings = recent.count { it.high >= ceiling * 0.985 } >= 2 &&
                recent.count { it.low <= floor * 1.015 } >= 2
            if (upperFlat && higherLows && enoughSwings) {
                out += candidate("المثلث الصاعد", "صاعد", ceiling, ceiling + range, floor - range, last,
                    "مقاومة شبه أفقية مع قيعان صاعدة متتابعة؛ الاختراق والإغلاق فوق المقاومة يؤكد النموذج.")
            } else if (lowerFlat && lowerHighs && enoughSwings) {
                out += candidate("المثلث الهابط", "هابط", floor, floor - range, ceiling + range, last,
                    "دعم شبه أفقي مع قمم هابطة متتابعة؛ الكسر والإغلاق أسفل الدعم يؤكد النموذج.")
            } else if (contracting) {
                out += candidate("المثلث المتماثل", "محايد", (ceiling + floor) / 2.0,
                    ceiling + range, floor - range, last,
                    "الحد العلوي يهبط والسفلي يصعد مع تقلص النطاق؛ اتجاه النموذج لا يتحدد إلا بالاختراق.")
            } else if (!upperFlat && !lowerFlat) {
                out += candidate("المستطيل (النطاق)", "محايد", ceiling, ceiling + range, floor - range, last,
                    "حركة عرضية بين دعم ومقاومة؛ لا يتأكد الاتجاه قبل اختراق أحد الحدين.")
            }
        }
        // Flags: require a strong directional pole and a compact, counter-trend or sideways pause.
        if (data.size >= 35) {
            val window = data.takeLast(35)
            val pole = window.take(25)
            val pause = window.takeLast(10)
            val poleStart = pole.first().close
            val poleEnd = pole.last().close
            val poleMove = abs(poleEnd - poleStart)
            val poleRange = pole.maxOf { it.high } - pole.minOf { it.low }
            val pauseHigh = pause.maxOf { it.high }
            val pauseLow = pause.minOf { it.low }
            val pauseRange = pauseHigh - pauseLow
            val pauseFirstMid = (pause.take(5).maxOf { it.high } + pause.take(5).minOf { it.low }) / 2.0
            val pauseLastMid = (pause.takeLast(5).maxOf { it.high } + pause.takeLast(5).minOf { it.low }) / 2.0
            val pauseSlope = pauseLastMid - pauseFirstMid
            val poleStrong = poleStart > 0.0 && poleMove / poleStart >= 0.08 &&
                poleRange > 0.0 && poleMove / poleRange >= 0.55
            val compactPause = poleRange > 0.0 && pauseRange / poleRange <= 0.55
            if (poleStrong && compactPause && poleEnd > poleStart &&
                pauseSlope <= pauseRange * 0.35 && pause.last().close > poleStart) {
                val trigger = pauseHigh
                out += candidate("العلم الصاعد", "صاعد", trigger, trigger + poleMove, pauseLow, last,
                    "سارية صاعدة قوية يتبعها تماسك ضيق مائل عرضيًا أو هابطًا؛ الاختراق أعلى التماسك يفعّل هدفًا يساوي طول السارية.")
            } else if (poleStrong && compactPause && poleEnd < poleStart &&
                pauseSlope >= -pauseRange * 0.35 && pause.last().close < poleStart) {
                val trigger = pauseLow
                out += candidate("العلم الهابط", "هابط", trigger, trigger - poleMove, pauseHigh, last,
                    "سارية هابطة قوية يتبعها تماسك ضيق مائل عرضيًا أو صاعدًا؛ الكسر أسفل التماسك يفعّل هدفًا يساوي طول السارية.")
            }
        }
        // Additional formations: channel, broadening, pennant and rounded bases.
        if (data.size >= 40) {
            val channel = data.takeLast(40)
            val first = channel.take(20); val second = channel.takeLast(20)
            val firstMid = (first.maxOf { it.high } + first.minOf { it.low }) / 2.0
            val secondMid = (second.maxOf { it.high } + second.minOf { it.low }) / 2.0
            val firstRange = first.maxOf { it.high } - first.minOf { it.low }
            val secondRange = second.maxOf { it.high } - second.minOf { it.low }
            val upper1 = first.maxOf { it.high }; val upper2 = second.maxOf { it.high }
            val lower1 = first.minOf { it.low }; val lower2 = second.minOf { it.low }
            val upperShift = upper2 - upper1
            val lowerShift = lower2 - lower1
            val parallelShift = firstRange > 0.0 && abs(upperShift - lowerShift) / firstRange < 0.12
            val rangeStable = firstRange > 0.0 && secondRange / firstRange in 0.65..1.35
            val channelValid = parallelShift && rangeStable &&
                abs(upperShift) / firstRange < 0.65 && abs(lowerShift) / firstRange < 0.65
            if (channelValid && secondMid > firstMid) {
                out += candidate("القناة الصاعدة","صاعد",upper2,upper2+firstRange,lower2,last,
                    "نطاقان متوازيان تقريبًا وميل متوسط صاعد؛ تجاوز سقف القناة هو مستوى التفعيل المرجعي.")
            } else if (channelValid && secondMid < firstMid) {
                out += candidate("القناة الهابطة","هابط",lower2,lower2-firstRange,upper2,last,
                    "نطاقان متوازيان تقريبًا وميل متوسط هابط؛ كسر أرضية القناة هو مستوى التفعيل المرجعي.")
            }
            val broadeningPivots = alternating.filter { it.first >= data.size - 40 }
            val broadeningHighs = broadeningPivots.filter { it.second }
            val broadeningLows = broadeningPivots.filter { !it.second }
            if (broadeningHighs.size >= 3 && broadeningLows.size >= 3) {
                val highValues = broadeningHighs.map { data[it.first].high }
                val lowValues = broadeningLows.map { data[it.first].low }
                val highsExpand = highValues.zipWithNext().count { (a, b) -> b > a * 1.002 } >= 2
                val lowsExpand = lowValues.zipWithNext().count { (a, b) -> b < a * 0.998 } >= 2
                val highSpan = broadeningHighs.last().first - broadeningHighs.first().first
                val lowSpan = broadeningLows.last().first - broadeningLows.first().first
                val pivotSpacingValid = highSpan >= 8 && lowSpan >= 8
                val pivotRange = highValues.last() - lowValues.last()
                val priorPivotRange = highValues.first() - lowValues.first()
                val expansionValid = priorPivotRange > 0.0 &&
                    pivotRange > priorPivotRange * 1.15 &&
                    secondRange > firstRange * 1.10
                if (highsExpand && lowsExpand && pivotSpacingValid && expansionValid) {
                    out += candidate("التشكيل المتسع (Broadening)", "محايد",
                        highValues.last(), highValues.last() + pivotRange,
                        lowValues.last() - pivotRange, last,
                        "ثلاث قمم محورية على الأقل تتسع لأعلى وثلاثة قيعان تتسع لأسفل مع زيادة المسافة بين الحدين؛ لا يتحدد الاتجاه إلا باختراق أو كسر واضح.")
                }
            }
        }
        if (data.size >= 35) {
            val windowStart = data.size - 35
            val window = data.takeLast(35)
            val pole = window.take(22)
            val pause = window.takeLast(13)
            val pauseStart = windowStart + 22
            val poleStart = pole.first().close
            val poleEnd = pole.last().close
            val poleMove = abs(poleEnd - poleStart)
            val pauseHigh = pause.maxOf { it.high }
            val pauseLow = pause.minOf { it.low }
            val pauseWidth = pauseHigh - pauseLow
            val pauseHighPivots = alternating.filter { it.first >= pauseStart && it.second }
            val pauseLowPivots = alternating.filter { it.first >= pauseStart && !it.second }
            val upperConverges = pauseHighPivots.size >= 2 &&
                data[pauseHighPivots.last().first].high < data[pauseHighPivots.first().first].high
            val lowerConverges = pauseLowPivots.size >= 2 &&
                data[pauseLowPivots.last().first].low > data[pauseLowPivots.first().first].low
            val pivotSpacingValid = pauseHighPivots.size >= 2 && pauseLowPivots.size >= 2 &&
                pauseHighPivots.last().first - pauseHighPivots.first().first >= 3 &&
                pauseLowPivots.last().first - pauseLowPivots.first().first >= 3
            val poleStrong = poleStart > 0.0 && poleMove / poleStart >= 0.08 &&
                poleMove / (pole.maxOf { it.high } - pole.minOf { it.low }).coerceAtLeast(poleStart * 0.001) >= 0.50
            val pennantCompact = poleMove > 0.0 && pauseWidth / poleMove in 0.08..0.55
            val pennantShape = upperConverges && lowerConverges && pivotSpacingValid &&
                pauseWidth > 0.0
            if (poleStrong && pennantCompact && pennantShape && poleEnd > poleStart &&
                pause.last().close > poleStart) {
                out += candidate("الراية الصاعدة (Pennant)", "صاعد", pauseHigh,
                    pauseHigh + poleMove, pauseLow, last,
                    "سارية صاعدة قوية يعقبها مثلث تماسك تتناقص قممه وترتفع قيعانه مع وجود نقاط ارتكاز متباعدة؛ الاختراق أعلى الراية يفعّل هدف طول السارية.")
            } else if (poleStrong && pennantCompact && pennantShape && poleEnd < poleStart &&
                pause.last().close < poleStart) {
                out += candidate("الراية الهابطة (Pennant)", "هابط", pauseLow,
                    pauseLow - poleMove, pauseHigh, last,
                    "سارية هابطة قوية يعقبها مثلث تماسك تتناقص قممه وترتفع قيعانه مع وجود نقاط ارتكاز متباعدة؛ الكسر أسفل الراية يفعّل هدف طول السارية.")
            }
        }
        // Rounded bottom/top: validate a gradual five-stage curve, central extreme and balanced rims.
        if (data.size >= 60) {
            val base = data.takeLast(60)
            val sections = (0 until 5).map { i -> base.drop(i * 12).take(12) }
            val sectionAvg = sections.map { it.map(Candle::close).average() }
            val leftRim = base.take(12).maxOf { it.high }
            val rightRim = base.takeLast(12).maxOf { it.high }
            val bottom = base.drop(12).take(36).minOf { it.low }
            val bottomIndex = base.drop(12).take(36).indices.minByOrNull { base[12 + it].low } ?: 18
            val rim = (leftRim + rightRim) / 2.0
            val depth = rim - bottom
            val rimsBalanced = rim > 0.0 && abs(leftRim - rightRim) / rim <= 0.08
            val bottomCentral = bottomIndex in 8..27
            val depthValid = depth > rim * 0.07 && depth < rim * 0.35
            val bowlCurve = sectionAvg[0] > sectionAvg[1] &&
                sectionAvg[1] > sectionAvg[2] * 0.985 &&
                sectionAvg[3] > sectionAvg[2] * 0.985 &&
                sectionAvg[4] > sectionAvg[3] &&
                sectionAvg[2] < sectionAvg[0] * 0.94 &&
                sectionAvg[2] < sectionAvg[4] * 0.94
            val recovery = sectionAvg[4] >= sectionAvg[0] * 0.88 &&
                base.takeLast(8).last().close > base.takeLast(8).first().close
            if (rimsBalanced && bottomCentral && depthValid && bowlCurve && recovery) {
                out += candidate("القاع المستدير (قاعدة صحن)", "صاعد", rim, rim + depth, bottom, last,
                    "قاع مركزي وانحناء تدريجي عبر خمس مراحل مع حافتين متقاربتين وتعافٍ واضح؛ اختراق الحافة يؤكد النموذج.")
            }

            val leftCeiling = base.take(12).minOf { it.low }
            val rightCeiling = base.takeLast(12).minOf { it.low }
            val top = base.drop(12).take(36).maxOf { it.high }
            val topIndex = base.drop(12).take(36).indices.maxByOrNull { base[12 + it].high } ?: 18
            val ceiling = (leftCeiling + rightCeiling) / 2.0
            val topHeight = top - ceiling
            val ceilingsBalanced = ceiling > 0.0 && abs(leftCeiling - rightCeiling) / ceiling <= 0.08
            val topCentral = topIndex in 8..27
            val heightValid = topHeight > ceiling * 0.07 && topHeight < ceiling * 0.35
            val domeCurve = sectionAvg[0] < sectionAvg[1] &&
                sectionAvg[1] < sectionAvg[2] * 1.015 &&
                sectionAvg[3] < sectionAvg[2] * 1.015 &&
                sectionAvg[4] < sectionAvg[3] &&
                sectionAvg[2] > sectionAvg[0] * 1.06 &&
                sectionAvg[2] > sectionAvg[4] * 1.06
            val weakening = sectionAvg[4] <= sectionAvg[0] * 1.12 &&
                base.takeLast(8).last().close < base.takeLast(8).first().close
            if (ceilingsBalanced && topCentral && heightValid && domeCurve && weakening) {
                out += candidate("القمة المستديرة", "هابط", ceiling, ceiling - topHeight, top, last,
                    "قمة مركزية وانحناء تدريجي عبر خمس مراحل مع قاعين جانبيين متقاربين وبدء ضعف؛ كسر خط الدعم يؤكد النموذج.")
            }
        }
        if (data.size >= 30) {
            val w=data.takeLast(30); val early=w.take(15); val late=w.takeLast(15)
            val eH=early.maxOf{it.high}; val eL=early.minOf{it.low}; val lH=late.maxOf{it.high}; val lL=late.minOf{it.low}
            if (eH>0 && eL>0 && lH>eH*1.02 && lL>eL*1.02 && (lH-lL)>(eH-eL)*1.10) {
                out += candidate("التشكيل المتسع الصاعد","صاعد",lH,lH+(lH-lL),lL,last,
                    "اتساع نطاق مع تحرك الحدود لأعلى؛ اختراق أعلى الحد الأخير يحتاج تأكيد.")
            } else if (eH>0 && lH<eH*.98 && lL<eL*.98 && (lH-lL)>(eH-eL)*1.10) {
                out += candidate("التشكيل المتسع الهابط","هابط",lL,lL-(lH-lL),lH,last,
                    "اتساع نطاق مع تحرك الحدود لأسفل؛ كسر أدنى الحد الأخير يحتاج تأكيد.")
            }
        }
        // Diamond reversals: require expanding then contracting swing boundaries and a prior trend.
        if (data.size >= 50) {
            val d = data.takeLast(50)
            val offset = data.size - d.size
            val diamondPivots = alternating.filter { it.first >= offset }
            val pivotHighs = diamondPivots.filter { it.second }
            val pivotLows = diamondPivots.filter { !it.second }
            if (pivotHighs.size >= 3 && pivotLows.size >= 3) {
                val firstHighs = pivotHighs.take(2).map { data[it.first].high }
                val lastHighs = pivotHighs.takeLast(2).map { data[it.first].high }
                val firstLows = pivotLows.take(2).map { data[it.first].low }
                val lastLows = pivotLows.takeLast(2).map { data[it.first].low }
                val highExpansion = firstHighs.last() > firstHighs.first() * 1.005
                val lowExpansion = firstLows.last() < firstLows.first() * 0.995
                val highContraction = lastHighs.last() < lastHighs.first() * 0.995
                val lowContraction = lastLows.last() > lastLows.first() * 1.005
                val firstRange = d.take(18).let { it.maxOf { c -> c.high } - it.minOf { c -> c.low } }
                val middleRange = d.drop(12).take(26).let { it.maxOf { c -> c.high } - it.minOf { c -> c.low } }
                val finalRange = d.takeLast(12).let { it.maxOf { c -> c.high } - it.minOf { c -> c.low } }
                val rangeShape = firstRange > 0.0 && middleRange > firstRange * 1.10 &&
                    finalRange < middleRange * 0.82
                val enoughSpacing = pivotHighs.last().first - pivotHighs.first().first >= 12 &&
                    pivotLows.last().first - pivotLows.first().first >= 12
                val highValues = pivotHighs.map { data[it.first].high }
                val lowValues = pivotLows.map { data[it.first].low }
                val expandedThenContracted = highExpansion && lowExpansion &&
                    highContraction && lowContraction &&
                    highValues.maxOrNull()!! > firstHighs.maxOrNull()!! * 1.015 &&
                    lowValues.minOrNull()!! < firstLows.minOrNull()!! * 0.985
                val prior = d.take(12)
                val priorUp = prior.last().close > prior.first().close * 1.04
                val priorDown = prior.last().close < prior.first().close * 0.96
                val shapeValid = rangeShape && enoughSpacing && expandedThenContracted
                if (shapeValid && priorUp) {
                    val trigger = d.takeLast(12).minOf { it.low }
                    val invalidation = d.takeLast(12).maxOf { it.high }
                    val height = (middleRange * 0.70).coerceAtLeast(last * 0.025)
                    out += candidate("الماسية القمة (Diamond Top)", "هابط", trigger,
                        trigger - height, invalidation, last,
                        "اتساع موثق بقمم أعلى وقيعان أدنى ثم انكماش بقمم أدنى وقيعان أعلى بعد اتجاه صاعد؛ كسر قاع التكوين يؤكد الانعكاس.")
                } else if (shapeValid && priorDown) {
                    val trigger = d.takeLast(12).maxOf { it.high }
                    val invalidation = d.takeLast(12).minOf { it.low }
                    val height = (middleRange * 0.70).coerceAtLeast(last * 0.025)
                    out += candidate("الماسية القاع (Diamond Bottom)", "صاعد", trigger,
                        trigger + height, invalidation, last,
                        "اتساع موثق بقمم أعلى وقيعان أدنى ثم انكماش بقمم أدنى وقيعان أعلى بعد اتجاه هابط؛ اختراق قمة التكوين يؤكد الانعكاس.")
                }
            }
        }
        // Measured Move: require a clear impulse, a controlled retracement, and a lower/higher correction pivot.
        if (alternating.size >= 4) {
            val q = alternating.takeLast(4)
            val values = q.map { if (it.second) data[it.first].high else data[it.first].low }
            val spacingValid = q.zipWithNext().all { (a, b) -> b.first - a.first >= 3 }
            val bullishShape = q.map { it.second } == listOf(false, true, false, true)
            val bearishShape = q.map { it.second } == listOf(true, false, true, false)
            if (spacingValid && bullishShape) {
                val a = values[0]; val b = values[1]; val c = values[2]; val d = values[3]
                val impulse = b - a
                val retracement = if (impulse > 0.0) (b - c) / impulse else 0.0
                val correctionRecovery = if (impulse > 0.0) (d - c) / impulse else 0.0
                val impulseDuration = q[1].first - q[0].first
                val correctionDuration = q[3].first - q[2].first
                val validImpulse = impulse > last * 0.06 && impulseDuration >= 4
                val validCorrection = retracement in 0.25..0.65 &&
                    correctionRecovery in 0.15..0.75 && c > a && d < b
                val balancedTiming = correctionDuration >= 3 &&
                    correctionDuration <= impulseDuration * 2
                if (validImpulse && validCorrection && balancedTiming) {
                    out += candidate("الحركة المقاسة الصاعدة (Measured Move)", "صاعد", b,
                        b + impulse, c, last,
                        "موجة اندفاع صاعدة واضحة من A إلى B، يتبعها تصحيح مضبوط إلى C وارتداد تكويني إلى D؛ تجاوز B يؤكد استكمال الحركة، والهدف إسقاط طول AB.")
                }
            } else if (spacingValid && bearishShape) {
                val a = values[0]; val b = values[1]; val c = values[2]; val d = values[3]
                val impulse = a - b
                val retracement = if (impulse > 0.0) (c - b) / impulse else 0.0
                val correctionDecline = if (impulse > 0.0) (c - d) / impulse else 0.0
                val impulseDuration = q[1].first - q[0].first
                val correctionDuration = q[3].first - q[2].first
                val validImpulse = impulse > last * 0.06 && impulseDuration >= 4
                val validCorrection = retracement in 0.25..0.65 &&
                    correctionDecline in 0.15..0.75 && c < a && d > b
                val balancedTiming = correctionDuration >= 3 &&
                    correctionDuration <= impulseDuration * 2
                if (validImpulse && validCorrection && balancedTiming) {
                    out += candidate("الحركة المقاسة الهابطة (Measured Move)", "هابط", b,
                        b - impulse, c, last,
                        "موجة اندفاع هابطة واضحة من A إلى B، يتبعها ارتداد تصحيحي إلى C ثم عودة هابطة إلى D؛ كسر B يؤكد استكمال الحركة، والهدف إسقاط طول AB.")
                }
            }
        }
        if (data.size >= 45) {
            val w = data.takeLast(45)
            val first = w.take(22)
            val second = w.takeLast(23)
            val firstVol = first.map { it.volume }.average()
            val secondVol = second.map { it.volume }.average()
            val range = w.maxOf { it.high } - w.minOf { it.low }
            // A quiet consolidation with a breakout-shaped range expansion candidate.
            if (firstVol > 0.0 && secondVol > 0.0 && secondVol < firstVol * 1.35 && range > last * 0.025) {
                val localHigh = second.maxOf { it.high }
                val localLow = second.minOf { it.low }
                if (last > localHigh) out += candidate("اختراق نطاق التماسك", "صاعد", localHigh,
                    localHigh + range * 0.65, localLow, last,
                    "تماسك نسبي ثم تجاوز سقف الجزء الأخير؛ يفضّل تأكيد الاختراق بالحجم.")
                else if (last < localLow) out += candidate("كسر نطاق التماسك", "هابط", localLow,
                    localLow - range * 0.65, localHigh, last,
                    "تماسك نسبي ثم كسر أرضية الجزء الأخير؛ يفضّل تأكيد الكسر بالحجم.")
            }
        }
        // Adam/Eve formations: use three consecutive alternating pivots and local shape.
        val adamEvePivots = alternating.takeLast(3)
        if (adamEvePivots.size == 3 &&
            adamEvePivots[0].second && !adamEvePivots[1].second && adamEvePivots[2].second) {
            val firstIndex = adamEvePivots[0].first
            val valleyIndex = adamEvePivots[1].first
            val secondIndex = adamEvePivots[2].first
            val firstTop = data[firstIndex].high
            val valley = data[valleyIndex].low
            val secondTop = data[secondIndex].high
            val averageTop = (firstTop + secondTop) / 2.0
            val height = averageTop - valley
            val firstShapeCount = (maxOf(0, firstIndex - 3)..minOf(data.lastIndex, firstIndex + 3))
                .count { data[it].high >= firstTop * 0.99 }
            val secondShapeCount = (maxOf(0, secondIndex - 3)..minOf(data.lastIndex, secondIndex + 3))
                .count { data[it].high >= secondTop * 0.99 }
            val firstIsAdam = firstShapeCount <= 2
            val secondIsAdam = secondShapeCount <= 2
            if (secondIndex - firstIndex in 5..35 &&
                abs(firstTop - secondTop) / maxOf(firstTop, secondTop) < 0.055 &&
                height > averageTop * 0.025) {
                val name = when {
                    firstIsAdam && !secondIsAdam -> "قمة آدم وحواء (Adam & Eve Top)"
                    !firstIsAdam && secondIsAdam -> "قمة حواء وآدم (Eve & Adam Top)"
                    else -> "قمة آدم وحواء مزدوجة (Adam/Eve Double Top)"
                }
                out += candidate(name, "هابط", valley, valley - height, valley + height, last,
                    "قمتان متقاربتان يفصل بينهما قاع واضح؛ تم تمييز القمة الحادة (آدم) من المستديرة (حواء). التأكيد بكسر القاع الفاصل.")
            }
        }
        if (adamEvePivots.size == 3 &&
            !adamEvePivots[0].second && adamEvePivots[1].second && !adamEvePivots[2].second) {
            val firstIndex = adamEvePivots[0].first
            val peakIndex = adamEvePivots[1].first
            val secondIndex = adamEvePivots[2].first
            val firstBottom = data[firstIndex].low
            val peak = data[peakIndex].high
            val secondBottom = data[secondIndex].low
            val averageBottom = (firstBottom + secondBottom) / 2.0
            val height = peak - averageBottom
            val firstShapeCount = (maxOf(0, firstIndex - 3)..minOf(data.lastIndex, firstIndex + 3))
                .count { data[it].low <= firstBottom * 1.01 }
            val secondShapeCount = (maxOf(0, secondIndex - 3)..minOf(data.lastIndex, secondIndex + 3))
                .count { data[it].low <= secondBottom * 1.01 }
            val firstIsAdam = firstShapeCount <= 2
            val secondIsAdam = secondShapeCount <= 2
            if (secondIndex - firstIndex in 5..35 &&
                abs(firstBottom - secondBottom) / minOf(firstBottom, secondBottom) < 0.055 &&
                height > averageBottom * 0.025) {
                val name = when {
                    firstIsAdam && !secondIsAdam -> "قاع آدم وحواء (Adam & Eve Bottom)"
                    !firstIsAdam && secondIsAdam -> "قاع حواء وآدم (Eve & Adam Bottom)"
                    else -> "قاع آدم وحواء مزدوج (Adam/Eve Double Bottom)"
                }
                out += candidate(name, "صاعد", peak, peak + height, peak - height, last,
                    "قاعان متقاربان تفصل بينهما قمة واضحة؛ تم تمييز القاع الحاد (آدم) من المستدير (حواء). التأكيد باختراق القمة الفاصلة.")
            }
        }
        // V-shaped reversal approximations, validated by a strong turn from the extreme.
        if (data.size >= 25) {
            val v=data.takeLast(25); val mid=12
            val lowIndex=v.indices.minByOrNull{v[it].low} ?: mid
            val low=v[lowIndex].low; val leftHigh=v.take(lowIndex).maxOfOrNull{it.high} ?: low
            val rightHigh=v.drop(lowIndex+1).maxOfOrNull{it.high} ?: low
            if (lowIndex in 5..19 && leftHigh>low*1.08 && last>low*1.08 && last>v.takeLast(5).first().close) {
                val trigger=maxOf(leftHigh,rightHigh); val height=trigger-low
                out += candidate("القاع حرف V (V Bottom)","صاعد",trigger,trigger+height,low,last,
                    "هبوط حاد ثم تعافٍ سريع من قاع محوري؛ تأكيد عند تجاوز المقاومة المحددة.")
            }
            val highIndex=v.indices.maxByOrNull{v[it].high} ?: mid
            val top=v[highIndex].high; val leftLow=v.take(highIndex).minOfOrNull{it.low} ?: top
            val rightLow=v.drop(highIndex+1).minOfOrNull{it.low} ?: top
            if (highIndex in 5..19 && top>leftLow*1.08 && top>rightLow*1.08 && last<top*.92) {
                val trigger=minOf(leftLow,rightLow); val height=top-trigger
                out += candidate("القمة حرف V المقلوب (Inverted V)","هابط",trigger,trigger-height,top,last,
                    "صعود حاد ثم انعكاس سريع من قمة محورية؛ تأكيد عند كسر الدعم المحدد.")
            }
        }
        // Pipe bottom/top: two strong adjacent candles near a local extreme.
        if (data.size >= 30) {
            val p=data.takeLast(30); val a=p[p.size-2]; val b=p.last()
            val bodyA=abs(a.close-a.open); val bodyB=abs(b.close-b.open)
            val avgRange=p.map{it.high-it.low}.average().coerceAtLeast(last*.001)
            val priorLow=p.dropLast(2).minOf{it.low}; val priorHigh=p.dropLast(2).maxOf{it.high}
            if (a.close<a.open && b.close>b.open && bodyA>avgRange*1.4 && bodyB>avgRange*1.4 && minOf(a.low,b.low)<=priorLow*1.03) {
                val trigger=maxOf(a.high,b.high); val height=trigger-minOf(a.low,b.low)
                out += candidate("Pipe Bottom","صاعد",trigger,trigger+height,minOf(a.low,b.low),last,
                    "شمعتان كبيرتان متعاكستان قرب قاع نطاق؛ الاختراق فوق قمتهما يؤكد بصورة أولية.")
            }
            if (a.close>a.open && b.close<b.open && bodyA>avgRange*1.4 && bodyB>avgRange*1.4 && maxOf(a.high,b.high)>=priorHigh*.97) {
                val trigger=minOf(a.low,b.low); val height=maxOf(a.high,b.high)-trigger
                out += candidate("Pipe Top","هابط",trigger,trigger-height,maxOf(a.high,b.high),last,
                    "شمعتان كبيرتان متعاكستان قرب قمة نطاق؛ الكسر أسفل قاعهما يؤكد بصورة أولية.")
            }
        }
        // Three Drives: validate both the three impulse extremes and the intervening reaction pivots.
        if (alternating.size >= 6) {
            val q = alternating.takeLast(6)
            val vals = q.map { if (it.second) data[it.first].high else data[it.first].low }
            val drive1 = abs(vals[1] - vals[0])
            val drive2 = abs(vals[3] - vals[2])
            val drive3 = abs(vals[5] - vals[4])
            val isTop = q.map { it.second } == listOf(false, true, false, true, false, true)
            val isBottom = q.map { it.second } == listOf(true, false, true, false, true, false)
            val similarDrives = minOf(drive1, drive2, drive3) > last * .025 &&
                maxOf(drive1, drive2, drive3) / minOf(drive1, drive2, drive3) < 2.2
            val validTop = isTop && vals[3] > vals[1] && vals[5] > vals[3] &&
                vals[2] > vals[0] && vals[4] > vals[2]
            val validBottom = isBottom && vals[1] < vals[3] && vals[3] < vals[5] &&
                vals[2] < vals[0] && vals[4] < vals[2]
            if (similarDrives && validTop) {
                val trigger = vals[4]
                out += candidate("Three Drives — قمة", "هابط", trigger, trigger - drive3,
                    vals[5] + drive3 * .25, last,
                    "ثلاث دفعات صاعدة مع قيعان ارتداد أعلى؛ كسر قاع الارتداد الأخير يؤكد النموذج بصورة أولية.")
            } else if (similarDrives && validBottom) {
                val trigger = vals[3]
                out += candidate("Three Drives — قاع", "صاعد", trigger, trigger + drive3,
                    vals[4] - drive3 * .25, last,
                    "ثلاث دفعات هابطة مع قمم ارتداد أدنى؛ اختراق قمة الارتداد الأخيرة يؤكد النموذج بصورة أولية.")
            }
        }
        // ABCD geometric approximation: similar AB and CD legs after alternating swing points.
        if (alternating.size >= 4) {
            val q=alternating.takeLast(4)
            val v=q.map{if(it.second)data[it.first].high else data[it.first].low}
            val ab=abs(v[1]-v[0]); val cd=abs(v[3]-v[2])
            val sameDirection=(v[1]-v[0])*(v[3]-v[2])>0
            val bullish = v[0] > v[1] && v[2] > v[3]
            val bearish = v[0] < v[1] && v[2] < v[3]
            if (ab>last*.03 && cd>last*.025 && abs(ab-cd)/maxOf(ab,cd)<.35 &&
                sameDirection && (bullish || bearish)) {
                // C is the structural confirmation level; D is the projected completion/reversal area.
                val trigger = v[2]
                val target = if (bullish) v[3] + ab else v[3] - ab
                val failure = if (bullish) v[3] - cd*.35 else v[3] + cd*.35
                out += candidate("ABCD (تقريب هندسي)",if (bullish) "صاعد" else "هابط",
                    trigger,target,failure,last,
                    "أربع نقاط متعاقبة بأطوال موجية متقاربة؛ الاتجاه يحدده ترتيب A-B-C-D، والتأكيد باختراق C في النموذج الصاعد أو كسره في الهابط.")
            }
        }

        // Island reversals: require two meaningful separating gaps and a preceding trend.
        if (data.size >= 18) {
            val w = data.takeLast(18)
            for (i in 5 until w.size - 4) {
                val island = w.subList(i - 2, i + 2)
                val islandHigh = island.maxOf { it.high }
                val islandLow = island.minOf { it.low }
                val islandRange = islandHigh - islandLow
                val leftDownGap = w[i - 2].low - w[i - 3].high
                val rightDownGap = w[i + 1].low - w[i + 2].high
                val leftUpGap = w[i - 3].low - w[i - 2].high
                val rightUpGap = w[i + 2].low - w[i + 1].high
                val gapFloor = last * 0.001
                val meaningfulIsland = islandRange > 0.0 && islandRange < last * 0.12
                val prior = w.subList(i - 5, i - 2)
                val priorWasRising = prior.last().close > prior.first().close * 1.015
                val priorWasFalling = prior.last().close < prior.first().close * 0.985
                val bearishIsland = leftUpGap > gapFloor && rightDownGap > gapFloor &&
                    meaningfulIsland && priorWasRising
                val bullishIsland = leftDownGap > gapFloor && rightUpGap > gapFloor &&
                    meaningfulIsland && priorWasFalling
                if (bearishIsland) {
                    val trigger = islandLow
                    out += candidate("الجزيرة الانعكاسية الهابطة (Island)", "هابط",
                        trigger, trigger - islandRange, islandHigh, last,
                        "فجوة صاعدة ثم جزيرة سعرية صغيرة وفجوة هابطة، بعد اتجاه صاعد سابق؛ كسر قاع الجزيرة شرط التأكيد.")
                }
                if (bullishIsland) {
                    val trigger = islandHigh
                    out += candidate("الجزيرة الانعكاسية الصاعدة (Island)", "صاعد",
                        trigger, trigger + islandRange, islandLow, last,
                        "فجوة هابطة ثم جزيرة سعرية صغيرة وفجوة صاعدة، بعد اتجاه هابط سابق؛ اختراق قمة الجزيرة شرط التأكيد.")
                }
            }
        }
        // Spring / Upthrust: false boundary break plus a directional rejection candle closing back inside.
        if (data.size >= 25) {
            val w = data.takeLast(25)
            val prior = w.dropLast(3)
            val tail = w.takeLast(3)
            val support = prior.minOf { it.low }
            val resistance = prior.maxOf { it.high }
            val reclaim = tail.last()
            val candleRange = (reclaim.high - reclaim.low).coerceAtLeast(last * 0.001)
            val closePosition = (reclaim.close - reclaim.low) / candleRange
            val spring = tail.minOf { it.low } < support * 0.995 &&
                reclaim.close > support && reclaim.close > reclaim.open && closePosition >= 0.60
            val upthrust = tail.maxOf { it.high } > resistance * 1.005 &&
                reclaim.close < resistance && reclaim.close < reclaim.open && closePosition <= 0.40
            val range = resistance - support
            if (spring && range > 0) out += candidate("Spring (اختراق دعم وهمي)", "صاعد",
                support, support + range * 0.65, tail.minOf { it.low }, last,
                "كسر مؤقت للدعم ثم استرداده بشمعة صاعدة أغلقت قرب أعلى نطاقها؛ يظل الحجم والسياق مهمين.")
            if (upthrust && range > 0) out += candidate("Upthrust (اختراق مقاومة وهمي)", "هابط",
                resistance, resistance - range * 0.65, tail.maxOf { it.high }, last,
                "اختراق مؤقت للمقاومة ثم رفضها بشمعة هابطة أغلقت قرب أدنى نطاقها؛ يظل الحجم والسياق مهمين.")
        }
        // Inverted cup-and-handle: broad rounded ceiling, balanced support rims and a shallow rebound handle.
        if (data.size >= 55) {
            val window = data.takeLast(55)
            val cup = window.dropLast(10)
            val handle = window.takeLast(10)
            val leftSection = cup.take(12)
            val middleSection = cup.drop(12).take(21)
            val rightSection = cup.takeLast(12)
            val leftSupport = leftSection.minOf { it.low }
            val rightSupport = rightSection.minOf { it.low }
            val support = (leftSupport + rightSupport) / 2.0
            val top = middleSection.maxOf { it.high }
            val topIndex = middleSection.indices.maxByOrNull { middleSection[it].high } ?: 10
            val depth = top - support
            val leftCloseAvg = leftSection.map { it.close }.average()
            val centerCloseAvg = middleSection.map { it.close }.average()
            val rightCloseAvg = rightSection.map { it.close }.average()
            val handleHigh = handle.maxOf { it.high }
            val handleLow = handle.minOf { it.low }
            val handleRange = handleHigh - handleLow
            val supportsBalanced = support > 0.0 && abs(leftSupport - rightSupport) / support < 0.07
            val cupDepthValid = depth > support * 0.08 && depth < support * 0.40
            val topIsCentral = topIndex in 4..16
            val roundedShape = centerCloseAvg > leftCloseAvg && centerCloseAvg > rightCloseAvg &&
                centerCloseAvg > support * 1.06
            val handleInLowerHalf = handleHigh < support + depth * 0.50 && handleHigh > support * 1.01
            val handleCompact = handleRange < depth * 0.45 && handleLow > support * 0.97
            val handleNotFallingSharply = handle.last().close >= handle.first().close * 0.96
            if (supportsBalanced && cupDepthValid && topIsCentral && roundedShape &&
                handleInLowerHalf && handleCompact && handleNotFallingSharply) {
                out += candidate("الكوب والعروة المقلوب", "هابط", support, support - depth, handleHigh, last,
                    "قمة مقوسة عريضة بقاعين جانبيين متقاربين، تعقبها عروة ارتدادية ضحلة في النصف السفلي؛ التأكيد بكسر دعم حافتي النموذج.")
            }
        }
        // Bump-and-Run: gradual lead-in, accelerated bump, then a confirmed support break.
        if (data.size >= 60 && last > 0.0) {
            val w = data.takeLast(60)
            val lead = w.take(25)
            val bump = w.drop(25).take(15)
            val decline = w.takeLast(20)
            val leadStart = lead.first().close
            val leadEnd = lead.last().close
            val bumpStart = bump.first().close
            val peakIndex = bump.indices.maxByOrNull { bump[it].high } ?: 0
            val peak = bump[peakIndex].high
            val leadPct = if (leadStart > 0.0) (leadEnd - leadStart) / leadStart * 100.0 else 0.0
            val bumpPct = if (bumpStart > 0.0) (peak - bumpStart) / bumpStart * 100.0 else 0.0
            val leadSlope = leadPct / 25.0
            val bumpSlope = bumpPct / 15.0
            val postPeak = if (peakIndex < bump.lastIndex) bump.drop(peakIndex + 1) + decline else decline
            val support = (bump.takeLast(5) + decline.take(10)).minOf { it.low }
            val drawdownPct = if (peak > 0.0) (peak - decline.last().close) / peak * 100.0 else 0.0
            val breakdown = decline.last().close < support
            val accelerated = bumpSlope > leadSlope * 1.35 && bumpPct > 5.0
            val orderlyLeadIn = leadPct in 4.0..25.0 && leadEnd >= leadStart
            val peakNearBumpEnd = peakIndex >= 5
            val postPeakWeakness = postPeak.isNotEmpty() &&
                postPeak.last().close < peak * 0.96 &&
                decline.last().close < decline.first().close * 0.96
            if (orderlyLeadIn && accelerated && peakNearBumpEnd &&
                drawdownPct >= 6.0 && postPeakWeakness && breakdown) {
                val trigger = support
                val height = (peak - leadEnd).coerceAtLeast(peak * 0.04)
                out += candidate("Bump-and-Run (انعكاس هابط)", "هابط", trigger,
                    trigger - height, peak, last,
                    "اتجاه تمهيدي صاعد يعقبه تسارع واضح في الصعود (Bump)، ثم تراجع يكسر دعم منطقة التكوين؛ الهدف النظري يُقاس من ارتفاع مرحلة الاندفاع، والفشل أعلى القمة.")
            }
        }
        // Broadening formations: require repeated expanding swing highs and lows, not just wider half-window ranges.
        if (data.size >= 35) {
            val startIndex = data.size - 35
            val formationPivots = alternating.filter { it.first >= startIndex }
            val formationHighs = formationPivots.filter { it.second }
            val formationLows = formationPivots.filter { !it.second }
            if (formationHighs.size >= 3 && formationLows.size >= 3) {
                val highValues = formationHighs.map { data[it.first].high }
                val lowValues = formationLows.map { data[it.first].low }
                val higherHighCount = highValues.zipWithNext().count { (a, b) -> b > a * 1.002 }
                val lowerLowCount = lowValues.zipWithNext().count { (a, b) -> b < a * 0.998 }
                val highSpan = formationHighs.last().first - formationHighs.first().first
                val lowSpan = formationLows.last().first - formationLows.first().first
                val firstWidth = highValues.first() - lowValues.first()
                val finalWidth = highValues.last() - lowValues.last()
                val expandsOutward = higherHighCount >= 2 && lowerLowCount >= 2 &&
                    highSpan >= 8 && lowSpan >= 8 && firstWidth > 0.0 &&
                    finalWidth > firstWidth * 1.15
                if (expandsOutward) {
                    val upper = highValues.last()
                    val lower = lowValues.last()
                    val width = finalWidth
                    val firstMid = (highValues.first() + lowValues.first()) / 2.0
                    val lastMid = (upper + lower) / 2.0
                    if (lastMid > firstMid * 1.01) {
                        out += candidate("الوتد المتسع الصاعد (Broadening Wedge)", "صاعد",
                            upper, upper + width, lower, last,
                            "قمم محورية أعلى وقيعان أدنى متتابعة مع اتساع الحدود وميل متوسط صاعد؛ اختراق آخر قمة يؤكد النموذج.")
                    } else if (lastMid < firstMid * 0.99) {
                        out += candidate("الوتد المتسع الهابط (Broadening Wedge)", "هابط",
                            lower, lower - width, upper, last,
                            "قمم محورية أعلى وقيعان أدنى متتابعة مع اتساع الحدود وميل متوسط هابط؛ كسر آخر قاع يؤكد النموذج.")
                    }
                }
            }
        }

        return out.distinctBy { it.name }.sortedWith(
            compareByDescending<ClassicPatternResult> { it.confidence }
                .thenByDescending { it.bias != "محايد" }
        ).take(50)
    }

    private fun candidate(name:String,bias:String,trigger:Double,targetSuccess:Double,targetFailure:Double,last:Double,explanation:String):ClassicPatternResult {
        val confirmed=when(bias) { "صاعد" -> last>trigger; "هابط" -> last<trigger; else -> false }
        val distancePct = if (trigger > 0.0) abs(last-trigger)/trigger*100.0 else 100.0
        val base = when {
            bias == "محايد" -> 42
            confirmed -> 72
            else -> 54
        }
        // Proximity-sensitive score: far-away unconfirmed setups are penalized,
        // while a modest confirmed move is preferred to an excessively extended breakout.
        val distancePenalty = (distancePct*1.6).toInt().coerceAtMost(24)
        val extensionPenalty = if (confirmed && distancePct > 8.0) ((distancePct-8.0)*1.2).toInt().coerceAtMost(12) else 0
        val confidence = (base-distancePenalty-extensionPenalty).coerceIn(20, 88)
        val qualityLabel = when {
            bias == "محايد" -> "محايد — يحتاج اتجاهًا"
            confirmed && distancePct <= 3.0 -> "تأكيد قريب من مستوى التفعيل"
            confirmed && distancePct <= 8.0 -> "تأكيد مبدئي — امتداد معتدل"
            confirmed -> "تأكيد مبدئي — السعر ممتد"
            distancePct <= 3.0 -> "قريب من مستوى التفعيل"
            else -> "بعيد عن مستوى التفعيل"
        }
        val status = when {
            bias == "محايد" -> "قيد التشكّل — بانتظار الاختراق"
            confirmed -> qualityLabel
            else -> "قيد التشكّل — غير مؤكد ($qualityLabel)"
        }
        val baseReason = when {
            bias == "محايد" -> "النموذج محايد الاتجاه (أساس 42)"
            confirmed -> "تجاوز السعر مستوى التفعيل مبدئيًا (أساس 72)"
            else -> "لم يتجاوز السعر مستوى التفعيل بعد (أساس 54)"
        }
        val distanceReason = "المسافة من مستوى التفعيل ${String.format(java.util.Locale.US, "%.2f", distancePct)}%، خصم المسافة ${distancePenalty} نقطة، وخصم الامتداد ${extensionPenalty} نقطة"
        val details = "$baseReason؛ $distanceReason؛ الحالة: $qualityLabel. الدرجة تقدير heuristic وليست احتمال نجاح إحصائيًا."
        // Keep invalidation separate from the opposite measured-move projection.
        // Use a directionally valid reference and limit extreme projection distances
        // so the invalidation marker remains a nearby reference, not a remote target.
        val invalidation = when (bias) {
            "صاعد" -> if (targetFailure > 0.0 && targetFailure < trigger)
                maxOf(targetFailure, trigger * 0.85) else trigger * 0.92
            "هابط" -> if (targetFailure > trigger)
                minOf(targetFailure, trigger * 1.15) else trigger * 1.08
            else -> targetFailure
        }
        return ClassicPatternResult(name,bias,status,trigger,targetSuccess,targetFailure,invalidation,explanation,confidence,details)
    }
}
