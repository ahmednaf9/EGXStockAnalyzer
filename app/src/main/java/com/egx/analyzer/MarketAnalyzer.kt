package com.egx.analyzer

import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

/** Market-neutral data quality checks and a concise professional report. */
data class MarketDiagnostics(
    val lowLiquidity: Boolean,
    val volumeRatio: Double?,
    val liquiditySummary: String,
    val warnings: List<String>
)

data class ProfessionalMarketReport(
    val summary: String,
    val trend: String,
    val alignment: String,
    val indicatorReasons: List<String>,
    val patternSummary: String,
    val triggerText: String,
    val targetsText: String,
    val supportText: String,
    val resistanceText: String,
    val reasonSummary: String
)

object MarketAnalyzer {
    fun inspect(input: List<Candle>): MarketDiagnostics {
        val rows = input.sortedBy { dateKey(it.date) }
        if (rows.isEmpty()) return MarketDiagnostics(
            true, null, "لا توجد بيانات كافية لمقارنة حجم التداول.",
            listOf("تعذر فحص انتظام تواريخ جلسات التداول.")
        )

        val warnings = mutableListOf<String>()
        val baseline = rows.dropLast(1).takeLast(20).map { it.volume }.filter { it.isFinite() && it > 0.0 }
        val averageVolume = baseline.average().takeIf { baseline.size >= 5 && it.isFinite() && it > 0.0 }
        val ratio = averageVolume?.let { rows.last().volume / it }
        val lowLiquidity = ratio == null || ratio < 0.35
        val liquiditySummary = when {
            ratio == null -> "بيانات حجم التداول غير كافية لمعرفة ما إذا كان النشاط أعلى أو أقل من المعتاد؛ لذلك لا نستخدم الحجم لتأكيد الإشارة."
            ratio < 0.35 -> "حجم تداول آخر جلسة يساوي " + fmt(ratio) + " مرة من متوسط آخر 20 جلسة متاحة؛ وهذا يعني أن عدد الأسهم المتداولة أقل من المعتاد بوضوح."
            ratio < 0.70 -> "حجم التداول أقل من متوسط آخر 20 جلسة متاحة (" + fmt(ratio) + " مرة من المتوسط)."
            ratio <= 1.30 -> "حجم التداول قريب من متوسط آخر 20 جلسة متاحة (" + fmt(ratio) + " مرة من المتوسط)."
            else -> "حجم التداول أعلى من متوسط آخر 20 جلسة متاحة (" + fmt(ratio) + " مرة من المتوسط)، ما يعني نشاطًا أكبر من المعتاد."
        }

        val dated = rows.mapNotNull { candle -> dateMillis(candle.date)?.let { it to candle } }
        dated.zipWithNext().forEach { pair ->
            val first = pair.first
            val second = pair.second
            val days = ((second.first - first.first) / 86_400_000L).toInt()
            if (days > 7) {
                warnings += "فترة انقطاع مدتها " + days + " يومًا بين جلستي التداول " + first.second.date + " و" + second.second.date +
                    "؛ وقد يرجع ذلك إلى عطلة أو توقف التداول أو نقص البيانات المستوردة. لا يمكن الجزم بالسبب من الأسعار وأحجام التداول وحدها."
            }
        }

        rows.zipWithNext().forEach { pair ->
            val previous = pair.first
            val current = pair.second
            val movePct = if (previous.close > 0.0) (current.close / previous.close - 1.0) * 100.0 else 0.0
            val openingGapPct = if (previous.close > 0.0) (current.open / previous.close - 1.0) * 100.0 else 0.0
            if (abs(movePct) >= 15.0 || abs(openingGapPct) >= 12.0) {
                warnings += "حركة سعرية كبيرة أو فجوة سعرية في جلسة " + current.date + " (الإغلاق " +
                    fmtSigned(movePct) + "%، فجوة الافتتاح " + fmtSigned(openingGapPct) +
                    "%). قد تنتج هذه الحركة عن تجزئة للسهم أو توزيع نقدي أو زيادة في رأس المال أو تغيير في بيانات التداول أو خبر مؤثر. راجع بيانات الشركة وإعلانات السوق قبل اعتبارها إشارة فنية."
            }
        }

        if (lowLiquidity) warnings += "انخفاض حجم التداول أو نقص بياناته يقلل من قوة تأكيد الاختراق؛ لذلك تعامل مع الإشارة بحذر وانتظر إغلاقًا واضحًا يدعمها."
        if (warnings.isEmpty()) warnings += "لم يرصد الفحص انقطاعات زمنية طويلة أو تغيرات سعرية استثنائية تتجاوز الحدود المحددة. ولا يعني ذلك بالضرورة عدم وجود أحداث مؤثرة على الشركة."
        return MarketDiagnostics(lowLiquidity, ratio, liquiditySummary, warnings.distinct().take(8))
    }

