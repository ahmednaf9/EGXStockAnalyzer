package com.egx.analyzer

import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import android.os.Bundle
import android.os.Build
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
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

data class ImportedStockFile(val candles: List<Candle>, val message: String, val symbol: String)

data class Candle(
    val date: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

private fun Candle.isValidForAnalysis(): Boolean =
    date.isNotBlank() &&
    open.isFinite() && high.isFinite() && low.isFinite() &&
    close.isFinite() && volume.isFinite() &&
    open > 0.0 && high > 0.0 && low > 0.0 && close > 0.0 &&
    volume >= 0.0 &&
    high >= maxOf(open, close, low) &&
    low <= minOf(open, close)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(243, 246, 249)
        window.navigationBarColor = android.graphics.Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        setContent { MaterialTheme { AnalyzerScreen(::readFile) } }
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
                    if (candle.isValidForAnalysis()) candle else null
                } catch (_: Exception) { null }
            }.sortedBy { dateSortKey(it.date) }.distinctBy { dateSortKey(it.date) }

            val note = when {
                parsed.size < 35 -> "تم استيراد ${parsed.size} شمعة فقط؛ يلزم 35 شمعة على الأقل لحساب RSI وMACD بصورة أفضل."
                parsed.size < 50 -> "تم استيراد ${parsed.size} جلسة. التحليل متاح، لكن SMA 50 والتقييم القوي يحتاجان 50 جلسة على الأقل."
                else -> "تم استيراد ${parsed.size} جلسة والتحقق من سلامة الأسعار."
            }
            parsed to note
        } catch (e: Exception) {
            emptyList<Candle>() to "تعذرت قراءة الملف: ${e.message ?: "صيغة غير صحيحة"}"
        }
    }

    private fun readFile(uri: Uri): ImportedStockFile {
        val fileName = contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else ""
        }.orEmpty()
        val imported = if (fileName.endsWith(".xlsx", true) ||
            contentResolver.getType(uri)?.contains("spreadsheetml") == true) readExcel(uri) else readCsv(uri)
        return ImportedStockFile(imported.first, imported.second, stockSymbolFromFileName(fileName))
    }

    private fun stockSymbolFromFileName(fileName: String): String {
        var base = fileName.substringAfterLast('/').substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9_-]"), "")
            .uppercase(Locale.ROOT)
        if (base.isBlank()) return ""
        base = base.replace(Regex("^(HISTORICAL[_-]?DATA|HISTORY[_-]?DATA|DATA)[_-]+"), "")
        base = base.replace(Regex("[_-](DAILY|WEEKLY|MONTHLY|HISTORICAL|HISTORY|PRICES|PRICE)$"), "")
            .trim('_', '-')
        if (base.isBlank() || base.all(Char::isDigit)) return ""
        return base.take(24)
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
            if(x.isValidForAnalysis()) x else null
        } catch(_:Exception){null} }.sortedBy{dateSortKey(it.date)}.distinctBy{dateSortKey(it.date)}
        data to when {
            data.size < 35 -> "تم استيراد ${data.size} شمعة من Excel؛ يلزم 35 شمعة على الأقل لحساب RSI وMACD بصورة أفضل."
            data.size < 50 -> "تم استيراد ${data.size} شمعة من Excel؛ SMA 50 والتقييم القوي يحتاجان 50 جلسة على الأقل."
            else -> "تم استيراد ${data.size} شمعة من Excel بعد التحقق من سلامة الأسعار."
        }
    } catch(e:Exception){ emptyList<Candle>() to "تعذرت قراءة Excel: ${e.message ?: "تأكد من الملف."}" }

    private fun parseNumber(raw: String): Double {
        var cleaned = raw.trim()
            .replace("\"", "")
            .replace(" ", "")
            .replace("\u00A0", "")
        val suffix = cleaned.lastOrNull()?.uppercaseChar()
        if (suffix == 'K' || suffix == 'M' || suffix == 'B') cleaned = cleaned.dropLast(1)
        if (cleaned.contains(',') && cleaned.contains('.')) {
            cleaned = if (cleaned.lastIndexOf(',') > cleaned.lastIndexOf('.')) {
                cleaned.replace(".", "").replace(',', '.')
            } else {
                cleaned.replace(",", "")
            }
        } else if (cleaned.contains(',')) {
            val decimals = cleaned.substringAfterLast(',').length
            cleaned = if (decimals in 1..2) cleaned.replace(',', '.') else cleaned.replace(",", "")
        }
        val number = cleaned.toDouble()
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
fun AnalyzerScreen(importFile: (Uri) -> ImportedStockFile) {
    var symbol by remember { mutableStateOf("") }
    var candles by remember { mutableStateOf(emptyList<Candle>()) }
    var message by remember { mutableStateOf("اكتب كود السهم واستورد ملف الأسعار التاريخية CSV.") }
    var result by remember { mutableStateOf<Analysis?>(null) }
    var period by remember { mutableStateOf("يومي") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val loaded = importFile(uri)
            candles = loaded.candles
            message = loaded.message
            symbol = loaded.symbol
            period = detectDataPeriod(candles)
            val grouped = aggregateCandles(candles, period)
            result = if (grouped.size >= 35) TechnicalAnalyzer.analyze(grouped) else null
            if (grouped.size < 35) message = "تم استيراد ${candles.size} شمعة، لكن الفترة ${period} تحتوي على ${grouped.size} شمعة فقط؛ يلزم 35 شمعة على الأقل للتحليل."
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
                    Text("ارفع ملف بيانات السهم وسيتم تحديد رمزه تلقائيًا من اسم الملف.", color = Color(0xFFB9C9D8), fontSize = 12.sp)
                    Spacer(Modifier.height(13.dp))
                    BasicTextField(
                        value = symbol,
                        onValueChange = { symbol = it.uppercase(Locale.ROOT) },
                        modifier = Modifier.fillMaxWidth()
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 16.dp),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Navy,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        decorationBox = { inner ->
                            if (symbol.isBlank()) Text("كود السهم", color = Muted, fontSize = 14.sp)
                            inner()
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("text/*","text/csv","application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Green)
                    ) {
                        Icon(Icons.Default.UploadFile, null)
                        Spacer(Modifier.width(8.dp))
                        Text("استيراد CSV أو Excel", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            symbol = ""
                            candles = emptyList()
                            message = "لم يتم تحميل أي ملف. يمكنك استيراد ملف سهم جديد."
                            result = null
                            period = "يومي"
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White)
                    ) {
                        Text("RESET", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
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
                Text("الأعمدة المطلوبة: Date, Open, High, Low, Close, Volume", color = Muted, fontSize = 11.sp)
            } else {
                Text("تحليل ${symbol.ifBlank { "السهم المستورد" }}", color = Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("آخر جلسة: ${a.last.date}", color = Muted, fontSize = 11.sp)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf("يومي","أسبوعي","شهري").forEach { p ->
                        FilterChip(
                            selected = period == p,
                            onClick = {
                                val grouped = aggregateCandles(candles, p)
                                if (grouped.size >= 35) {
                                    period = p
                                    result = TechnicalAnalyzer.analyze(grouped)
                                    message = "الفترة $p: ${grouped.size} شمعة جاهزة للتحليل."
                                } else {
                                    message = "الفترة $p تحتوي على ${grouped.size} شمعة فقط؛ يلزم 35 شمعة على الأقل. تم الاحتفاظ بالفترة الحالية."
                                }
                            },
                            label = { Text(p) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFD5BCEB),
                                selectedLabelColor = Navy,
                                containerColor = Color.White,
                                labelColor = Navy
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = period == p,
                                borderColor = Muted,
                                selectedBorderColor = Color(0xFFB18AD8)
                            )
                        )
                    }
                }
                Text("الفترة: ${period}",color=Muted,fontSize=11.sp)
                PriceTrendChart(aggregateCandles(candles, period))

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
                Spacer(Modifier.height(14.dp))
                SectionTitle("حالة السهم")
                val nearestSupport = a.supports.firstOrNull()
                val nearestResistance = a.resistances.firstOrNull()
                val secondResistance = a.resistances.getOrNull(1)
                val breakoutBuy = nearestResistance?.plus(a.atr * 0.2) ?: (a.last.close + a.atr * 0.5)
                val supportBuy = nearestSupport?.let { it + a.atr * 0.2 }
                val breakdownExit = nearestSupport?.minus(a.atr * 0.2) ?: a.invalidation
                val supportText = a.supports.take(3).joinToString(" - ") { money(it) }.ifBlank { "غير متاح" }
                val resistanceText = a.resistances.take(3).joinToString(" - ") { money(it) }.ifBlank { "غير متاح" }
                ResultCard(
                    "تصنيف السيولة: ${a.marketState}\n" +
                    "${a.marketStateReason}\n\n" +
                    "الدعوم: ${supportText}\n" +
                    "المقاومات: ${resistanceText}\n" +
                    "وقف الخسارة المقترح: ${money(a.invalidation)} (إغلاق أسفل المستوى)\n\n" +
                    "المستهدفات السعرية:\n" +
                    "قريب: ${money(a.targets[0])}\n" +
                    "متوسط: ${money(a.targets[1])}\n" +
                    "بعيد: ${money(a.targets[2])}\n\n" +
                    "خطة التعامل في الصعود:\n" +
                    "شراء اختراق: ${money(breakoutBuy)} بعد إغلاق أعلى المقاومة وتأكيد حجم التداول.\n" +
                    "شراء من الدعم: ${supportBuy?.let(::money) ?: "لا يوجد دعم قريب محدد"} بشرط ظهور ارتداد واضح.\n" +
                    "بيع/جني ربح: قرب المقاومة ${nearestResistance?.let(::money) ?: "غير متاحة"} ثم المستهدف القريب ${money(a.targets[0])}.\n\n" +
                    "خطة التعامل في الهبوط:\n" +
                    "بيع أو تخفيف: عند كسر الدعم والإغلاق أسفل ${money(breakdownExit)}.\n" +
                    "شراء بعد الهبوط: راقب الدعم ${nearestSupport?.let(::money) ?: "غير متاح"}؛ لا يتم الشراء إلا بعد ارتداد وتأكيد انعكاس، وليس لمجرد ملامسة الدعم.\n" +
                    "المقاومة التالية للبيع: ${secondResistance?.let(::money) ?: nearestResistance?.let(::money) ?: "غير متاحة"}."
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    "المستويات تقديرية ومحسوبة آليًا من البيانات التاريخية والفترة المختارة. نقاط الاختراق والارتداد تحتاج تأكيدًا بالإغلاق والسيولة؛ لا توجد ضمانات، ووقف الخسارة قد يتأثر بالفجوات السعرية.",
                    color = Muted, fontSize = 11.sp, lineHeight = 17.sp
                )
            Spacer(Modifier.height(14.dp))
            SectionTitle("التقييم الفني النهائي")
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(15.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("نتيجة توافق المؤشرات", color = Muted, fontSize = 11.sp)
                            Text(
                                a.finalRating,
                                color = finalRatingColor(a.finalRating),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Text(
                            (if (a.finalRatingScore > 0) "+" else "") + a.finalRatingScore + " / 100",
                            color = finalRatingColor(a.finalRating),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { ((a.finalRatingScore + 100) / 200f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(7.dp),
                        color = finalRatingColor(a.finalRating),
                        trackColor = Page
                    )
                    Spacer(Modifier.height(9.dp))
                    Text(a.finalRatingReason, color = Navy, fontSize = 12.sp, lineHeight = 19.sp)
                    Spacer(Modifier.height(8.dp))
                    a.finalRatingFactors.forEach { factor ->
                        Text("• $factor", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "التصنيف قراءة فنية آلية وليست أمر شراء أو بيع أو ضمانًا للربح.",
                        color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                    )
                }
            }
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

private fun dateSortKeyGlobal(value: String): String {
    val s = value.trim()
    val iso = Regex("^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})").find(s)
    if (iso != null) return "${iso.groupValues[1].padStart(4, '0')}-${iso.groupValues[2].padStart(2, '0')}-${iso.groupValues[3].padStart(2, '0')}"
    val dmy = Regex("^(\\d{1,2})[-/](\\d{1,2})[-/](\\d{4})").find(s)
    if (dmy != null) return "${dmy.groupValues[3]}-${dmy.groupValues[2].padStart(2, '0')}-${dmy.groupValues[1].padStart(2, '0')}"
    return s
}

private fun normalizedDateParts(value: String): Triple<Int, Int, Int>? {
    val normalized = dateSortKeyGlobal(value)
    val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(normalized) ?: return null
    return Triple(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
}

private fun detectDataPeriod(input: List<Candle>): String {
    if (input.size < 2) return "يومي"
    val dates = input.mapNotNull { candle ->
        val parts = normalizedDateParts(candle.date) ?: return@mapNotNull null
        Calendar.getInstance(Locale.US).apply {
            clear()
            set(parts.first, parts.second - 1, parts.third)
        }.timeInMillis / 86_400_000L
    }.sorted()
    if (dates.size < 2) return "يومي"
    val gaps = dates.zipWithNext { a, b -> (b - a).toInt() }.filter { it > 0 }.sorted()
    if (gaps.isEmpty()) return "يومي"
    val medianGap = gaps[gaps.size / 2]
    return when {
        medianGap >= 20 -> "شهري"
        medianGap >= 5 -> "أسبوعي"
        else -> "يومي"
    }
}

private fun aggregateCandles(input: List<Candle>, period: String): List<Candle> {
    if (period == "يومي") return input.sortedBy { dateSortKeyGlobal(it.date) }
    val groups = input.sortedBy { dateSortKeyGlobal(it.date) }.groupBy { candle ->
        val parts = normalizedDateParts(candle.date)
        if (parts == null) candle.date else {
            val (year, month, day) = parts
            if (period == "شهري") "%04d-%02d".format(Locale.US, year, month) else {
                val cal = Calendar.getInstance(Locale.US)
                cal.clear()
                cal.set(year, month - 1, day)
                "%04d-W%02d".format(Locale.US, year, cal.get(Calendar.WEEK_OF_YEAR))
            }
        }
    }
    return groups.toSortedMap().values.map { rows ->
        Candle(rows.first().date, rows.first().open, rows.maxOf { it.high },
            rows.minOf { it.low }, rows.last().close, rows.sumOf { it.volume })
    }
}

@Composable
private fun PriceTrendChart(data: List<Candle>) {
    if (data.isEmpty()) return
    val low = data.minOf { it.low }
    val high = data.maxOf { it.high }
    val range = (high - low).coerceAtLeast(0.000001)
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("الشارت بالشموع اليابانية", color = Navy, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Canvas(Modifier.fillMaxWidth().height(210.dp)) {
                val dx = size.width / data.size
                val bodyWidth = (dx * 0.62f).coerceAtLeast(2f)
                fun y(price: Double) = size.height - ((price - low) / range).toFloat() * size.height
                data.forEachIndexed { i, candle ->
                    val x = (i + 0.5f) * dx
                    val candleColor = if (candle.close >= candle.open) Green else Red
                    drawLine(candleColor, Offset(x, y(candle.high)), Offset(x, y(candle.low)), strokeWidth = 1.5f)
                    val bodyTop = minOf(y(candle.open), y(candle.close))
                    val bodyBottom = maxOf(y(candle.open), y(candle.close))
                    val bodyHeight = (bodyBottom - bodyTop).coerceAtLeast(2f)
                    drawRect(
                        candleColor,
                        topLeft = Offset(x - bodyWidth / 2f, bodyTop),
                        size = androidx.compose.ui.geometry.Size(bodyWidth, bodyHeight)
                    )
                }
            }
            Text("آخر إغلاق: " + String.format(Locale.US, "%.2f", data.last().close), color = Muted, fontSize = 10.sp)
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

private fun finalRatingColor(label: String): Color = when (label) {
    "شراء قوي" -> Color(0xFF087F5B)
    "شراء بحذر" -> Color(0xFF4F9D75)
    "بيع بحذر" -> Color(0xFFD27A48)
    "بيع قوي" -> Red
    else -> Amber
}
private fun money(v: Double) = String.format(Locale.US, "%.2f", v)
private fun one(v: Double) = String.format(Locale.US, "%.1f", v)
