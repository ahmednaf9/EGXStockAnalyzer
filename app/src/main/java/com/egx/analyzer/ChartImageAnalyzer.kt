package com.egx.analyzer

import android.graphics.Bitmap
import kotlin.math.abs

/** Offline visual trend estimator. It detects colored chart traces, not exact prices. */
object ChartImageAnalyzer {
    fun analyze(source: Bitmap): Pair<String, String> {
        if (source.width < 160 || source.height < 100) return "" to "الصورة صغيرة جدًا. ارفع لقطة شاشة واضحة للشارت."
        val scale = minOf(1.0, 1000.0 / source.width, 700.0 / source.height)
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createScaledBitmap(source, w, h, true)
        val left = (w * 0.08).toInt(); val right = (w * 0.96).toInt()
        val top = (h * 0.08).toInt(); val bottom = (h * 0.90).toInt()
        val bins = 72
        val ys = Array(bins) { mutableListOf<Int>() }
        for (x in left until right step 2) {
            val bin = (((x - left).toDouble() / (right - left)) * bins).toInt().coerceIn(0, bins - 1)
            for (y in top until bottom step 2) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 255; val g = (pixel shr 8) and 255; val b = pixel and 255
                val maxC = maxOf(r, g, b); val minC = minOf(r, g, b)
                val saturation = if (maxC == 0) 0.0 else (maxC - minC).toDouble() / maxC
                if (saturation > 0.34 && maxC / 255.0 > 0.28) ys[bin].add(y)
            }
        }
        bitmap.recycle()
        val points = ys.mapIndexedNotNull { i, values ->
            if (values.size < 2) null else { val sorted = values.sorted(); i.toDouble() to sorted[sorted.size / 2].toDouble() }
        }
        if (points.size < 14) return "" to "لم أتمكن من تمييز خط سعري ملون بوضوح. جرّب صورة أوضح وأظهر الشارت كاملًا."
        val meanX = points.map { it.first }.average(); val meanY = points.map { it.second }.average()
        val numerator = points.sumOf { (x, y) -> (x - meanX) * (y - meanY) }
        val denominator = points.sumOf { (x, _) -> (x - meanX) * (x - meanX) }.coerceAtLeast(0.0001)
        val slope = numerator / denominator
        val verticalRange = (bottom - top).toDouble().coerceAtLeast(1.0)
        val normalizedSlope = slope * (bins - 1) / verticalRange
        val trend = when { normalizedSlope < -0.075 -> "الاتجاه المرئي صاعد"; normalizedSlope > 0.075 -> "الاتجاه المرئي هابط"; else -> "الاتجاه المرئي عرضي أو غير واضح" }
        val levels = points.map { it.second }.sorted()
        val lowBand = levels[(levels.size * 0.20).toInt().coerceIn(0, levels.lastIndex)]
        val highBand = levels[(levels.size * 0.80).toInt().coerceIn(0, levels.lastIndex)]
        val supportPosition = ((lowBand - top) / verticalRange * 100.0).coerceIn(0.0, 100.0)
        val resistancePosition = ((highBand - top) / verticalRange * 100.0).coerceIn(0.0, 100.0)
        val confidence = (points.size.toDouble() / bins * 100.0).toInt().coerceIn(20, 95)
        val strength = when { abs(normalizedSlope) >= 0.22 -> "واضح نسبيًا"; abs(normalizedSlope) >= 0.10 -> "متوسط"; else -> "ضعيف" }
        val report = buildString {
            appendLine(trend)
            appendLine("قوة الميل المرئي: $strength")
            appendLine("النطاق البصري المنخفض: عند ${supportPosition.toInt()}% من أعلى مساحة الرسم (ليس مستوى سعر).")
            appendLine("النطاق البصري المرتفع: عند ${resistancePosition.toInt()}% من أعلى مساحة الرسم (ليس مستوى سعر).")
            appendLine("تغطية اكتشاف المسار الملون: $confidence%")
            append("الفترة المختارة في التطبيق لا يمكن التحقق منها من الصورة وحدها.")
        }
        return report to "تم فحص الصورة محليًا دون إنترنت. لا يمكن استخراج أسعار فعلية أو مستويات سعرية دقيقة من الصورة وحدها."
    }
}
