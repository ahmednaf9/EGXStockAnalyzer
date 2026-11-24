package com.egx.analyzer

import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

/** Egyptian-market data quality checks and a concise professional report. */
data class EgyptMarketDiagnostics(
    val lowLiquidity: Boolean,
    val volumeRatio: Double?,
    val liquiditySummary: String,
    val warnings: List<String>
)

data class EgyptProfessionalReport(
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

object EgyptMarketAnalyzer {
    fun inspect(input: List<Candle>): EgyptMarketDiagnostics {
        val rows = input.sortedBy { dateKey(it.date) }
        if (rows.isEmpty()) return EgyptMarketDiagnostics(
            true, null, "لا توجد بيانات كافية لفحص السيولة.",
            listOf("تعذر تقييم جودة السلسلة الزمنية.")
        )

        val warnings = mutableListOf<String>()
        val baseline = rows.dropLast(1).takeLast(20).map { it.volume }.filter { it.isFinite() && it > 0.0 }
        val averageVolume = baseline.average().takeIf { baseline.size >= 5 && it.isFinite() && it > 0.0 }
        val ratio = averageVolume?.let { rows.last().volume / it }
        val lowLiquidity = ratio == null || ratio < 0.35
        val liquiditySummary = when {
            ratio == null -> "حجم التداول غير كافٍ للحكم؛ لا تمنح الإشارة دعمًا من السيولة."
            ratio < 0.35 -> "حجم آخر جلسة يعادل " + fmt(ratio) + "× من متوسط الجلسات السابقة؛ سيولة نسبية ضعيفة."
            ratio < 0.70 -> "حجم التداول أقل من المعتاد (" + fmt(ratio) + "× من المتوسط)."
            ratio <= 1.30 -> "حجم التداول قريب من متوسطه (" + fmt(ratio) + "×)."
            else -> "حجم التداول أعلى من متوسطه (" + fmt(ratio) + "×)."
        }

        val dated = rows.mapNotNull { candle -> dateMillis(candle.date)?.let { it to candle } }
        dated.zipWithNext().forEach { pair ->
            val first = pair.first
            val second = pair.second
            val days = ((second.first - first.first) / 86_400_000L).toInt()
            if (days > 7) {
                warnings += "فاصل " + days + " يومًا بين " + first.second.date + " و" + second.second.date +
                    "؛ قد يكون عطلة ممتدة أو توقف تداول أو نقصًا في البيانات، ولا يمكن الجزم بالسبب من OHLCV وحده."
            }
        }

        rows.zipWithNext().forEach { pair ->
            val previous = pair.first
            val current = pair.second
            val movePct = if (previous.close > 0.0) (current.close / previous.close - 1.0) * 100.0 else 0.0
            val openingGapPct = if (previous.close > 0.0) (current.open / previous.close - 1.0) * 100.0 else 0.0
            if (abs(movePct) >= 15.0 || abs(openingGapPct) >= 12.0) {
                warnings += "حركة/فجوة سعرية غير معتادة في " + current.date + " (الإغلاق " +
                    fmtSigned(movePct) + "%، فجوة الافتتاح " + fmtSigned(openingGapPct) +
                    "%). راجع التجزئة أو التوزيعات أو زيادة رأس المال أو أخبار السهم قبل اعتمادها كإشارة فنية."
            }
        }

        if (lowLiquidity) warnings += "السيولة النسبية الضعيفة أو نقص بيانات الحجم قد يرفعان احتمالات الاختراقات الكاذبة؛ خفّض الثقة وانتظر إغلاقًا مؤكدًا."
        if (warnings.isEmpty()) warnings += "لم تُرصد فجوات زمنية طويلة أو قفزات سعرية استثنائية وفق حدود الفحص؛ هذا لا يثبت خلو السهم من أحداث الشركات."
        return EgyptMarketDiagnostics(lowLiquidity, ratio, liquiditySummary, warnings.distinct().take(8))
    }

    fun buildReport(
        candles: List<Candle>,
        analysis: Analysis,
        classicPatterns: List<ClassicPatternResult>,
        diagnostics: EgyptMarketDiagnostics
    ): EgyptProfessionalReport {
        val last = candles.lastOrNull()?.close ?: analysis.last.close
        val reasons = mutableListOf<String>()
        var positive = 0
        var negative = 0

        analysis.sma20?.let {
            val above = last >= it
            if (above) positive++ else negative++
            reasons += "السعر " + (if (above) "أعلى" else "أسفل") + " SMA20 (" + fmt(it) + ")."
        } ?: run { reasons += "SMA20 غير متاح." }

        analysis.sma50?.let {
            val above = last >= it
            if (above) positive++ else negative++
            reasons += "السعر " + (if (above) "أعلى" else "أسفل") + " SMA50 (" + fmt(it) + ")."
        } ?: run { reasons += "SMA50 غير متاح لعدد الجلسات الحالي." }

        when {
            analysis.rsi >= 55.0 -> { positive++; reasons += "RSI يميل للإيجابية (" + fmt(analysis.rsi) + ")." }
            analysis.rsi <= 45.0 -> { negative++; reasons += "RSI يميل للسلبية (" + fmt(analysis.rsi) + ")." }
            else -> reasons += "RSI في المنطقة الوسطية (" + fmt(analysis.rsi) + ")."
        }

        if (analysis.macd != null && analysis.macdSignal != null) {
            val bullish = analysis.macd >= analysis.macdSignal
            if (bullish) positive++ else negative++
            reasons += "MACD " + (if (bullish) "أعلى" else "أسفل") + " خط الإشارة."
        } else reasons += "MACD أو خط الإشارة غير متاح."

        val patternBias = classicPatterns.count { it.bias == "صاعد" } - classicPatterns.count { it.bias == "هابط" }
        val candleBullish = analysis.recentPatterns.count { it.direction == "إيجابي" }
        val candleBearish = analysis.recentPatterns.count { it.direction == "سلبي" }
        val totalPatternBias = patternBias + candleBullish - candleBearish
        if (totalPatternBias > 0) positive++ else if (totalPatternBias < 0) negative++
        reasons += "توازن النماذج: " + classicPatterns.size + " نموذج كلاسيكي و" +
            analysis.recentPatterns.size + " إشارة شموع ضمن البيانات المفحوصة."

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
        val reasonSummary = "التقييم الفني " + analysis.score + "/100 (" + analysis.scoreLabel + ")؛ " +
            analysis.finalRatingReason + " " +
            (if (diagnostics.lowLiquidity) "تم رصد ضعف/نقص نسبي في السيولة، لذا يلزم تحفظ إضافي."
             else "تم فحص السيولة النسبية والفجوات الزمنية والسعرية.")
        val summary = "الاتجاه " + analysis.trend + "، والتقييم " + analysis.score +
            "/100، وتوافق المؤشرات والنماذج: " + alignment + ". التصنيف النهائي: " +
            analysis.finalRating + ". المستويات مراقبة فنية مشروطة وليست أمر شراء أو بيع."
        return EgyptProfessionalReport(
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
