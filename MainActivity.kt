package com.egx.analyzer

import android.net.Uri
import android.os.Bundle
import android.content.Intent
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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

private val Navy = Color(0xFF10243A)
private val Green = Color(0xFF14966C)
private val Page = Color(0xFFF3F6F9)
private val Muted = Color(0xFF718096)
private val Red = Color(0xFFC64D55)
private val Amber = Color(0xFFB7791F)

data class Candle(
    val date: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AnalyzerScreen(::readCsv, ::fetchHistory, ::openTradingView) } }
    }


    private fun fetchHistory(symbol: String, apiKey: String, callback: (List<Candle>, String) -> Unit) {
        Thread {
            try {
                if (symbol.isBlank() || apiKey.isBlank()) throw IllegalArgumentException("أدخل كود السهم ومفتاح DataCedar.")
                fun getJson(url: String): JSONObject {
                    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15000
                        readTimeout = 25000
                        setRequestProperty("X-API-Key", apiKey)
                        setRequestProperty("Accept", "application/json")
                    }
                    try {
                        val code = connection.responseCode
                        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                        val body = stream.bufferedReader().use { it.readText() }
                        if (code !in 200..299) {
                            val detail = try { JSONObject(body).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                            throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "فشل الاتصال بمزود البيانات (HTTP $code).")
                        }
                        return JSONObject(body)
                    } finally { connection.disconnect() }
                }
                val ticker = java.net.URLEncoder.encode(symbol.trim().uppercase(Locale.ROOT), "UTF-8")
                val search = getJson("https://api.datacedar.com/v3/search?q=$ticker&exchange=egx&limit=10")
                val searchData = search.optJSONArray("data") ?: JSONArray()
                val match = (0 until searchData.length()).map { searchData.getJSONObject(it) }
                    .firstOrNull { it.optString("symbol").equals(symbol.trim(), true) }
                    ?: (0 until searchData.length()).firstNotNullOfOrNull { i -> searchData.optJSONObject(i) }
                    ?: throw IllegalStateException("لم يتم العثور على السهم في تغطية EGX لدى مزود البيانات.")
                val sid = match.optString("sid").takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("المزود لم يرجع معرف السهم.")
                val encodedSid = java.net.URLEncoder.encode(sid, "UTF-8")
                val barsJson = getJson("https://api.datacedar.com/v3/stocks/$encodedSid/bars?timeframe=1d&adjustment=all&order=asc&limit=50000")
                val rows = barsJson.optJSONArray("data") ?: JSONArray()
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val parsed = mutableListOf<Candle>()
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val ts = row.optString("ts_ms", row.optString("t"))
                    val date = try {
                        if (ts.toLongOrNull() != null) dateFormat.format(Date(ts.toLong()))
                        else ts.take(10)
                    } catch (_: Exception) { continue }
                    val open = row.optDouble("open", row.optDouble("o", Double.NaN))
                    val high = row.optDouble("high", row.optDouble("h", Double.NaN))
                    val low = row.optDouble("low", row.optDouble("l", Double.NaN))
                    val close = row.optDouble("close", row.optDouble("c", Double.NaN))
                    val volume = row.optString("volume", row.optString("v", "0")).toDoubleOrNull() ?: 0.0
                    if (date.isNotBlank() && open.isFinite() && high.isFinite() && low.isFinite() && close.isFinite() &&
                        open > 0 && close > 0 && high >= maxOf(open, close, low) && low <= minOf(open, close)) {
                        parsed.add(Candle(date, open, high, low, close, volume))
                    }
                }
                val ordered = parsed.distinctBy { it.date }.sortedBy { it.date }
                val note = if (ordered.isEmpty()) "لم يرجع المزود جلسات تاريخية لهذا السهم. تحقق من التغطية والرمز."
                    else "تم تحميل ${ordered.size} جلسة يومية للسهم $sid. مصدر البيانات: DataCedar؛ راجع حدود خطتك وتاريخ آخر تحديث."
                runOnUiThread { callback(ordered, note) }
            } catch (e: Exception) {
                runOnUiThread { callback(emptyList(), e.message ?: "حدث خطأ أثناء تحميل البيانات.") }
            }
        }.start()
    }

    private fun openTradingView(symbol: String) {
        val url = "https://www.tradingview.com/chart/?symbol=" + Uri.encode("EGX:$symbol")
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun readCsv(uri: Uri): Pair<List<Candle>, String> {
        return try {
            val input = contentResolver.openInputStream(uri)
                ?: return emptyList<Candle>() to "تعذر فتح الملف."
            val lines = BufferedReader(InputStreamReader(input)).use { it.readLines() }
            if (lines.size < 2) return emptyList<Candle>() to "الملف فارغ أو لا يحتوي على بيانات."

            val delimiter = when {
                lines.first().count { it == ';' } > lines.first().count { it == ',' } -> ';'
                lines.first().contains('\t') -> '\t'
                else -> ','
            }
            val header = splitCsvLine(lines.first(), delimiter).map { it.trim().lowercase(Locale.ROOT) }
            fun indexOf(vararg names: String, fallback: Int): Int {
                val found = header.indexOfFirst { h -> names.any { h == it || h.contains(it) } }
                return if (found >= 0) found else fallback
            }
            val dateI = indexOf("date", "التاريخ", fallback = 0)
            val openI = indexOf("open", "الفتح", fallback = 1)
            val highI = indexOf("high", "الأعلى", "اعلى", fallback = 2)
            val lowI = indexOf("low", "الأدنى", "ادنى", fallback = 3)
            val closeI = indexOf("close", "الإغلاق", "الاغلاق", fallback = 4)
            val volumeI = indexOf("volume", "الحجم", fallback = 5)
            val parsed = lines.drop(1).mapNotNull { line ->
                val cells = splitCsvLine(line, delimiter)
                val needed = listOf(dateI, openI, highI, lowI, closeI, volumeI)
                if (cells.size <= (needed.maxOrNull() ?: 5)) null else try {
                    val candle = Candle(
                        cells[dateI].trim(),
                        parseNumber(cells[openI]),
                        parseNumber(cells[highI]),
                        parseNumber(cells[lowI]),
                        parseNumber(cells[closeI]),
                        parseNumber(cells[volumeI])
                    )
                    if (candle.date.isBlank() || candle.open <= 0.0 || candle.close <= 0.0 ||
                        candle.high < maxOf(candle.open, candle.close, candle.low) ||
                        candle.low > minOf(candle.open, candle.close)) null else candle
                } catch (_: Exception) { null }
            }.sortedBy { dateSortKey(it.date) }.distinctBy { it.date }

            val note = when {
                parsed.size < 26 -> "تمت قراءة ${parsed.size} جلسة فقط. يلزم 26 جلسة على الأقل."
                parsed.size < 50 -> "تم استيراد ${parsed.size} جلسة. بعض المؤشرات طويلة المدى ستظهر عند توافر 50 جلسة."
                else -> "تم استيراد ${parsed.size} جلسة والتحقق من سلامة الأسعار."
            }
            parsed to note
        } catch (e: Exception) {
            emptyList<Candle>() to "تعذرت قراءة الملف: ${e.message ?: "صيغة غير صحيحة"}"
        }
    }

    private fun parseNumber(raw: String): Double {
        val cleaned = raw.trim().replace("\"", "").replace(",", "").replace(" ", "")
        return cleaned.toDouble()
    }

    private fun splitCsvLine(line: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    cell.append('"'); i++
                }
                ch == '"' -> quoted = !quoted
                ch == delimiter && !quoted -> {
                    out.add(cell.toString()); cell.clear()
                }
                else -> cell.append(ch)
            }
            i++
        }
        out.add(cell.toString())
        return out
    }

    private fun dateSortKey(value: String): String {
        val s = value.trim()
        val iso = Regex("^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})").find(s)
        if (iso != null) return "${iso.groupValues[1].padStart(4, '0')}-${iso.groupValues[2].padStart(2, '0')}-${iso.groupValues[3].padStart(2, '0')}"
        val dmy = Regex("^(\\d{1,2})[-/](\\d{1,2})[-/](\\d{4})").find(s)
        if (dmy != null) return "${dmy.groupValues[3]}-${dmy.groupValues[2].padStart(2, '0')}-${dmy.groupValues[1].padStart(2, '0')}"
        return s
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzerScreen(importCsv: (Uri) -> Pair<List<Candle>, String>, fetchHistory: (String, String, (List<Candle>, String) -> Unit) -> Unit, openTradingView: (String) -> Unit) {
    var symbol by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var candles by remember { mutableStateOf(emptyList<Candle>()) }
    var message by remember { mutableStateOf("اكتب كود السهم واستورد ملف الأسعار التاريخية CSV.") }
    var result by remember { mutableStateOf<Analysis?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val loaded = importCsv(uri)
            candles = loaded.first
            message = loaded.second
            result = if (candles.size >= 26) TechnicalAnalyzer.analyze(candles) else null
        }
    }

    Scaffold(
        containerColor = Page,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("EGX ANALYZER", color = Green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("محلل الأسهم المصرية", color = Navy, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                    }
                },
                actions = {
                    Icon(Icons.Default.ShowChart, null, tint = Green, modifier = Modifier.padding(end = 18.dp).size(30.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Page)
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = Navy), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("لوحة تحليل EGX", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("أدخل رمز التداول وارفع بيانات السهم لتحصل على قراءة فنية منظمة.", color = Color(0xFFB9C9D8), fontSize = 12.sp)
                    Spacer(Modifier.height(13.dp))
                    Row(
                        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Search, null, tint = Muted)
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = symbol,
                            onValueChange = { symbol = it.uppercase(Locale.ROOT).take(16) },
                            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                            singleLine = true,
                            decorationBox = { inner ->
                                if (symbol.isBlank()) Text("كود السهم مثال: COMI", color = Muted)
                                inner()
                            }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it.trim() },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("مفتاح DataCedar API") },
                        placeholder = { Text("أدخل مفتاحك الشخصي") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF8FCDB7), unfocusedBorderColor = Color(0xFF8FA3B5),
                            focusedLabelColor = Color(0xFFB9C9D8), unfocusedLabelColor = Color(0xFFB9C9D8)
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (!loading) {
                                loading = true
                                fetchHistory(symbol, apiKey) { loaded, note ->
                                    candles = loaded
                                    message = note
                                    result = if (candles.size >= 26) TechnicalAnalyzer.analyze(candles) else null
                                    loading = false
                                }
                            }
                        },
                        enabled = symbol.isNotBlank() && apiKey.isNotBlank() && !loading,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Green),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (loading) "جارٍ تحميل التريند..." else "تحميل التريند وتحليل السهم تلقائيًا", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(7.dp))
                    OutlinedButton(
                        onClick = { if (symbol.isNotBlank()) openTradingView(symbol) },
                        enabled = symbol.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("فتح السهم على TradingView") }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("text/*", "text/csv", "application/vnd.ms-excel")) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Green),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.UploadFile, null)
                        Spacer(Modifier.width(8.dp))
                        Text("استيراد بيانات السهم CSV", fontWeight = FontWeight.Bold)
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
                Text("ماذا يقدم المحلل؟", color = Navy, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Feature("الاتجاه والزخم", "اتجاه المتوسطات وRSI وMACD.")
                Feature("الدعوم والمقاومات", "مستويات مستخرجة من القمم والقيعان المحورية.")
                Feature("النماذج الفنية", "نماذج شموع أساسية مع وصف مختصر.")
                Feature("إدارة المخاطر", "سيناريوهات سعرية ووقف افتراضي ونسبة عائد إلى مخاطرة.")
                Feature("السيولة", "مقارنة حجم آخر جلسة بمتوسط العشرين جلسة السابقة.")
                Spacer(Modifier.height(8.dp))
                Text("صيغة الملف: Date,Open,High,Low,Close,Volume", color = Muted, fontSize = 11.sp)
            } else {
                Text("تحليل ${symbol.ifBlank { "السهم المستورد" }}", color = Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("آخر جلسة: ${a.last.date}", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric("آخر إغلاق", money(a.last.close), Modifier.weight(1f))
                    Metric("الاتجاه", a.trend, Modifier.weight(1f))
                }
                Spacer(Modifier.height(9.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(15.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("التقييم الفني التجميعي", color = Muted, fontSize = 12.sp)
                                Text("${a.score} / 100", color = scoreColor(a.scoreLabel), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            Text(a.scoreLabel, color = scoreColor(a.scoreLabel), fontWeight = FontWeight.Bold)
                        }
                        LinearProgressIndicator(
                            progress = { a.score / 100f },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = scoreColor(a.scoreLabel),
                            trackColor = Page
                        )
                        Spacer(Modifier.height(9.dp))
                        Text(
                            if (a.sessions < 50) "تنبيه: أقل من 50 جلسة؛ اتجاه SMA50 غير متاح والتقييم مبدئي." else "عدد الجلسات: ${a.sessions} جلسة${if (a.volumeAvailable) " • بيانات الحجم متاحة" else " • بيانات الحجم غير مكتملة"}",
                            color = Amber, fontSize = 11.sp, lineHeight = 16.sp
                        )
                        Spacer(Modifier.height(7.dp))
                        a.scoreParts.forEach { part ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(part.name, Modifier.weight(1f), color = Navy, fontSize = 11.sp)
                                Text("${part.points}/${part.maxPoints}", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                SectionTitle("الدعوم والمقاومات")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric("الدعوم الأقرب", a.supports.joinToString("\n") { money(it) }.ifBlank { "غير متاح" }, Modifier.weight(1f))
                    Metric("المقاومات الأقرب", a.resistances.joinToString("\n") { money(it) }.ifBlank { "غير متاح" }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                SectionTitle("المؤشرات الفنية")
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Metric("RSI 14", one(a.rsi), Modifier.weight(1f))
                    Metric("SMA 20", a.sma20?.let(::money) ?: "—", Modifier.weight(1f))
                    Metric("SMA 50", a.sma50?.let(::money) ?: "—", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Metric("MACD", a.macd?.let(::money) ?: "—", Modifier.weight(1f))
                    Metric("إشارة MACD", a.macdSignal?.let(::money) ?: "—", Modifier.weight(1f))
                    Metric("ATR 14", money(a.atr), Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                SectionTitle("السيولة والبنية السعرية")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric("حجم التداول", "${one(a.volumeRatio)} × متوسط 20 جلسة", Modifier.weight(1f))
                    Metric("نطاق بولينجر", "${money(a.bbLower)} - ${money(a.bbUpper)}", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                SectionTitle("النماذج المرصودة")
                ResultCard(a.patterns.joinToString("\n"))
                Spacer(Modifier.height(12.dp))
                SectionTitle("سيناريوهات سعرية افتراضية")
                ResultCard(
                    "قصير المدى: ${money(a.targets[0])}\n" +
                    "متوسط المدى: ${money(a.targets[1])}\n" +
                    "طويل المدى: ${money(a.targets[2])}\n" +
                    "مستوى إبطال السيناريو: ${money(a.invalidation)}\n" +
                    "العائد المحتمل / المخاطرة: ${a.riskReward?.let(::one) ?: "غير متاح"}"
                )
                Spacer(Modifier.height(8.dp))
                Text("السيناريوهات حسابات فنية افتراضية وليست توصية شراء أو بيع. قد تفشل المؤشرات في توقع حركة السعر.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "مصدر البيانات: ${if (candles.isNotEmpty()) message else "لم يتم تحميل بيانات بعد."} الأسعار تاريخية وليست لحظية. TradingView يُستخدم لعرض الرسم فقط؛ التحليل يعتمد على بيانات DataCedar أو ملف CSV.",
                color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center, lineHeight = 16.sp
            )
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, color = Navy, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(7.dp))
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(11.dp)) {
            Text(label, color = Muted, fontSize = 10.sp)
            Spacer(Modifier.height(5.dp))
            Text(value, color = Navy, fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 17.sp)
        }
    }
}

@Composable
fun ResultCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
        Text(text, Modifier.fillMaxWidth().padding(14.dp), color = Navy, fontSize = 13.sp, lineHeight = 22.sp)
    }
}

@Composable
fun Feature(title: String, desc: String) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Insights, null, tint = Green, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, color = Navy, fontWeight = FontWeight.Bold)
                Text(desc, color = Muted, fontSize = 11.sp)
            }
        }
    }
}

private fun scoreColor(label: String): Color = when (label) {
    "إيجابي فنيًا", "يميل للإيجابية" -> Green
    "ضعيف فنيًا" -> Red
    else -> Amber
}
private fun money(v: Double) = String.format(Locale.US, "%.2f", v)
private fun one(v: Double) = String.format(Locale.US, "%.1f", v)
