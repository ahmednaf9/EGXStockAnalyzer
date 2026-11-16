package com.egx.analyzer

import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import android.os.Bundle
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
        setContent { MaterialTheme { AnalyzerScreen(::readCsv, ::fetchFromDataCedar) } }
    }

    private fun fetchFromDataCedar(symbol: String, apiKey: String, callback: (List<Candle>, String) -> Unit) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                fun getJson(url: String): JSONObject {
                    connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15000
                        readTimeout = 20000
                        setRequestProperty("X-API-Key", apiKey)
                        setRequestProperty("Accept", "application/json")
                    }
                    val code = connection!!.responseCode
                    val stream = if (code in 200..299) connection!!.inputStream else connection!!.errorStream
                    val body = stream.bufferedReader().use { it.readText() }
                    connection!!.disconnect()
                    connection = null
                    if (code !in 200..299) {
                        val msg = try { JSONObject(body).getJSONObject("error").optString("message", "HTTP $code") } catch (_: Exception) { "HTTP $code" }
                        throw IllegalStateException(msg)
                    }
                    return JSONObject(body)
                }

                val query = URLEncoder.encode(symbol.trim(), "UTF-8")
                fun searchSymbols(filter: String): org.json.JSONArray {
                    val response = getJson("https://api.datacedar.com/v3/search?q=$query&$filter&limit=50")
                    return response.optJSONArray("data") ?: org.json.JSONArray()
                }
                var matches = searchSymbols("exchange=egx")
                if (matches.length() == 0) matches = searchSymbols("country=EG")
                var sid: String? = null
                val requested = symbol.trim()
                val available = mutableListOf<String>()
                for (i in 0 until matches.length()) {
                    val item = matches.optJSONObject(i) ?: continue
                    val ticker = item.optString("symbol").trim()
                    val itemSid = item.optString("sid").trim()
                    val name = item.optString("name").trim()
                    if (ticker.isNotBlank()) available.add(ticker + if (name.isNotBlank()) " - $name" else "")
                    if (ticker.equals(requested, true) || itemSid.substringAfter(":").equals(requested, true)) {
                        sid = itemSid.takeIf { it.isNotBlank() }
                        break
                    }
                }
                if (sid.isNullOrBlank()) {
                    val suggestions = available.take(5).joinToString("، ")
                    val reason = if (matches.length() == 0) "DataCedar لم يُرجع نتائج لسوق EGX. تحقق من المفتاح واتصال الإنترنت." else "لم يتم العثور على الرمز $requested في نتائج EGX. جرّب كود السهم كما يظهر في DataCedar. أمثلة: $suggestions"
                    throw IllegalStateException(reason)
                }
                val from = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().apply { add(Calendar.YEAR, -1) }.time)
                val bars = getJson("https://api.datacedar.com/v3/stocks/${URLEncoder.encode(sid, "UTF-8")}/bars?timeframe=1d&from=$from&order=asc&limit=50000&adjustment=raw")
                val data = bars.optJSONArray("data") ?: throw IllegalStateException("لم تصل بيانات تاريخية لهذا السهم.")
                val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val rows = mutableListOf<Candle>()
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val ts = item.optString("ts_ms").toLongOrNull() ?: continue
                    val candle = Candle(
                        formatter.format(Date(ts)),
                        item.optDouble("open", Double.NaN),
                        item.optDouble("high", Double.NaN),
                        item.optDouble("low", Double.NaN),
                        item.optDouble("close", Double.NaN),
                        item.optString("volume").toDoubleOrNull() ?: 0.0
                    )
                    if (candle.open.isFinite() && candle.high.isFinite() && candle.low.isFinite() &&
                        candle.close.isFinite() && candle.open > 0 && candle.close > 0 &&
                        candle.high >= maxOf(candle.open, candle.close, candle.low) &&
                        candle.low <= minOf(candle.open, candle.close)) rows.add(candle)
                }
                val note = if (rows.size < 26) "وصلت ${rows.size} جلسة فقط؛ يلزم 26 جلسة على الأقل للتحليل." else
                    "تم جلب ${rows.size} جلسة يومية من DataCedar للسهم ${sid}."
                Handler(Looper.getMainLooper()).post { callback(rows, note) }
            } catch (e: Exception) {
                val message = e.message ?: "تعذر الاتصال بـ DataCedar."
                Handler(Looper.getMainLooper()).post { callback(emptyList(), "فشل التحميل: $message") }
            } finally {
                connection?.disconnect()
            }
        }.start()
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
fun AnalyzerScreen(importCsv: (Uri) -> Pair<List<Candle>, String>, fetchData: (String, String, (List<Candle>, String) -> Unit) -> Unit) {
    var symbol by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
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
                    Spacer(Modifier.height(10.dp))
                    BasicTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it.trim() },
                        modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(14.dp),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        decorationBox = { inner ->
                            if (apiKey.isBlank()) Text("مفتاح DataCedar API", color = Muted)
                            inner()
                        }
                    )
                    Spacer(Modifier.height(9.dp))
                    Button(
                        onClick = {
                            if (symbol.isBlank() || apiKey.isBlank()) {
                                message = "اكتب كود السهم ومفتاح DataCedar أولًا."
                            } else {
                                message = "جارٍ جلب البيانات اليومية من DataCedar..."
                                fetchData(symbol, apiKey) { loaded, note ->
                                    candles = loaded
                                    message = note
                                    result = if (loaded.size >= 26) TechnicalAnalyzer.analyze(loaded) else null
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Green),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, null)
                        Spacer(Modifier.width(8.dp))
                        Text("جلب وتحليل مباشر من DataCedar", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("text/*", "text/csv", "application/vnd.ms-excel")) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.UploadFile, null)
                        Spacer(Modifier.width(8.dp))
                        Text("أو استيراد ملف CSV", fontWeight = FontWeight.Bold)
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
                "مصدر البيانات: DataCedar للتحميل المباشر أو ملف CSV يدوي. البيانات تاريخية وليست أسعارًا لحظية؛ تحقق من تاريخها وتغطيتها قبل قراءة التحليل.",
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
