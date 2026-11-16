package com.egx.analyzer

import android.net.Uri
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import java.util.zip.ZipInputStream
import java.io.ByteArrayOutputStream
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
        setContent { MaterialTheme { AnalyzerScreen(::readFile, ::readChartImage) } }
    }

    private fun readChartImage(uri: Uri): Pair<String, String> {
        return try {
            val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: return "" to "تعذر فتح صورة الشارت."
            val report = ChartImageAnalyzer.analyze(bitmap)
            bitmap.recycle()
            report
        } catch (e: Exception) {
            "" to "تعذر تحليل الصورة: ${e.message ?: "صيغة غير مدعومة"}"
        }
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
            val closeI = indexOf("close", "price", "last", "الإغلاق", "الاغلاق", "السعر", fallback = 4)
            val volumeI = indexOf("volume", "vol.", "vol", "الحجم", fallback = 5)
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
                parsed.size < 15 -> "تم استيراد ${parsed.size} جلسة بنجاح، لكن التحليل الفني يحتاج 26 جلسة على الأقل. أضف بيانات تاريخية أكثر."
                parsed.size < 50 -> "تم استيراد ${parsed.size} جلسة. بعض المؤشرات طويلة المدى ستظهر عند توافر 50 جلسة."
                else -> "تم استيراد ${parsed.size} جلسة والتحقق من سلامة الأسعار."
            }
            parsed to note
        } catch (e: Exception) {
            emptyList<Candle>() to "تعذرت قراءة الملف: ${e.message ?: "صيغة غير صحيحة"}"
        }
    }

    private fun readFile(uri: Uri): Pair<List<Candle>, String> {
        val n = contentResolver.query(uri,null,null,null,null)?.use { c ->
            val i=c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if(i>=0 && c.moveToFirst()) c.getString(i) else ""
        }.orEmpty()
        return if(n.endsWith(".xlsx",true) || contentResolver.getType(uri)?.contains("spreadsheetml")==true) readExcel(uri) else readCsv(uri)
    }

    private fun readExcel(uri: Uri): Pair<List<Candle>, String> = try {
        val parts=mutableMapOf<String,String>()
        ZipInputStream(contentResolver.openInputStream(uri) ?: error("تعذر فتح الملف")).use { z ->
            var e=z.nextEntry
            while(e!=null) {
                if(e.name=="xl/sharedStrings.xml" || e.name=="xl/worksheets/sheet1.xml") {
                    val b=ByteArrayOutputStream(); z.copyTo(b); parts[e.name]=b.toString("UTF-8")
                }
                z.closeEntry(); e=z.nextEntry
            }
        }
        val sheet=parts["xl/worksheets/sheet1.xml"] ?: error("ورقة البيانات الأولى غير موجودة")
        val shared=Regex("<si[^>]*>(.*?)</si>",RegexOption.DOT_MATCHES_ALL).findAll(parts["xl/sharedStrings.xml"] ?: "")
            .map { m -> Regex("<t[^>]*>(.*?)</t>",RegexOption.DOT_MATCHES_ALL).findAll(m.groupValues[1]).joinToString("") { it.groupValues[1] } }.toList()
        fun col(ref:String):Int { var v=0; ref.takeWhile{it.isLetter()}.uppercase(Locale.ROOT).forEach{v=v*26+(it-'A'+1)}; return v-1 }
        val rows=Regex("<row[^>]*>(.*?)</row>",RegexOption.DOT_MATCHES_ALL).findAll(sheet).map { m ->
            val row=mutableMapOf<Int,String>()
            Regex("<c([^>]*)>(.*?)</c>",RegexOption.DOT_MATCHES_ALL).findAll(m.groupValues[1]).forEach { cell ->
                val ref=Regex("r=\"([A-Z]+[0-9]+)\"").find(cell.groupValues[1])?.groupValues?.get(1) ?: return@forEach
                val type=Regex("t=\"([^\"]+)\"").find(cell.groupValues[1])?.groupValues?.get(1) ?: ""
                val raw=Regex("<v>(.*?)</v>",RegexOption.DOT_MATCHES_ALL).find(cell.groupValues[2])?.groupValues?.get(1) ?: ""
                row[col(ref)]=if(type=="s") shared.getOrNull(raw.toIntOrNull() ?: -1) ?: "" else raw
            }; row
        }.toList()
        if(rows.size<2) error("لا توجد بيانات في ملف Excel")
        val h=rows.first().mapValues{it.value.lowercase(Locale.ROOT).trim()}
        fun ix(vararg names:String,fallback:Int)=h.entries.firstOrNull{(_,v)->names.any{v==it || v.contains(it)}}?.key ?: fallback
        val d=ix("date","التاريخ",fallback=0); val o=ix("open","الفتح",fallback=1)
        val hi=ix("high","الأعلى","اعلى",fallback=2); val lo=ix("low","الأدنى","ادنى",fallback=3)
        val c=ix("close","الإغلاق","الاغلاق",fallback=4); val v=ix("volume","الحجم",fallback=5)
        val data=rows.drop(1).mapNotNull { r -> try {
            val rawDate=r[d].orEmpty()
            val date=rawDate.toDoubleOrNull()?.let { serial ->
                val cal=Calendar.getInstance(); cal.timeInMillis=(serial-25569.0).toLong()*86400000L
                SimpleDateFormat("yyyy-MM-dd",Locale.US).format(cal.time)
            } ?: rawDate
            val x=Candle(date,parseNumber(r[o]?: ""),parseNumber(r[hi]?: ""),parseNumber(r[lo]?: ""),parseNumber(r[c]?: ""),parseNumber(r[v]?: "0"))
            if(date.isNotBlank() && x.open>0 && x.close>0 && x.high>=maxOf(x.open,x.close,x.low) && x.low<=minOf(x.open,x.close)) x else null
        } catch(_:Exception){null} }.sortedBy{dateSortKey(it.date)}.distinctBy{it.date}
        data to "تم استيراد ${data.size} جلسة من Excel."
    } catch(e:Exception){ emptyList<Candle>() to "تعذرت قراءة Excel: ${e.message ?: "تأكد من الملف."}" }

    private fun parseNumber(raw: String): Double {
        val cleaned = raw.trim().replace("\"", "").replace(",", "").replace(" ", "")
        val suffix = cleaned.lastOrNull()?.uppercaseChar()
        val number = if (suffix == 'K' || suffix == 'M' || suffix == 'B') cleaned.dropLast(1).toDouble() else cleaned.toDouble()
        return number * when (suffix) {
            'K' -> 1_000.0
            'M' -> 1_000_000.0
            'B' -> 1_000_000_000.0
            else -> 1.0
        }
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
fun AnalyzerScreen(importFile: (Uri) -> Pair<List<Candle>, String>, importChart: (Uri) -> Pair<String, String>) {
    var symbol by remember { mutableStateOf("") }
    var candles by remember { mutableStateOf(emptyList<Candle>()) }
    var message by remember { mutableStateOf("اكتب كود السهم واستورد ملف الأسعار التاريخية CSV.") }
    var result by remember { mutableStateOf<Analysis?>(null) }
    var period by remember { mutableStateOf("يومي") }
    var drawTrend by remember { mutableStateOf(false) }
    var trendPoints by remember { mutableStateOf(emptyList<Pair<Int,Double>>()) }\n    var imageReport by remember { mutableStateOf("") }\n    var imageMessage by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val loaded = importFile(uri)
            candles = loaded.first
            message = loaded.second
            result = if (candles.size >= 15) TechnicalAnalyzer.analyze(aggregateCandles(candles,period)) else null
            trendPoints=emptyList()
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val report = importChart(uri)
            imageReport = report.first
            imageMessage = report.second
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
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("text/*","text/csv","application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.UploadFile, null)
                        Spacer(Modifier.width(8.dp))
                        Text("استيراد CSV أو Excel", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { imagePicker.launch("image/*") },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.ImageSearch, null)
                        Spacer(Modifier.width(8.dp))
                        Text("تحليل صورة الشارت دون إنترنت", fontWeight = FontWeight.Bold)
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
            if (imageMessage.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("تحليل صورة الشارت • ${period}", color = Navy, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(imageMessage, color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                        if (imageReport.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(imageReport, color = Navy, fontSize = 13.sp, lineHeight = 21.sp)
                        }
                    }
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
                Text("الأعمدة المطلوبة: Date, Open, High, Low, Close, Volume", color = Muted, fontSize = 11.sp)
            } else {
                Text("تحليل ${symbol.ifBlank { "السهم المستورد" }}", color = Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("آخر جلسة: ${a.last.date}", color = Muted, fontSize = 11.sp)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf("يومي","أسبوعي","شهري").forEach { p ->
                        FilterChip(selected=period==p,onClick={
                            val grouped=aggregateCandles(candles,p)
                            if(grouped.size>=26) {
                                period=p
                                result=TechnicalAnalyzer.analyze(grouped)
                                trendPoints=emptyList()
                            } else {
                                message="الفترة $p تحتوي على ${grouped.size} شمعة فقط؛ يلزم 15 على الأقل. تم الاحتفاظ بالفترة الحالية."
                            }
                        },label={Text(p)})
                    }
                }
                Text("الفترة: ${period}",color=Muted,fontSize=11.sp)
                PriceTrendChart(aggregateCandles(candles,period),trendPoints,drawTrend) { i,price ->
                    trendPoints=if(trendPoints.size>=2) listOf(i to price) else trendPoints+(i to price)
                    if(trendPoints.size>=2) drawTrend=false
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={drawTrend=true;trendPoints=emptyList()},modifier=Modifier.weight(1f)){Text("رسم خط اتجاه")}
                    OutlinedButton(onClick={drawTrend=false;trendPoints=emptyList()},modifier=Modifier.weight(1f)){Text("مسح الخط")}
                }
                if(drawTrend) Text("اضغط على نقطتين في الرسم لرسم خط الاتجاه.",color=Green,fontSize=11.sp)

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
                SectionTitle("الخلاصة الفنية")
                ResultCard(
                    "الحالة: ${a.scoreLabel}\n" +
                    "الاتجاه الحالي: ${a.trend}\n" +
                    "درجة توافق المؤشرات: ${a.score}/100\n" +
                    when {
                        a.score >= 75 && a.trend == "صاعد" ->
                            "القراءة: أغلب المؤشرات متوافقة مع اتجاه صاعد، لكن يلزم متابعة تأكيد الحركة."
                        a.score <= 35 && a.trend == "هابط" ->
                            "القراءة: توجد ضغوط فنية هابطة؛ راجع مستويات الدعم ومؤشرات الزخم."
                        a.score >= 62 ->
                            "القراءة: توجد إشارات إيجابية جزئيًا، لكنها لا تزال بحاجة إلى تأكيد."
                        else ->
                            "القراءة: الإشارات مختلطة أو غير كافية لتحديد اتجاه واضح."
                    }
                )
                Spacer(Modifier.height(8.dp))
                Text("هذه قراءة تعليمية آلية للبيانات التاريخية وليست توصية شراء أو بيع أو ضمانًا للربح.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
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
                "مصدر البيانات: ملف CSV أو Excel الذي تستورده يدويًا. البيانات تاريخية؛ تحقق من تاريخها وتغطيتها قبل قراءة التحليل.",
                color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center, lineHeight = 16.sp
            )
            Spacer(Modifier.height(22.dp))
        }
    }
}

private fun aggregateCandles(input:List<Candle>,period:String):List<Candle>{
    if(period=="يومي") return input
    val groups=input.sortedBy{it.date}.groupBy{c->
        val p=c.date.take(10).replace('/','-').split('-')
        val y=p.getOrNull(0)?.toIntOrNull()?:0; val m=p.getOrNull(1)?.toIntOrNull()?:1; val d=p.getOrNull(2)?.toIntOrNull()?:1
        if(period=="شهري") "%04d-%02d".format(Locale.US,y,m) else {
            val cal=Calendar.getInstance(Locale.US);cal.clear();cal.set(y,(m-1).coerceIn(0,11),d.coerceIn(1,28))
            "%04d-W%02d".format(Locale.US,y,cal.get(Calendar.WEEK_OF_YEAR))
        }
    }
    return groups.toSortedMap().values.map{r->Candle(r.first().date,r.first().open,r.maxOf{it.high},r.minOf{it.low},r.last().close,r.sumOf{it.volume})}
}

@Composable
private fun PriceTrendChart(data:List<Candle>,points:List<Pair<Int,Double>>,drawing:Boolean,onTap:(Int,Double)->Unit){
    if(data.isEmpty()) return
    val low=data.minOf{it.low};val high=data.maxOf{it.high};val range=(high-low).coerceAtLeast(0.000001)
    Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(14.dp)){
        Column(Modifier.fillMaxWidth().padding(12.dp)){
            Text("الرسم السعري وخط الاتجاه",color=Navy,fontWeight=FontWeight.Bold,fontSize=13.sp)
            Canvas(Modifier.fillMaxWidth().height(180.dp).pointerInput(data,drawing,points){
                detectTapGestures{p->if(drawing&&data.size>1){
                    val i=((p.x/size.width)*data.lastIndex).toInt().coerceIn(0,data.lastIndex)
                    onTap(i,high-(p.y/size.height).coerceIn(0f,1f)*range)
                }}
            }){
                val dx=size.width/data.lastIndex.coerceAtLeast(1);val path=Path()
                data.forEachIndexed{i,c->val y=size.height-((c.close-low)/range).toFloat()*size.height
                    if(i==0)path.moveTo(i*dx,y) else path.lineTo(i*dx,y)}
                drawPath(path,Green,style=Stroke(width=3f))
                if(points.size==2){
                    fun xy(p:Pair<Int,Double>)=Offset(p.first.coerceIn(0,data.lastIndex)*dx,size.height-((p.second-low)/range).toFloat()*size.height)
                    drawLine(Red,xy(points[0]),xy(points[1]),strokeWidth=4f)
                    drawCircle(Red,6f,xy(points[0]));drawCircle(Red,6f,xy(points[1]))
                }
            }
            Text("آخر إغلاق: "+String.format(Locale.US,"%.2f",data.last().close),color=Muted,fontSize=10.sp)
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
