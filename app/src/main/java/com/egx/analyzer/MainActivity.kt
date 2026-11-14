package com.egx.analyzer

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val Navy = Color(0xFF10243A)
private val Green = Color(0xFF14966C)
private val Page = Color(0xFFF3F6F9)
private val Muted = Color(0xFF718096)

data class Candle(val date: String, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)
data class Analysis(
    val last: Candle, val supports: List<Double>, val resistances: List<Double>,
    val rsi: Double, val sma20: Double?, val sma50: Double?, val macd: Double?,
    val trend: String, val patterns: List<String>, val targets: List<Double>, val invalidation: Double?
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AnalyzerScreen(::readCsv) } }
    }

    private fun readCsv(uri: Uri): Pair<List<Candle>, String> = try {
        val input = contentResolver.openInputStream(uri) ?: return Pair(emptyList(), "تعذر فتح الملف")
        val rows = BufferedReader(InputStreamReader(input)).use { it.readLines() }
        val parsed = rows.drop(1).mapNotNull { line ->
            val p = line.split(",").map { it.trim().trim('"') }
            if (p.size < 6) null else try {
                Candle(p[0], p[1].toDouble(), p[2].toDouble(), p[3].toDouble(),
                    p[4].toDouble(), p[5].replace(",", "").toDoubleOrNull() ?: 0.0)
            } catch (_: Exception) { null }
        }.sortedBy { it.date }
        Pair(parsed, if (parsed.size >= 20) "تم استيراد ${parsed.size} جلسة." else "تمت قراءة ${parsed.size} جلسة. يلزم 20 جلسة على الأقل.")
    } catch (e: Exception) { Pair(emptyList(), "تعذرت قراءة الملف: ${e.message ?: "صيغة غير صحيحة"}") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzerScreen(importCsv: (Uri) -> Pair<List<Candle>, String>) {
    var symbol by remember { mutableStateOf("") }
    var candles by remember { mutableStateOf(emptyList<Candle>()) }
    var message by remember { mutableStateOf("استورد ملف CSV لبدء التحليل الفني.") }
    var result by remember { mutableStateOf<Analysis?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val loaded = importCsv(uri)
            candles = loaded.first
            message = loaded.second
            result = if (candles.size >= 20) TechnicalAnalyzer.analyze(candles) else null
        }
    }

    Scaffold(containerColor = Page, topBar = {
        TopAppBar(
            title = { Column {
                Text("EGX ANALYZER", color = Green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("محلل الأسهم المصرية", color = Navy, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
            } },
            actions = { Icon(Icons.Default.ShowChart, null, tint = Green, modifier = Modifier.padding(end = 18.dp).size(30.dp)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Page)
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = Navy), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("حلّل سهمًا من البورصة المصرية", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("اكتب كود التداول ثم استورد بياناته التاريخية", color = Color(0xFFB9C9D8), fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, null, tint = Muted)
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(value = symbol, onValueChange = { symbol = it.uppercase(Locale.ROOT) },
                            modifier = Modifier.weight(1f).padding(vertical = 12.dp), singleLine = true,
                            decorationBox = { inner -> if (symbol.isBlank()) Text("مثال: COMI", color = Muted); inner() })
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { picker.launch(arrayOf("text/*", "text/csv", "application/vnd.ms-excel")) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Green), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("استيراد ملف الأسعار CSV", fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = Green)
                    Spacer(Modifier.width(8.dp))
                    Text(message, color = Muted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            val a = result
            if (a == null) {
                Text("مميزات المحلل", color = Navy, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Feature("الدعوم والمقاومات", "مستويات فنية من بيانات السعر")
                Feature("المؤشرات", "RSI و SMA 20/50 و MACD")
                Feature("النماذج", "رصد بعض نماذج الشموع والقمم والقيعان")
                Feature("المستهدفات", "سيناريوهات سعرية ومستوى إبطال")
                Text("تنسيق CSV المطلوب: Date,Open,High,Low,Close,Volume", color = Muted, fontSize = 11.sp)
            } else {
                Text("نتيجة التحليل: ${symbol.ifBlank { "السهم المستورد" }}", color = Navy, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric("آخر إغلاق", money(a.last.close), Modifier.weight(1f))
                    Metric("الاتجاه", a.trend, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Text("الدعوم والمقاومات", color = Navy, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric("الدعم", a.supports.joinToString("\n") { money(it) }.ifBlank { "غير محدد" }, Modifier.weight(1f))
                    Metric("المقاومة", a.resistances.joinToString("\n") { money(it) }.ifBlank { "غير محدد" }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Text("المؤشرات الفنية", color = Navy, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Metric("RSI 14", String.format(Locale.US, "%.1f", a.rsi), Modifier.weight(1f))
                    Metric("SMA 20", a.sma20?.let(::money) ?: "—", Modifier.weight(1f))
                    Metric("SMA 50", a.sma50?.let(::money) ?: "—", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Text("النماذج المرصودة", color = Navy, fontWeight = FontWeight.Bold)
                ResultCard(a.patterns.joinToString("\n"))
                Spacer(Modifier.height(12.dp))
                Text("مستهدفات السيناريو الفني", color = Navy, fontWeight = FontWeight.Bold)
                ResultCard("قصير المدى: ${a.targets.getOrNull(0)?.let(::money) ?: "غير محدد"}\nمتوسط المدى: ${a.targets.getOrNull(1)?.let(::money) ?: "غير محدد"}\nطويل المدى: ${a.targets.getOrNull(2)?.let(::money) ?: "غير محدد"}\nإبطال السيناريو: ${a.invalidation?.let(::money) ?: "غير محدد"}")
                Text("هذه مستويات حسابية افتراضية مبنية على التاريخ المستورد، وليست توصية أو ضمانًا للربح.", color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(20.dp))
            Text("لا يوجد اتصال مباشر بمصدر أسعار EGX في هذه النسخة. تحقق من مصدر ملفك وتاريخه قبل الاعتماد على النتائج.", color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(11.dp)) {
            Text(label, color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(5.dp))
            Text(value, color = Navy, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}
@Composable fun ResultCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
        Text(text, Modifier.fillMaxWidth().padding(14.dp), color = Navy, fontSize = 13.sp, lineHeight = 22.sp)
    }
}
@Composable fun Feature(title: String, desc: String) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Insights, null, tint = Green, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Column { Text(title, color = Navy, fontWeight = FontWeight.Bold); Text(desc, color = Muted, fontSize = 11.sp) }
        }
    }
}
private fun money(v: Double) = String.format(Locale.US, "%.2f", v)

object TechnicalAnalyzer {
    fun analyze(input: List<Candle>): Analysis {
        val rows = input.sortedBy { it.date }
        val closes = rows.map { it.close }
        val last = rows.last()
        fun sma(n: Int): Double? = if (closes.size >= n) closes.takeLast(n).average() else null
        val changes = closes.zipWithNext { x, y -> y - x }.takeLast(14)
        val avgGain = changes.map { max(it, 0.0) }.average()
        val avgLoss = changes.map { max(-it, 0.0) }.average()
        val rsi = if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
        fun ema(values: List<Double>, period: Int): Double {
            val k = 2.0 / (period + 1.0)
            return values.drop(1).fold(values.first()) { prev, value -> value * k + prev * (1 - k) }
        }
        val macd = if (closes.size >= 26) {
            val v = closes.takeLast(26); ema(v, 12) - ema(v, 26)
        } else null
        val recent = rows.takeLast(60)
        val supports = recent.map { it.low }.filter { it < last.close }.sortedDescending()
            .distinctBy { (it * 100).toInt() }.take(3).sorted()
        val resistances = recent.map { it.high }.filter { it > last.close }.sorted()
            .distinctBy { (it * 100).toInt() }.take(3)
        val s20 = sma(20); val s50 = sma(50)
        val trend = when {
            s20 != null && s50 != null && last.close > s20 && s20 > s50 -> "صاعد"
            s20 != null && s50 != null && last.close < s20 && s20 < s50 -> "هابط"
            else -> "عرضي / غير مؤكد"
        }
        val previous = rows[rows.lastIndex - 1]
        val patterns = mutableListOf<String>()
        val range = (last.high - last.low).coerceAtLeast(0.000001)
        if (abs(last.close - last.open) / range < 0.1) patterns.add("شمعة دوجي محتملة")
        if (last.close > last.open && previous.close < previous.open && last.close >= previous.open && last.open <= previous.close)
            patterns.add("ابتلاع شرائي محتمل")
        if (last.close < last.open && previous.close > previous.open && last.open >= previous.close && last.close <= previous.open)
            patterns.add("ابتلاع بيعي محتمل")
        if (patterns.isEmpty()) patterns.add("لم يتم رصد نموذج شموع واضح")
        val atr = recent.takeLast(14).map { it.high - it.low }.average()
        val t1 = resistances.firstOrNull() ?: (last.close + atr)
        val t2 = last.close + atr * 2.0
        val t3 = last.close + atr * 3.0
        val stop = supports.lastOrNull() ?: (last.close - atr)
        return Analysis(last, supports, resistances, rsi, s20, s50, macd, trend, patterns, listOf(t1, t2, t3), stop)
    }
}
