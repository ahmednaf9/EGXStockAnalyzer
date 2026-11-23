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

        // Double top / bottom: two similar pivots and a valley/peak between them.
        if (highs.size >= 2) {
            val a=highs[highs.size-2]; val b=highs.last()
            if (abs(a-b)/maxOf(a,b) < 0.045 && lows.isNotEmpty()) {
                val trigger=lows.last(); val h=((a+b)/2)-trigger
                out += candidate("القمة المزدوجة","هابط",trigger,trigger-h,trigger+h,last,
                    "قمتان متقاربتان؛ تأكيد هابط بإغلاق أسفل مستوى الفاصل بينهما.")
            }
        }
        if (lows.size >= 2) {
            val a=lows[lows.size-2]; val b=lows.last()
            if (abs(a-b)/minOf(a,b) < 0.045 && highs.isNotEmpty()) {
                val trigger=highs.last(); val h=trigger-((a+b)/2)
                out += candidate("القاع المزدوج","صاعد",trigger,trigger+h,trigger-h,last,
                    "قاعان متقاربان؛ تأكيد صاعد بإغلاق أعلى القمة الفاصلة.")
            }
        }
        // Triple top / bottom using three recent similar pivots.
        if (highs.size >= 3 && lows.size >= 2) {
            val a=highs[highs.size-3]; val b=highs[highs.size-2]; val d=highs.last()
            if (maxOf(a,b,d)-minOf(a,b,d) < maxOf(a,b,d)*.05) {
                val valley=lows.takeLast(2).minOrNull() ?: 0.0
                val height=((a+b+d)/3)-valley
                out += candidate("القمة الثلاثية","هابط",valley,valley-height,valley+height,last,
                    "ثلاث قمم متقاربة؛ تأكيد النموذج بكسر الدعم الفاصل.")
            }
        }
        if (lows.size >= 3 && highs.size >= 2) {
            val a=lows[lows.size-3]; val b=lows[lows.size-2]; val d=lows.last()
            if (maxOf(a,b,d)-minOf(a,b,d) < minOf(a,b,d)*.05) {
                val peak=highs.takeLast(2).maxOrNull() ?: 0.0
                val height=peak-((a+b+d)/3)
                out += candidate("القاع الثلاثي","صاعد",peak,peak+height,peak-height,last,
                    "ثلاثة قيعان متقاربة؛ تأكيد النموذج باختراق المقاومة الفاصلة.")
            }
        }
        // Head & shoulders / inverse: use the last three alternating same-type pivots.
        if (highs.size >= 3) {
            val l=highs[highs.size-3]; val h=highs[highs.size-2]; val r=highs.last()
            if (h>l && h>r && abs(l-r)/h < .08 && lows.size>=2) {
                val neck=(lows[lows.size-2]+lows.last())/2; val height=h-neck
                out += candidate("الرأس والكتفين","هابط",neck,neck-height,neck+height,last,
                    "رأس أعلى من الكتفين؛ التأكيد بكسر خط الرقبة.")
            }
        }
        if (lows.size >= 3) {
            val l=lows[lows.size-3]; val h=lows[lows.size-2]; val r=lows.last()
            if (h<l && h<r && abs(l-r)/maxOf(l,r) < .08 && highs.size>=2) {
                val neck=(highs[highs.size-2]+highs.last())/2; val height=neck-h
                out += candidate("الرأس والكتفين المقلوب","صاعد",neck,neck+height,neck-height,last,
                    "رأس أدنى من الكتفين؛ التأكيد باختراق خط الرقبة.")
            }
        }
        // Cup-and-handle approximation: rounded multi-week base, then a shallow handle.
        if (data.size >= 55) {
            val cup=data.takeLast(55).dropLast(10); val handle=data.takeLast(10)
            val left=cup.take(10).maxOf{it.high}; val bottom=cup.drop(10).take(25).minOf{it.low}
            val right=cup.takeLast(10).maxOf{it.high}; val handleLow=handle.minOf{it.low}
            val rim=(left+right)/2
            if (bottom<rim && abs(left-right)/rim<.12 && handleLow>rim*.82 && handleLow<rim*1.02) {
                val depth=rim-bottom
                out += candidate("الكوب والعروة","صاعد",rim,rim+depth,handleLow,last,
                    "قاعدة مقوسة تقريبية مع عروة قصيرة؛ يتأكد باختراق حافة الكوب.")
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
        // Range / triangle candidates based on recent 30-bar boundaries.
        val recent=data.takeLast(30); val ceiling=recent.maxOf{it.high}; val floor=recent.minOf{it.low}; val range=ceiling-floor
        if (range>0 && range/last < .22) {
            val recentHighs=recent.take(15).maxOf{it.high}; val recentLows=recent.take(15).minOf{it.low}
            val laterHighs=recent.drop(15).maxOf{it.high}; val laterLows=recent.drop(15).minOf{it.low}
            if (laterHighs < recentHighs && laterLows > recentLows) {
                out += candidate("المثلث المتماثل","محايد",ceiling,ceiling+range,floor-range,last,
                    "نطاق يتقلص؛ اتجاه النموذج يتحدد بالاختراق، ويُعرض الهدف للاتجاه الذي يتأكد.")
            } else if (abs(ceiling-recent.take(15).maxOf{it.high})/ceiling < .03 && laterLows>recentLows) {
                out += candidate("المثلث الصاعد","صاعد",ceiling,ceiling+range,floor-range,last,
                    "مقاومة شبه أفقية وقيعان أعلى؛ الاختراق فوق المقاومة يؤكد.")
            } else if (abs(floor-recent.take(15).minOf{it.low})/floor < .03 && laterHighs<recentHighs) {
                out += candidate("المثلث الهابط","هابط",floor,floor-range,ceiling+range,last,
                    "دعم شبه أفقي وقمم أقل؛ الكسر أسفل الدعم يؤكد.")
            } else {
                out += candidate("المستطيل (النطاق)","محايد",ceiling,ceiling+range,floor-range,last,
                    "السعر يتحرك ضمن نطاق؛ لا يتأكد الاتجاه قبل اختراق أحد الحدين.")
            }
        }
        // Flag-like pause after a strong directional impulse followed by a compact pullback.
        if (data.size >= 35) {
            val pole=data.takeLast(35).take(25); val pause=data.takeLast(10)
            val poleLow=pole.minOf{it.low}; val poleHigh=pole.maxOf{it.high}; val poleMove=poleHigh-poleLow
            val pauseRange=pause.maxOf{it.high}-pause.minOf{it.low}
            if (poleMove/ poleLow > .12 && pauseRange/poleMove < .55 && pause.last().close >= pause.first().close*.94) {
                val trigger=pause.maxOf{it.high}
                out += candidate("العلم الصاعد","صاعد",trigger,trigger+poleMove,pause.minOf{it.low}-poleMove,last,
                    "اندفاع صاعد مع تماسك قصير؛ اختراق أعلى التماسك يفعّل هدف طول السارية.")
            } else if (poleMove/poleHigh > .12 && pauseRange/poleMove < .55 && pause.last().close <= pause.first().close*1.06) {
                val trigger=pause.minOf{it.low}
                out += candidate("العلم الهابط","هابط",trigger,trigger-poleMove,pause.maxOf{it.high}+poleMove,last,
                    "اندفاع هابط مع تماسك قصير؛ كسر أدنى التماسك يفعّل هدف طول السارية.")
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
            if (firstRange > 0 && abs(upper2-upper1)/firstRange < .20 && abs(lower2-lower1)/firstRange < .20 && secondMid > firstMid) {
                out += candidate("القناة الصاعدة","صاعد",upper2,upper2+firstRange,lower2,last,
                    "نطاقان متوازيان تقريبًا وميل متوسط صاعد؛ تجاوز سقف القناة هو مستوى التفعيل المرجعي.")
            } else if (firstRange > 0 && abs(upper2-upper1)/firstRange < .20 && abs(lower2-lower1)/firstRange < .20 && secondMid < firstMid) {
                out += candidate("القناة الهابطة","هابط",lower2,lower2-firstRange,upper2,last,
                    "نطاقان متوازيان تقريبًا وميل متوسط هابط؛ كسر أرضية القناة هو مستوى التفعيل المرجعي.")
            }
            if (secondRange > firstRange*1.15 && upper2>upper1 && lower2<lower1) {
                out += candidate("التشكيل المتسع (Broadening)","محايد",upper2,upper2+secondRange,lower2-secondRange,last,
                    "قمم أعلى وقيعان أدنى مع اتساع الحركة؛ اتجاه التأكيد يتحدد بكسر أحد الحدود.")
            }
        }
        if (data.size >= 35) {
            val window=data.takeLast(35); val pole=window.take(22); val pause=window.takeLast(13)
            val poleRise=pole.last().close-pole.first().close
            val pauseHigh=pause.maxOf{it.high}; val pauseLow=pause.minOf{it.low}
            val pauseWidth=pauseHigh-pauseLow
            val pHigh1=pause.take(6).maxOf{it.high}; val pHigh2=pause.takeLast(6).maxOf{it.high}
            val pLow1=pause.take(6).minOf{it.low}; val pLow2=pause.takeLast(6).minOf{it.low}
            if (poleRise>last*.08 && pauseWidth<poleRise*.55 && pHigh2<pHigh1 && pLow2>pLow1) {
                out += candidate("الراية الصاعدة (Pennant)","صاعد",pauseHigh,pauseHigh+poleRise,pauseLow,last,
                    "سارية صاعدة قوية يتبعها تضييق تقريبي؛ تأكيد محتمل باختراق قمة الراية.")
            } else if (poleRise < -last*.08 && pauseWidth<abs(poleRise)*.55 && pHigh2<pHigh1 && pLow2>pLow1) {
                out += candidate("الراية الهابطة (Pennant)","هابط",pauseLow,pauseLow-abs(poleRise),pauseHigh,last,
                    "سارية هابطة قوية يتبعها تضييق تقريبي؛ تأكيد محتمل بكسر قاع الراية.")
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
        return out.distinctBy{it.name}.take(24)
    }

    private fun candidate(name:String,bias:String,trigger:Double,targetSuccess:Double,targetFailure:Double,last:Double,explanation:String):ClassicPatternResult {
        val confirmed=when(bias) { "صاعد" -> last>trigger; "هابط" -> last<trigger; else -> false }
        val status=if (bias == "محايد") "قيد التشكّل — بانتظار الاختراق" else if (confirmed) "تأكيد مبدئي بالإغلاق" else "قيد التشكّل — غير مؤكد"
        val distancePct = if (trigger > 0.0) abs(last-trigger)/trigger*100.0 else 100.0
        val base = when {
            bias == "محايد" -> 42
            confirmed -> 72
            else -> 54
        }
        val distancePenalty = (distancePct*2.0).toInt().coerceAtMost(22)
        val confidence = (base-distancePenalty).coerceIn(25, 88)
        val baseReason = when {
            bias == "محايد" -> "النموذج محايد الاتجاه (أساس 42)"
            confirmed -> "السعر تجاوز مستوى التفعيل مبدئيًا (أساس 72)"
            else -> "لم يتجاوز السعر مستوى التفعيل بعد (أساس 54)"
        }
        val distanceReason = "ابتعاد السعر عن مستوى التفعيل ${String.format(java.util.Locale.US, "%.2f", distancePct)}%، خصم تقديري ${distancePenalty} نقطة"
        val details = "$baseReason؛ $distanceReason. الدرجة ناتجة عن قاعدة مبسطة وليست احتمال نجاح إحصائيًا."
        return ClassicPatternResult(name,bias,status,trigger,targetSuccess,targetFailure,targetFailure,explanation,confidence,details)
    }
}
