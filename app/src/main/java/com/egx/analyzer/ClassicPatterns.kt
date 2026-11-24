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
        // Wedge approximation from contracting recent range and sloping boundaries.
        if (data.size >= 30) {
            val w=data.takeLast(30); val first=w.take(15); val second=w.takeLast(15)
            val firstH=first.maxOf{it.high}; val secondH=second.maxOf{it.high}
            val firstL=first.minOf{it.low}; val secondL=second.minOf{it.low}
            val contracting=(secondH-secondL)<(firstH-firstL)*.82
            if (contracting && secondH<firstH && secondL>firstL) {
                val trigger=secondH; val height=(firstH-firstL).coerceAtLeast(last*.03)
                out += candidate("الوتد الهابط","صاعد",trigger,trigger+height,secondL,last,
                    "حدود الحركة تتقارب مع ميل هابط؛ الاختراق أعلى الحد العلوي إشارة محتملة.")
            } else if (contracting && secondH>firstH && secondL<firstL) {
                val trigger=secondL; val height=(firstH-firstL).coerceAtLeast(last*.03)
                out += candidate("الوتد الصاعد","هابط",trigger,trigger-height,secondH,last,
                    "حدود الحركة تتقارب مع ميل صاعد؛ الكسر أسفل الحد السفلي إشارة محتملة.")
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
            if (secondRange > firstRange*1.15 && upper2>upper1 && lower2<lower1) {
                out += candidate("التشكيل المتسع (Broadening)","محايد",upper2,upper2+secondRange,lower2-secondRange,last,
                    "قمم أعلى وقيعان أدنى مع اتساع الحركة؛ اتجاه التأكيد يتحدد بكسر أحد الحدود.")
            }
        }
        if (data.size >= 35) {
            val window = data.takeLast(35)
            val pole = window.take(22)
            val pause = window.takeLast(13)
            val poleStart = pole.first().close
            val poleEnd = pole.last().close
            val poleMove = abs(poleEnd - poleStart)
            val pauseHigh = pause.maxOf { it.high }
            val pauseLow = pause.minOf { it.low }
            val pauseWidth = pauseHigh - pauseLow
            val firstHigh = pause.take(6).maxOf { it.high }
            val lastHigh = pause.takeLast(6).maxOf { it.high }
            val firstLow = pause.take(6).minOf { it.low }
            val lastLow = pause.takeLast(6).minOf { it.low }
            val upperConverges = lastHigh < firstHigh - pauseWidth * 0.04
            val lowerConverges = lastLow > firstLow + pauseWidth * 0.04
            val poleStrong = poleStart > 0.0 && poleMove / poleStart >= 0.08
            val pennantCompact = poleMove > 0.0 && pauseWidth / poleMove <= 0.55
            val pennantShape = upperConverges && lowerConverges
            if (poleStrong && pennantCompact && pennantShape && poleEnd > poleStart &&
                pause.last().close > poleStart) {
                out += candidate("الراية الصاعدة (Pennant)", "صاعد", pauseHigh,
                    pauseHigh + poleMove, pauseLow, last,
                    "سارية صاعدة قوية يعقبها تقارب واضح للحدين العلوي والسفلي؛ الاختراق أعلى الراية يفعّل هدف طول السارية.")
            } else if (poleStrong && pennantCompact && pennantShape && poleEnd < poleStart &&
                pause.last().close < poleStart) {
                out += candidate("الراية الهابطة (Pennant)", "هابط", pauseLow,
                    pauseLow - poleMove, pauseHigh, last,
                    "سارية هابطة قوية يعقبها تقارب واضح للحدين العلوي والسفلي؛ الكسر أسفل الراية يفعّل هدف طول السارية.")
            }
        }
        if (data.size >= 60) {
            val base=data.takeLast(60); val left=base.take(15); val mid=base.drop(15).take(30); val right=base.takeLast(15)
            val leftHigh=left.maxOf{it.high}; val rightHigh=right.maxOf{it.high}; val bottom=mid.minOf{it.low}
            val rim=(leftHigh+rightHigh)/2
            val midClose=mid.map{it.close}.average()
            if (rim>bottom && abs(leftHigh-rightHigh)/rim<.15 && midClose<rim*.92 && right.last().close>right.first().close) {
                out += candidate("القاع المستدير (قاعدة صحن)","صاعد",rim,rim+(rim-bottom),bottom,last,
                    "قاعدة طويلة بملامح تقوس وتعافٍ في الجزء الأخير؛ الاختراق فوق الحافة شرط التأكيد.")
            }
            val leftLow=left.minOf{it.low}; val rightLow=right.minOf{it.low}; val top=mid.maxOf{it.high}
            val ceiling=(leftLow+rightLow)/2
            if (top>ceiling && abs(leftLow-rightLow)/ceiling<.15 && midClose>ceiling*1.08 && right.last().close<right.first().close) {
                out += candidate("القمة المستديرة","هابط",ceiling,ceiling-(top-ceiling),top,last,
                    "تشكيل قمة طويلة بملامح تقوس وبدء ضعف في الجزء الأخير؛ كسر الدعم شرط التأكيد.")
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
        // Further classic formations: diamond reversals and measured-move structures.
        if (data.size >= 50) {
            val d = data.takeLast(50)
            val first = d.take(20); val middle = d.drop(15).take(20); val lastPart = d.takeLast(15)
            val firstRange = first.maxOf { it.high } - first.minOf { it.low }
            val midRange = middle.maxOf { it.high } - middle.minOf { it.low }
            val finalRange = lastPart.maxOf { it.high } - lastPart.minOf { it.low }
            val earlyMid = (first.maxOf { it.high } + first.minOf { it.low }) / 2.0
            val lateMid = (lastPart.maxOf { it.high } + lastPart.minOf { it.low }) / 2.0
            if (firstRange > 0 && midRange > firstRange * 1.12 && finalRange < midRange * .88 &&
                lateMid < earlyMid && lastPart.maxOf { it.high } < middle.maxOf { it.high }) {
                val trigger = lastPart.minOf { it.low }
                out += candidate("الماسية القمة (Diamond Top)", "هابط", trigger, trigger-(midRange*.7), lastPart.maxOf{it.high}, last,
                    "اتساع تدريجي ثم تضييق مع انحياز هابط؛ كسر الحد السفلي هو إشارة التأكيد.")
            }
            if (firstRange > 0 && midRange > firstRange * 1.12 && finalRange < midRange * .88 &&
                lateMid > earlyMid && lastPart.minOf { it.low } > middle.minOf { it.low }) {
                val trigger = lastPart.maxOf { it.high }
                out += candidate("الماسية القاع (Diamond Bottom)", "صاعد", trigger, trigger+(midRange*.7), lastPart.minOf{it.low}, last,
                    "اتساع تدريجي ثم تضييق مع انحياز صاعد؛ اختراق الحد العلوي هو إشارة التأكيد.")
            }
        }
        if (data.size >= 45) {
            val w=data.takeLast(45); val first=w.take(22); val second=w.takeLast(23)
            val move1=first.last().close-first.first().close
            val move2=second.last().close-second.first().close
            val firstVol=first.map{it.volume}.average(); val secondVol=second.map{it.volume}.average()
            val high=w.maxOf{it.high}; val low=w.minOf{it.low}; val range=high-low
            if (move1>last*.06 && abs(move2)<move1*.45 && second.last().close>second.first().close*.96 && range>0) {
                out += candidate("الحركة المقاسة الصاعدة (Measured Move)", "صاعد", high, high+move1, low, last,
                    "اندفاع صاعد أول ثم فترة تصحيح/تماسك؛ الهدف النظري إسقاط طول الحركة الأولى بعد الاختراق.")
            } else if (move1< -last*.06 && abs(move2)<abs(move1)*.45 && second.last().close<second.first().close*1.04 && range>0) {
                out += candidate("الحركة المقاسة الهابطة (Measured Move)", "هابط", low, low-abs(move1), high, last,
                    "اندفاع هابط أول ثم فترة تصحيح/تماسك؛ الهدف النظري إسقاط طول الحركة الأولى بعد الكسر.")
            }
            // A quiet consolidation with a breakout-shaped range expansion candidate.
            if (firstVol>0 && secondVol>0 && secondVol<firstVol*1.35 && range>last*.025) {
                val localHigh=second.maxOf{it.high}; val localLow=second.minOf{it.low}
                if (last>localHigh) out += candidate("اختراق نطاق التماسك","صاعد",localHigh,localHigh+range*.65,localLow,last,
                    "تماسك نسبي ثم تجاوز سقف الجزء الأخير؛ يفضّل تأكيد الاختراق بالحجم.")
                else if (last<localLow) out += candidate("كسر نطاق التماسك","هابط",localLow,localLow-range*.65,localHigh,last,
                    "تماسك نسبي ثم كسر أرضية الجزء الأخير؛ يفضّل تأكيد الكسر بالحجم.")
            }
        }
        // Adam/Eve composite tops and bottoms: sharp vs rounded pivot approximations.
        if (highs.size >= 2 && lows.size >= 2) {
            val h1=highs[highs.size-2]; val h2=highs.last()
            val low=lows.last()
            val topTolerance=abs(h1-h2)/maxOf(h1,h2)
            if (topTolerance<.06 && abs(h1-h2)>last*.002) {
                val firstSharp = abs(h1-data.takeLast(35).take(17).maxOf{it.high})<last*.015
                val name=if(firstSharp) "قمة آدم وحواء (Adam & Eve Top)" else "قمة حواء وآدم (Eve & Adam Top)"
                val trigger=low; val height=((h1+h2)/2)-trigger
                out += candidate(name,"هابط",trigger,trigger-height,trigger+height,last,
                    "قمتان متقاربتان مع اختلاف تقريبي في شكل القمم؛ تأكيد بكسر الدعم الفاصل.")
            }
        }
        if (lows.size >= 2 && highs.size >= 2) {
            val l1=lows[lows.size-2]; val l2=lows.last()
            val high=highs.last()
            val bottomTolerance=abs(l1-l2)/minOf(l1,l2)
            if (bottomTolerance<.06) {
                val trigger=high; val height=trigger-((l1+l2)/2)
                val sharpFirst = abs(l1-data.takeLast(35).take(17).minOf{it.low})<last*.015
                val name=if(sharpFirst) "قاع آدم وحواء (Adam & Eve Bottom)" else "قاع حواء وآدم (Eve & Adam Bottom)"
                out += candidate(name,"صاعد",trigger,trigger+height,trigger-height,last,
                    "قاعان متقاربان مع اختلاف تقريبي في شكل القاع؛ تأكيد باختراق المقاومة الفاصلة.")
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
        // Broadening wedge approximation (sloped expansion), directional bias inferred from midpoints.
        if (data.size >= 35) {
            val w=data.takeLast(35); val a=w.take(17); val b=w.takeLast(18)
            val aH=a.maxOf{it.high}; val aL=a.minOf{it.low}; val bH=b.maxOf{it.high}; val bL=b.minOf{it.low}
            val aMid=(aH+aL)/2; val bMid=(bH+bL)/2
            if (bH>aH*1.02 && bL<aL*.98 && bMid>aMid) {
                out += candidate("الوتد المتسع الصاعد (Broadening Wedge)","صاعد",bH,bH+(bH-bL),bL,last,
                    "اتساع الحدود مع ميل عام صاعد؛ الاختراق فوق الحد الأعلى شرط تأكيد إضافي.")
            } else if (bH>aH*1.02 && bL<aL*.98 && bMid<aMid) {
                out += candidate("الوتد المتسع الهابط (Broadening Wedge)","هابط",bL,bL-(bH-bL),bH,last,
                    "اتساع الحدود مع ميل عام هابط؛ الكسر أسفل الحد الأدنى شرط تأكيد إضافي.")
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
