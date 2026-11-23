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
    val explanation: String
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
        val pivots = mutableListOf<Pair<Int, Boolean>>() // index, high?
        for (i in 2 until data.size - 2) {
            if (data[i].high >= data[i-1].high && data[i].high >= data[i-2].high &&
                data[i].high > data[i+1].high && data[i].high > data[i+2].high) pivots += i to true
            if (data[i].low <= data[i-1].low && data[i].low <= data[i-2].low &&
                data[i].low < data[i+1].low && data[i].low < data[i+2].low) pivots += i to false
        }
        val out = mutableListOf<ClassicPatternResult>()
        val last = data.last().close
        val highs = pivots.filter { it.second }.takeLast(5).map { data[it.first].high }
        val lows = pivots.filter { !it.second }.takeLast(5).map { data[it.first].low }

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
        return out.distinctBy{it.name}.take(6)
    }

    private fun candidate(name:String,bias:String,trigger:Double,targetSuccess:Double,targetFailure:Double,last:Double,explanation:String):ClassicPatternResult {
        val confirmed=when(bias) { "صاعد" -> last>trigger; "هابط" -> last<trigger; else -> last>trigger || last<trigger }
        val status=if (confirmed) "اختراق/كسر محتمل" else "قيد التشكّل — غير مؤكد"
        return ClassicPatternResult(name,bias,status,trigger,targetSuccess,targetFailure,targetFailure,explanation)
    }
}