    fun buildReport(
        candles: List<Candle>,
        analysis: Analysis,
        classicPatterns: List<ClassicPatternResult>,
        diagnostics: MarketDiagnostics
    ): ProfessionalMarketReport {
        val last = candles.lastOrNull()?.close ?: analysis.last.close
        val reasons = mutableListOf<String>()
        var positive = 0
        var negative = 0

        analysis.sma20?.let {
            val above = last >= it
            if (above) positive++ else negative++
            reasons += "سعر الإغلاق الأخير " + (if (above) "أعلى من" else "أقل من") + " متوسط أسعار الإغلاق خلال آخر 20 جلسة (SMA 20)، وقيمة المتوسط " + fmt(it) + "."
        } ?: run { reasons += "المتوسط المتحرك لـ20 جلسة (SMA 20) غير متاح؛ لأنه يحتاج إلى 20 جلسة سعرية على الأقل." }

        analysis.sma50?.let {
            val above = last >= it
            if (above) positive++ else negative++
            reasons += "سعر الإغلاق الأخير " + (if (above) "أعلى من" else "أقل من") + " متوسط أسعار الإغلاق خلال آخر 50 جلسة (SMA 50)، وقيمة المتوسط " + fmt(it) + "."
        } ?: run { reasons += "المتوسط المتحرك لـ50 جلسة (SMA 50) غير متاح؛ لأنه يحتاج إلى 50 جلسة سعرية على الأقل." }

        when {
            analysis.rsi >= 55.0 -> { positive++; reasons += "مؤشر القوة النسبية (RSI)، الذي يقيس قوة وسرعة تغير السعر خلال الفترة الأخيرة، يميل إلى دعم الحركة الصاعدة (" + fmt(analysis.rsi) + ")." }
            analysis.rsi <= 45.0 -> { negative++; reasons += "مؤشر القوة النسبية (RSI)، الذي يقيس سرعة وقوة تغير السعر، يشير إلى ضعف الحركة (" + fmt(analysis.rsi) + ")." }
            else -> reasons += "مؤشر القوة النسبية (RSI)، الذي يقيس سرعة وقوة تغير السعر، في المنطقة الوسطى ولا يعطي ميلًا واضحًا (" + fmt(analysis.rsi) + ")."
        }

        if (analysis.macd != null && analysis.macdSignal != null) {
            val bullish = analysis.macd >= analysis.macdSignal
            if (bullish) positive++ else negative++
            reasons += "مؤشر MACD، الذي يقارن متوسطين أسيين للسعر لقياس اتجاه الزخم، " + (if (bullish) "أعلى من" else "أقل من") + " خط الإشارة؛ ما يشير إلى " + (if (bullish) "زخم إيجابي نسبيًا" else "زخم سلبي نسبيًا") + ". وتُقرأ هذه الإشارة مع موقع المؤشر من خط الصفر وبقية المؤشرات."
        } else reasons += "لا تتوفر بيانات كافية لحساب مؤشر MACD أو خط الإشارة؛ لذلك لا يدخل هذا المؤشر في تأكيد الاتجاه."

        val patternBias = classicPatterns.count { it.bias == "صاعد" } - classicPatterns.count { it.bias == "هابط" }
        val candleBullish = analysis.recentPatterns.count { it.direction == "إيجابي" }
        val candleBearish = analysis.recentPatterns.count { it.direction == "سلبي" }
        val totalPatternBias = patternBias + candleBullish - candleBearish
        if (totalPatternBias > 0) positive++ else if (totalPatternBias < 0) negative++
        reasons += "حصيلة الفحص: رصد البرنامج " + classicPatterns.size + " نموذجًا سعريًا كلاسيكيًا و" +
            analysis.recentPatterns.size + " إشارة من نماذج الشموع اليابانية ضمن البيانات التي تم فحصها."

        val alignment = when {
            positive >= 3 && negative == 0 -> "متوافق إيجابيًا"
            negative >= 3 && positive == 0 -> "متوافق سلبيًا"
            positive > negative -> "يميل للإيجابية"
            negative > positive -> "يميل للسلبية"
            else -> "مختلط/محايد"
        }
        val trigger = when (analysis.trend) {
            "صاعد", "تحسن صاعد" -> analysis.resistances.firstOrNull { it > last }?.let { fmt(it) } ?: "راقب اختراق قمة حديثة بإغلاق"
            "هابط", "ضغط هابط" -> analysis.supports.firstOrNull { it < last }?.let { fmt(it) } ?: "راقب كسر قاع حديث بإغلاق"
            else -> "انتظار خروج واضح من النطاق"
        }
        val patternNames = (classicPatterns.map { it.name } + analysis.recentPatterns.map { it.name }).distinct()
        val patternSummary = if (patternNames.isEmpty()) "لم تُكتشف نماذج واضحة في العينة الحالية." else patternNames.take(12).joinToString("، ")
        val targets = analysis.targets.take(3).map { fmt(it) }.joinToString(" / ").ifBlank { "غير متاحة" }
        val supports = analysis.supports.take(3).map { fmt(it) }.joinToString(" / ").ifBlank { "غير متاحة" }
        val resistances = analysis.resistances.take(3).map { fmt(it) }.joinToString(" / ").ifBlank { "غير متاحة" }
        val reasonSummary = "بلغ التقييم الفني للسهم " + analysis.score + " من 100، وتصنيفه " + analysis.scoreLabel + "؛ " +
            analysis.finalRatingReason + " " +
            (if (diagnostics.lowLiquidity) "حجم التداول منخفض نسبيًا أو بياناته غير مكتملة؛ لذلك يلزم الحذر عند قراءة الإشارات."
             else "تمت مراجعة حجم التداول والفجوات الزمنية والتغيرات السعرية غير المعتادة.")
        val summary = "الاتجاه الفني للسهم: " + analysis.trend + "؛ ودرجته الفنية: " + analysis.score +
            " من 100؛ ومدى اتفاق المؤشرات والنماذج: " + alignment + ". والخلاصة الفنية: " +
            analysis.finalRating + ". مستويات الأسعار المعروضة للمراقبة الفنية فقط، وليست أمرًا بالشراء أو البيع."
        return ProfessionalMarketReport(
            summary, analysis.trend, alignment, reasons, patternSummary, trigger,
            targets, supports, resistances, reasonSummary
        )
    }

    private fun dateKey(value: String): Long = dateMillis(value) ?: Long.MAX_VALUE

    private fun dateMillis(value: String): Long? {
        val formats = listOf("yyyy-MM-dd", "yyyy/MM/dd", "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "d-M-yyyy")
        for (format in formats) {
            try {
                val parser = SimpleDateFormat(format, Locale.US).apply { isLenient = false }
                return parser.parse(value.trim())?.time
            } catch (_: Exception) {
                // Try the next supported date representation.
            }
        }
        return null
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)
    private fun fmtSigned(value: Double): String = String.format(Locale.US, "%+.2f", value)
}
