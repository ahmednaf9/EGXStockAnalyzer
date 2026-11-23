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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import android.graphics.Paint as AndroidPaint
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var indexedSymbols by remember { mutableStateOf(emptyList<String>()) }
    var indexSearch by remember { mutableStateOf("") }
    var indexExpanded by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var metastockTree by remember { mutableStateOf(context.getSharedPreferences("metastock", android.content.Context.MODE_PRIVATE).getString("treeUri", null)) }
    LaunchedEffect(metastockTree) {
        val savedTree = metastockTree
        if (savedTree != null && indexedSymbols.isEmpty()) {
            scope.launch {
                try {
                    val loaded = withContext(Dispatchers.IO) {
                        MetaStockImporter.importFolder(context.contentResolver, Uri.parse(savedTree), "")
                    }
                    indexedSymbols = loaded.indexedSymbols
                    message = loaded.note
                } catch (e: Exception) {
                    message = "تعذر إعادة قراءة فهرس MetaStock المحفوظ: ${e.message ?: "خطأ"}"
                }
            }
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            try {
                context.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.getSharedPreferences("metastock", android.content.Context.MODE_PRIVATE).edit().putString("treeUri", tree.toString()).apply()
                metastockTree = tree.toString()
                indexSearch = ""
                message = "جارٍ قراءة فهرس MetaStock..."
                scope.launch {
                    try {
                        val loaded = withContext(Dispatchers.IO) {
                            MetaStockImporter.importFolder(context.contentResolver, tree, symbol)
                        }
                        indexedSymbols = loaded.indexedSymbols
                        message = loaded.note
                        if (loaded.candles.isNotEmpty()) {
                            val prepared = withContext(Dispatchers.Default) {
                                val detected = detectDataPeriod(loaded.candles)
                                val grouped = aggregateCandles(loaded.candles, detected)
                                Triple(detected, grouped, if (grouped.size >= 35) TechnicalAnalyzer.analyze(grouped) else null)
                            }
                            candles = loaded.candles
                            symbol = loaded.symbol
                            period = prepared.first
                            result = prepared.third
                            if (prepared.second.size < 35) message = "تم استيراد ${loaded.candles.size} جلسة لكن يلزم 35 جلسة على الأقل."
                        }
                    } catch (e: Exception) {
                        message = "MetaStock: ${e.message ?: "خطأ في قراءة المجلد"}"
                        result = null
                        candles = emptyList()
                    }
                }
            } catch (e: Exception) {
                message = "MetaStock: ${e.message ?: "خطأ في قراءة المجلد"}"
                result = null
                candles = emptyList()
            }
        }
    }
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
        containerColor = Color(0xFFE8FBEA),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "تحميل بيانات الأسهم",
                        color = Color(0xFF075C20),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                actions = {
                    Icon(
                        Icons.Default.ShowChart,
                        null,
                        tint = Color(0xFF00A52B),
                        modifier = Modifier.padding(end = 18.dp).size(32.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFDDF8E2))
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFF087A22), Color(0xFFB8F6BE), Color(0xFF087A22))),
                        RoundedCornerShape(28.dp)
                    )
                    .padding(14.dp)
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    BasicTextField(
                        value = symbol,
                        onValueChange = { symbol = it.uppercase(Locale.ROOT) },
                        modifier = Modifier.fillMaxWidth()
                            .border(2.dp, Color(0xFF168B39), RoundedCornerShape(28.dp))
                            .background(Color.White, RoundedCornerShape(28.dp))
                            .padding(horizontal = 14.dp, vertical = 20.dp),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color(0xFF174E9B),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center
                        ),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                if (symbol.isBlank()) {
                                    Text(
                                        "كود السهم",
                                        color = Color(0xFF174E9B),
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                                inner()
                            }
                        }
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            picker.launch(arrayOf("text/*","text/csv","application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                        },
                        modifier = Modifier.fillMaxWidth().height(66.dp),
                        shape = RoundedCornerShape(34.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00C82B),
                            contentColor = Color.White
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 7.dp)
                    ) {
                        Text("Excel/CSV تحميل ملف السهم", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Button(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(30.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF075C20), contentColor = Color.White)) { Text("تحميل مجلد الميتاستوك", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    Button(
                        onClick = {
                            val code = symbol.trim().uppercase(Locale.ROOT)
                            val treeString = metastockTree
                            if (code.isBlank()) message = "اكتب كود السهم أولاً."
                            else if (treeString == null) message = "اختر مجلد MetaStock أولاً."
                            else {
                                message = "جارٍ تحميل $code من MetaStock..."
                                result = null
                                scope.launch {
                                    try {
                                        val loaded = withContext(Dispatchers.IO) {
                                            MetaStockImporter.importFolder(context.contentResolver, Uri.parse(treeString), code)
                                        }
                                        val prep = withContext(Dispatchers.Default) {
                                            val detected = detectDataPeriod(loaded.candles)
                                            val grouped = aggregateCandles(loaded.candles, detected)
                                            Triple(detected, grouped.size, if (grouped.size >= 35) TechnicalAnalyzer.analyze(grouped) else null)
                                        }
                                        candles = loaded.candles
                                        symbol = loaded.symbol.ifBlank { code }
                                        period = prep.first
                                        result = prep.third
                                        message = if (prep.second < 35) "تم تحميل ${loaded.candles.size} جلسة؛ يلزم 35 جلسة على الأقل." else loaded.note
                                    } catch (e: Exception) {
                                        message = "تعذر تحميل $code: ${e.message ?: "خطأ"}"
                                        candles = emptyList()
                                        result = null
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Green)
                    ) { Text("تحميل السهم من ملف الميتاستوك", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            val oldTree = metastockTree
                            if (oldTree != null) {
                                try {
                                    context.contentResolver.releasePersistableUriPermission(
                                        Uri.parse(oldTree), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    )
                                } catch (_: Exception) { }
                            }
                            context.getSharedPreferences("metastock", android.content.Context.MODE_PRIVATE)
                                .edit().remove("treeUri").apply()
                            metastockTree = null
                            indexedSymbols = emptyList()
                            indexSearch = ""
                            indexExpanded = false
                            symbol = ""
                            candles = emptyList()
                            message = "تمت إعادة ضبط البرنامج وإلغاء فهرس MetaStock."
                            result = null
                            period = "يومي"
                        },
                        modifier = Modifier.size(60.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE50916),
                            contentColor = Color.White
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Sync, null, modifier = Modifier.size(18.dp))
                            Text("RESET", fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                        }
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
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(13.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("قائمة أسهم ميتاستوك", color = Navy, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(if (indexedSymbols.isEmpty()) "حمّل مجلد الميتاستوك من الزر أعلاه أولاً." else "${indexedSymbols.size} رمزًا متاحًا للاختيار والتحميل", color = Muted, fontSize = 12.sp)
                        }
                        Button(
                            onClick = { indexExpanded = !indexExpanded },
                            modifier = Modifier.height(42.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF16A34A),
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp)
                        ) {
                            Text(
                                if (indexExpanded) "إغلاق القائمة" else "فتح القائمة",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                    if (indexExpanded && indexedSymbols.isNotEmpty()) {
                        BasicTextField(value = indexSearch, onValueChange = { indexSearch = it.uppercase(Locale.ROOT) },
                            modifier = Modifier.fillMaxWidth().background(Page, RoundedCornerShape(10.dp)).padding(12.dp), singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = Navy, fontSize = 14.sp),
                            decorationBox = { inner -> Box { if (indexSearch.isBlank()) Text("ابحث بكود السهم أو اسم الشركة", color = Muted); inner() } })
                        Spacer(Modifier.height(8.dp))
                        val stockNames = mapOf(
                            "COMI" to "البنك التجاري الدولي",
                            "EFIH" to "إي فاينانس للاستثمارات المالية والرقمية",
                            "TMGH" to "مجموعة طلعت مصطفى",
                            "FWRY" to "فوري لتكنولوجيا البنوك والمدفوعات",
                            "ETEL" to "المصرية للاتصالات",
                            "EAST" to "الشرقية للدخان",
                            "SWDY" to "السويدي إليكتريك",
                            "HRHO" to "المجموعة المالية هيرميس",
                            "ABUK" to "أبو قير للأسمدة",
                            "AMOC" to "الإسكندرية للزيوت المعدنية",
                            "ORAS" to "أوراسكوم للإنشاءات",
                            "ORHD" to "أوراسكوم للتنمية مصر",
                            "PHAR" to "إيبيكو",
                            "JUFO" to "جهينة للصناعات الغذائية",
                            "CLHO" to "كليوباترا للمستشفيات",
                            "ESRS" to "حديد عز",
                            "EMFD" to "إعمار مصر للتنمية",
                            "MNHD" to "مدينة مصر للإسكان والتعمير",
                            "ADIB" to "مصرف أبو ظبي الإسلامي مصر",
                            "ADCI" to "العربية للأدوية",
                            "ALEX" to "الإسكندرية لتداول الحاويات",
                            "ALCN" to "الإسكندرية للزيوت والصابون",
                            "ALUM" to "مصر للألومنيوم",
                            "APSW" to "الأهرام للطباعة والتغليف",
                            "ARCC" to "العربية للأسمنت",
                            "ASCM" to "أسيك للتعدين",
                            "AUTO" to "جي بي كورب",
                            "BTFH" to "بلتون القابضة",
                            "CCAP" to "القابضة للاستثمارات المالية",
                            "CIEB" to "البنك التجاري الدولي للاستثمار",
                            "COSG" to "كوكاكولا مصر",
                            "DCRC" to "الدلتا للسكر",
                            "DSCW" to "الدلتا للصلب",
                            "EGAL" to "مصر للألومنيوم",
                            "EGCH" to "المصرية للكيماويات",
                            "EGTS" to "المصرية للسياحة",
                            "EXPA" to "البنك المصري لتنمية الصادرات",
                            "FAIT" to "بنك فيصل الإسلامي المصري",
                            "GBCO" to "جي بي كورب",
                            "GDR" to "العربية للصناعات الهندسية",
                            "GOLD" to "جولد للاستثمار",
                            "GTWL" to "جيت واي للاستثمار",
                            "ICFC" to "العربية لحليج الأقطان",
                            "IDHC" to "ابن سينا فارما",
                            "ISPH" to "ابن سينا فارما",
                            "KABO" to "النصر للملابس والمنسوجات",
                            "KIMA" to "كيما",
                            "KRDI" to "القاهرة للاستثمار والتنمية العقارية",
                            "LCSW" to "ليسكو",
                            "MICH" to "مصر لصناعة الكيماويات",
                            "MFPC" to "موبكو",
                            "MTIE" to "ام ام جروب",
                            "NCCW" to "الأسمنت الوطنية",
                            "NEDA" to "نهر الخير",
                            "NINV" to "نايل للاستثمار",
                            "OLFI" to "عبور لاند",
                            "OIH" to "أوراسكوم للاستثمار القابضة",
                            "OPTI" to "المصرية للأعمال الهندسية",
                            "PHDC" to "بالم هيلز",
                            "POUL" to "القاهرة للدواجن",
                            "PRCL" to "القاهرة للأدوية",
                            "RAYA" to "راية القابضة",
                            "RMDA" to "راميدا",
                            "SAUD" to "المصرية السعودية للاستثمار",
                            "SKPC" to "سيدي كرير للبتروكيماويات",
                            "SPIN" to "سبينالكس",
                            "SUGR" to "الدلتا للسكر",
                            "TAQA" to "طاقة عربية",
                            "TALM" to "تعليم لخدمات الإدارة",
                            "UEGC" to "المتحدة للإسكان",
                            "UNIT" to "المتحدة للإسكان",
                            "VACS" to "فالي للاستثمار",
                            "ZEOT" to "الزيوت المستخلصة"
                        )
                        val filteredSymbols = indexedSymbols.filter { ticker ->
                            ticker.contains(indexSearch.trim(), ignoreCase = true) ||
                                stockNames[ticker]?.contains(indexSearch.trim(), ignoreCase = true) == true
                        }
                        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            filteredSymbols.forEach { ticker ->
                                TextButton(onClick = {
                                    val uriString = metastockTree
                                    if (uriString != null) {
                                        indexSearch = ticker
                                        message = "جارٍ تحميل بيانات $ticker..."
                                        result = null
                                        scope.launch {
                                            try {
                                                val treeUri = Uri.parse(uriString)
                                                val loaded = withContext(Dispatchers.IO) {
                                                    MetaStockImporter.importFolder(context.contentResolver, treeUri, ticker)
                                                }
                                                val analysisData = withContext(Dispatchers.Default) {
                                                    val detected = detectDataPeriod(loaded.candles)
                                                    val grouped = aggregateCandles(loaded.candles, detected)
                                                    val analysis = if (grouped.size >= 35) TechnicalAnalyzer.analyze(grouped) else null
                                                    Triple(detected, grouped.size, analysis)
                                                }
                                                candles = loaded.candles
                                                symbol = loaded.symbol
                                                period = analysisData.first
                                                result = analysisData.third
                                                message = if (analysisData.second < 35) "تم تحميل ${loaded.candles.size} جلسة؛ يلزم 35 جلسة على الأقل." else loaded.note
                                            } catch (e: Exception) {
                                                message = "تعذر تحميل $ticker: ${e.message ?: "خطأ"}"
                                            }
                                        }
                                    } else message = "اختر مجلد MetaStock أولاً."
                                }, modifier = Modifier.fillMaxWidth()) {
                        Column(horizontalAlignment = Alignment.Start) {
                                        Text(ticker, color = Green, fontWeight = FontWeight.Bold)
                                        stockNames[ticker]?.let { Text(it, color = Muted, fontSize = 12.sp) }
                                    }
                                }
                                Divider(color = Page)
                            }
                            if (filteredSymbols.isEmpty()) Text("لا توجد رموز مطابقة.", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            val a = result
            if (a == null) {
                Text("ماذا يقدم لك المحلل الفني؟", color = Navy, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(
                    "أدوات تساعدك على فهم حركة السهم من خلال تحليل بيانات الأسعار التاريخية ومجموعة من المؤشرات الفنية.",
                    color = Muted, fontSize = 12.sp, lineHeight = 18.sp
                )
                Feature("ملخص وتحليل السهم", "ملخص لأهم الإشارات الفنية ونقاط القوة والضعف وتقييم عام لحالة السهم.")
                Feature("الاتجاه والزخم", "يوضح اتجاه السعر صعودًا أو هبوطًا أو عرضيًا، ويقيس قوة الحركة باستخدام RSI وMACD والمتوسطات.")
                Feature("الدعم والمقاومة", "يعرض مستويات سعرية قد يرتد منها السعر أو يواجه عندها ضغطًا، اعتمادًا على القمم والقيعان السابقة.")
                Feature("النماذج الفنية", "يرصد نماذج شموع أساسية قد تشير إلى استمرار الاتجاه أو احتمال انعكاسه.")
                Feature("إدارة المخاطر", "يعرض سيناريوهات سعرية ومستوى إبطال ووقفًا افتراضيًا ونسبة عائد إلى مخاطرة.")
                Feature("السيولة", "يقارن حجم تداول آخر جلسة بمتوسط أحجام الجلسات السابقة لتقدير نشاط التداول.")
                Feature("الرسوم البيانية", "رسوم توضح حركة السعر والشموع اليابانية والمؤشرات لتسهيل متابعة الاتجاه.")
                Feature("التقييم الفني المتعدد", "يجمع نتائج المؤشرات المختلفة في تقييم فني متكامل بدلًا من الاعتماد على مؤشر واحد.")
                Spacer(Modifier.height(8.dp))
                Text("الأعمدة المطلوبة: Date, Open, High, Low, Close, Volume", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Text("التحليل مبني على البيانات التاريخية المستوردة، وهو أداة مساعدة وليس توصية شراء أو بيع.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
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
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("قراءة التجميع والتصريف", color = Muted, fontSize = 11.sp)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                a.marketState,
                                Modifier.weight(1f),
                                color = marketStateColor(a.marketState),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                if (a.marketState == "غير محسوم") "—" else
                                    "${if (a.marketStateScore > 0) "+" else ""}${a.marketStateScore}/100",
                                color = marketStateColor(a.marketState),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        Text("قوة الإشارة: ${a.marketStateStrength}", color = Navy, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        if (a.marketState != "غير محسوم") {
                            LinearProgressIndicator(
                                progress = { (kotlin.math.abs(a.marketStateScore) / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = marketStateColor(a.marketState),
                                trackColor = Page
                            )
                            Spacer(Modifier.height(7.dp))
                        }
                        Text("المعنى ببساطة", color = Navy, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(a.marketStateInterpretation, color = marketStateColor(a.marketState), fontSize = 12.sp, lineHeight = 18.sp)
                        Spacer(Modifier.height(5.dp))
                        Text(a.marketStateReason, color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "هذا تقدير فني من بيانات السعر والحجم، وليس إثباتًا لتحركات المحافظ أو المستثمرين الكبار.",
                            color = Muted, fontSize = 10.sp, lineHeight = 15.sp
                        )
                    }
                }
                Spacer(Modifier.height(9.dp))
                ResultCard(
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
                        Spacer(Modifier.height(6.dp))
                        Text(a.scoreInterpretation, color = scoreColor(a.scoreLabel), fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
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
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.fillMaxWidth().padding(15.dp)) {
                        Text("التصنيف الفني للسهم", color = Muted, fontSize = 12.sp)
                        val classificationColor = when (a.technicalClassification) {
                            "قوي جداً", "قوي", "إيجابي" -> Green
                            "ضعيف", "ضعيف جداً" -> Red
                            else -> Amber
                        }
                        Text(a.technicalClassification, color = classificationColor, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(4.dp))
                        Text(a.technicalClassificationReason, color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
                        Spacer(Modifier.height(9.dp))
                        Text("التوصية التنفيذية", color = Muted, fontSize = 12.sp)
                        val recommendationColor = when (a.executionRecommendation) {
                            "شراء", "شراء تدريجي" -> Green
                            "تجنب" -> Red
                            else -> Amber
                        }
                        Text(a.executionRecommendation, color = recommendationColor, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(a.executionRecommendationReason, color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "العائد إلى المخاطرة: ${a.riskReward?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "غير متاح"}",
                            color = Muted, fontSize = 11.sp
                        )
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
                    Metric("RSI 14", one(a.rsi), Modifier.weight(1f),
                        if (a.rsi < 45.0) Red else if (a.rsi >= 55.0) Green else Amber)
                    Metric("SMA 20", a.sma20?.let(::money) ?: "—", Modifier.weight(1f),
                        a.sma20?.let { if (a.last.close >= it) Green else Red } ?: Muted)
                    Metric("SMA 50", a.sma50?.let(::money) ?: "—", Modifier.weight(1f),
                        a.sma50?.let { if (a.last.close >= it) Green else Red } ?: Muted)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Metric("MACD", a.macd?.let(::money) ?: "—", Modifier.weight(1f),
                        a.macd?.let { if (it >= 0.0) Green else Red } ?: Muted)
                    Metric("إشارة MACD", a.macdSignal?.let(::money) ?: "—", Modifier.weight(1f),
                        a.macdSignal?.let { if (it >= 0.0) Green else Red } ?: Muted)
                    Metric("ATR 14", money(a.atr), Modifier.weight(1f), Muted)
                }
                Spacer(Modifier.height(12.dp))
                SectionTitle("السيولة وحركة السعر")
                val liquidityText = when {
                    !a.volumeAvailable -> "بيانات التداول غير كافية"
                    a.volumeRatio >= 1.3 -> "التداول مرتفع"
                    a.volumeRatio <= 0.7 -> "التداول منخفض"
                    else -> "التداول متوسط"
                }
                val liquidityColor = when {
                    !a.volumeAvailable -> Amber
                    a.volumeRatio >= 1.3 -> Green
                    a.volumeRatio <= 0.7 -> Red
                    else -> Amber
                }
                val priceLocation = when {
                    nearestSupport != null && (a.last.close - nearestSupport) / a.last.close <= 0.03 -> "السعر قريب من منطقة دعم"
                    nearestResistance != null && (nearestResistance - a.last.close) / a.last.close <= 0.025 -> "السعر قريب من منطقة مقاومة"
                    else -> "السعر بين مناطق الدعم والمقاومة"
                }
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("حركة التداول", Modifier.weight(1f), color = Navy, fontSize = 13.sp)
                            Text(liquidityText, color = liquidityColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(
                            if (a.volumeAvailable) "حجم التداول مقارنة بالمعتاد: ${when {
                                a.volumeRatio >= 1.3 -> "أعلى من المعتاد"
                                a.volumeRatio <= 0.7 -> "أقل من المعتاد"
                                else -> "قريب من المعتاد"
                            }}" else "لا تتوفر بيانات كافية للحكم على حجم التداول.",
                            color = Muted, fontSize = 11.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        Divider(color = Page)
                        Spacer(Modifier.height(9.dp))
                        Text("مكان السعر: $priceLocation", color = Navy, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("اتجاه السهم: ${a.trend}", color = when {
                            a.trend == "صاعد" || a.trend == "تحسن صاعد" -> Green
                            a.trend == "هابط" || a.trend == "ضغط هابط" -> Red
                            else -> Amber
                        }, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                SectionTitle("النماذج الكلاسيكية")
                val classicPatterns = ClassicPatternScanner.scan(aggregateCandles(candles, period))
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("فحص آلي مرشح للنماذج الكلاسيكية، مع مستهدف الحركة في حال تأكيد الاختراق أو فشل السيناريو.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
                        Spacer(Modifier.height(8.dp))
                        if (classicPatterns.isEmpty()) {
                            Text("لا توجد نماذج كلاسيكية واضحة وفق شروط الفحص الحالي أو البيانات غير كافية.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                        } else {
                            classicPatterns.forEachIndexed { i, p ->
                                if (i > 0) { Spacer(Modifier.height(8.dp)); Divider(color = Page); Spacer(Modifier.height(8.dp)) }
                                val pc = when(p.bias) { "صاعد" -> Green; "هابط" -> Red; else -> Amber }
                                Text(p.name, color = Navy, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text("الاتجاه: ${p.bias}  •  الحالة: ${p.status}", color = pc, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(3.dp))
                                Text(p.explanation, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                                Spacer(Modifier.height(4.dp))
                                Text("مستوى التأكيد/التفعيل: ${money(p.trigger)}", color = Navy, fontSize = 11.sp)
                                Text("المستهدف عند نجاح النموذج: ${money(p.targetSuccess)}", color = Green, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text("المستهدف الافتراضي عند فشل/انعكاس السيناريو: ${money(p.targetFailure)}", color = Red, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text("مستهدف الاتجاه المقابل/منطقة إبطال مرجعية: ${money(p.invalidation)}", color = Muted, fontSize = 10.sp)
                                ClassicPatternChart(aggregateCandles(candles, period).takeLast(55), p)
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                        Text("تنبيه: هذه نتائج كشف تقريبي تعتمد على قمم وقيعان السعر؛ النموذج لا يتأكد إلا بالإغلاق خارج مستوى التفعيل. المستهدفات قياسات نظرية وليست توقعات مضمونة.", color = Amber, fontSize = 10.sp, lineHeight = 15.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                SectionTitle("إشارات الشموع — آخر 10 جلسات")
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(
                            "النماذج المرصودة خلال آخر 10 جلسات، مع فحص تأكيد كل نموذج من إغلاق الجلسة التالية.",
                            color = Muted, fontSize = 11.sp, lineHeight = 17.sp
                        )
                        Spacer(Modifier.height(9.dp))
                        if (a.recentPatterns.isEmpty()) {
                            Text(
                                "لم يتم رصد نماذج شموع أساسية واضحة خلال آخر 10 جلسات.",
                                color = Muted, fontSize = 13.sp, lineHeight = 19.sp
                            )
                        } else {
                            a.recentPatterns.forEachIndexed { index, pattern ->
                                if (index > 0) {
                                    Spacer(Modifier.height(8.dp))
                                    Divider(color = Page)
                                    Spacer(Modifier.height(8.dp))
                                }
                                val patternColor = when (pattern.direction) {
                                    "إيجابي" -> Green
                                    "سلبي" -> Red
                                    else -> Amber
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        pattern.name,
                                        Modifier.weight(1f),
                                        color = patternColor,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(pattern.direction, color = patternColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(3.dp))
                                Text("التاريخ: ${pattern.date}", color = Muted, fontSize = 11.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(pattern.explanation, color = Navy, fontSize = 11.sp, lineHeight = 17.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    pattern.confirmation,
                                    color = if (pattern.confirmation.startsWith("تأكدت") || pattern.confirmation.startsWith("تحول إلى")) Green
                                    else if (pattern.confirmation.startsWith("أُبطلت")) Red else Amber,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    lineHeight = 17.sp
                                )
                                Spacer(Modifier.height(8.dp))
                                PatternPriceChart(candles.takeLast(16), pattern)
                                Spacer(Modifier.height(7.dp))
                                Text(
                                    "قمة النموذج: ${money(pattern.patternHigh)}   •   قاع النموذج: ${money(pattern.patternLow)}",
                                    color = Muted, fontSize = 10.sp
                                )
                                if (pattern.confirmationDate != null && pattern.confirmationPrice != null) {
                                    Text(
                                        "إغلاق التأكيد (${pattern.confirmationDate}): ${money(pattern.confirmationPrice)}",
                                        color = Green, fontSize = 10.sp, fontWeight = FontWeight.SemiBold
                                    )
                                }
                                if (pattern.entryPrice != null && pattern.stopPrice != null) {
                                    Text(
                                        "مستوى التفعيل: ${money(pattern.entryPrice)}   •   وقف/إبطال: ${money(pattern.stopPrice)}",
                                        color = Navy, fontSize = 10.sp
                                    )
                                }
                                if (pattern.targets.isNotEmpty()) {
                                    Text(
                                        "المستهدفات النظرية: م1 ${money(pattern.targets[0])}   •   م2 ${money(pattern.targets[1])}   •   م3 ${money(pattern.targets[2])}",
                                        color = if (pattern.direction == "إيجابي") Green else Red,
                                        fontSize = 10.sp, lineHeight = 16.sp
                                    )
                                } else {
                                    Text("النموذج المحايد لا يملك مستهدفًا اتجاهيًا قبل تأكيد الاختراق.", color = Amber, fontSize = 10.sp)
                                }
                                Spacer(Modifier.height(9.dp))
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F8F6)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                        Text("ماذا يعني هذا النموذج؟ ببساطة", color = Navy, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(6.dp))
                                        Text("1. ماذا حدث؟  ${pattern.explanation}", color = Navy, fontSize = 11.sp, lineHeight = 17.sp)
                                        Spacer(Modifier.height(4.dp))
                                        val meaning = when (pattern.direction) {
                                            "إيجابي" -> "قد يشير إلى تحسن قوة المشترين ومحاولة السعر الارتداد لأعلى."
                                            "سلبي" -> "قد يشير إلى زيادة ضغط البائعين واحتمال تعرض السعر لهبوط."
                                            else -> "يعكس ترددًا بين المشترين والبائعين؛ والاتجاه لم يُحسم بعد."
                                        }
                                        Text("2. ماذا يعني؟  $meaning", color = Navy, fontSize = 11.sp, lineHeight = 17.sp)
                                        Spacer(Modifier.height(4.dp))
                                        val expectation = when {
                                            pattern.confirmation.startsWith("تأكدت") -> "الإشارة اجتازت شرط التأكيد المبدئي؛ راقب استمرار الحركة نحو المستهدفات."
                                            pattern.confirmation.startsWith("أُبطلت") -> "السيناريو الأصلي فقد صلاحيته؛ لا تعتمد على مستهدفاته."
                                            pattern.confirmation.startsWith("تحول إلى") -> "خرج السعر من نطاق التردد؛ تابع اتجاه الاختراق بحذر."
                                            pattern.direction == "إيجابي" -> "ننتظر إغلاقًا أعلى قمة النموذج قبل اعتبار الصعود مؤكدًا."
                                            pattern.direction == "سلبي" -> "ننتظر إغلاقًا أسفل قاع النموذج قبل اعتبار الهبوط مؤكدًا."
                                            else -> "ننتظر إغلاقًا خارج قمة أو قاع النموذج لتحديد الاتجاه."
                                        }
                                        Text("3. ماذا نتوقع؟  $expectation", color = Navy, fontSize = 11.sp, lineHeight = 17.sp)
                                        Spacer(Modifier.height(4.dp))
                                        val action = when {
                                            pattern.confirmation.startsWith("أُبطلت") -> "القرار: تجنب تنفيذ هذا السيناريو، وأعد تقييم السهم."
                                            pattern.confirmation.startsWith("تأكدت") -> "القرار: متابعة السعر وإدارة المخاطرة؛ التأكيد لا يضمن الوصول للأهداف."
                                            pattern.confirmation.startsWith("تحول إلى") -> "القرار: انتظر ثبات الاختراق ولا تتسرع قبل وضوح الحركة."
                                            else -> "القرار: انتظار التأكيد، وعدم الدخول اعتمادًا على شكل الشمعة وحده."
                                        }
                                        Text("4. ماذا أفعل؟  $action", color = patternColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
                                    }
                                }
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    "تنبيه: المستهدفات تقديرية مبنية على مدى شمعة النموذج، وليست توقعات مضمونة أو توصية مباشرة.",
                                    color = Muted, fontSize = 9.sp, lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
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
private fun ClassicPatternChart(data: List<Candle>, pattern: ClassicPatternResult) {
    if (data.size < 3) return
    val levels = listOf("تفعيل" to pattern.trigger, "هدف" to pattern.targetSuccess, "مقابل" to pattern.targetFailure)
    val prices = data.flatMap { listOf(it.high, it.low) } + levels.map { it.second }
    val minPrice = prices.minOrNull() ?: return
    val maxPrice = prices.maxOrNull() ?: return
    val pad = ((maxPrice-minPrice)*.08).coerceAtLeast(maxPrice*.002)
    val low=(minPrice-pad).coerceAtLeast(0.000001)
    val high=maxPrice+pad
    val range=(high-low).coerceAtLeast(0.000001)

    // Shape anchors are selected from the actual displayed candles, by alternating
    // segment extrema, to visualize the detected pattern's approximate structure.
    val anchors: List<Pair<Int, Double>> = run {
        val name=pattern.name
        fun extrema(parts:Int, highFirst:Boolean): List<Pair<Int,Double>> {
            val result=mutableListOf<Pair<Int,Double>>()
            for (s in 0 until parts) {
                val from=s*data.size/parts
                val to=((s+1)*data.size/parts).coerceAtLeast(from+1).coerceAtMost(data.size)
                if (from>=to) continue
                val slice=data.subList(from,to)
                val hi=highFirst || s%2==1
                val local=if(hi) slice.indices.maxByOrNull{slice[it].high} else slice.indices.minByOrNull{slice[it].low}
                if(local!=null) result += (from+local) to (if(hi) slice[local].high else slice[local].low)
            }
            return result
        }
        when {
            name.contains("الرأس والكتفين المقلوب") -> extrema(5,false)
            name.contains("الرأس والكتفين") -> extrema(5,true)
            name.contains("القمة المزدوجة") -> extrema(3,true)
            name.contains("القاع المزدوج") -> extrema(3,false)
            name.contains("العلم") -> {
                val poleEnd=(data.size*0.72).toInt().coerceAtLeast(2)
                val pole=data.take(poleEnd)
                val pause=data.drop(poleEnd.coerceAtMost(data.size-1))
                if(pause.size>=2) listOf(
                    pole.indices.minByOrNull{pole[it].low}?.let{it to pole[it].low},
                    pole.indices.maxByOrNull{pole[it].high}?.let{it to pole[it].high},
                    (poleEnd+pause.indices.minByOrNull{pause[it].low}!! to pause.minOf{it.low}),
                    (poleEnd+pause.indices.maxByOrNull{pause[it].high}!! to pause.maxOf{it.high})
                ).filterNotNull() else extrema(4,true)
            }
            name.contains("المثلث") || name.contains("المستطيل") -> extrema(4,true)
            else -> extrema(4,true)
        }
    }

    Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFFBFDFC)), shape=RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Text("رسم النموذج على حركة السهم (آخر ${data.size} شمعة)", color=Navy, fontSize=11.sp, fontWeight=FontWeight.Bold)
            Canvas(Modifier.fillMaxWidth().height(205.dp)) {
                val left=8f; val right=size.width-8f; val top=10f; val bottom=size.height-8f
                val plotH=bottom-top; val dx=(right-left)/data.size; val bw=(dx*.55f).coerceAtLeast(2f)
                fun y(v:Double)=bottom-((v-low)/range).toFloat()*plotH
                fun x(i:Int)=left+(i+.5f)*dx
                data.forEachIndexed { i, candle ->
                    val xx=x(i); val color=if(candle.close>=candle.open) Green else Red
                    drawLine(color,Offset(xx,y(candle.high)),Offset(xx,y(candle.low)),strokeWidth=1.4f)
                    drawRect(color,Offset(xx-bw/2,minOf(y(candle.open),y(candle.close))),androidx.compose.ui.geometry.Size(bw,(abs(y(candle.open)-y(candle.close))).coerceAtLeast(2f))
                    )
                }
                // Pattern geometry over the real candle data.
                anchors.zipWithNext().forEach { (a,b) ->
                    drawLine(Color(0xFFB7791F),Offset(x(a.first),y(a.second)),Offset(x(b.first),y(b.second)),strokeWidth=3f)
                }
                anchors.forEach { (i,p) -> drawCircle(Color(0xFFB7791F),radius=4f,center=Offset(x(i),y(p))) }
                levels.forEach { (label,price) ->
                    val yy=y(price); val color=when(label){"تفعيل"->Color(0xFF315E9B);"هدف"->Green;else->Red}
                    var xx=left; while(xx<right){drawLine(color,Offset(xx,yy),Offset((xx+6f).coerceAtMost(right),yy),strokeWidth=1.3f);xx+=10f}
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("تفعيل ${money(pattern.trigger)}",color=Color(0xFF315E9B),fontSize=9.sp)
                Text("هدف ${money(pattern.targetSuccess)}",color=Green,fontSize=9.sp)
                Text("سيناريو مقابل ${money(pattern.targetFailure)}",color=Red,fontSize=9.sp)
            }
            Text("الخط الذهبي يوضح الشكل التقريبي للنموذج على الشموع المعروضة.",color=Muted,fontSize=9.sp)
        }
    }
}

@Composable
private fun PatternPriceChart(data: List<Candle>, pattern: CandlestickPattern) {
    if (data.isEmpty()) return
    val levels = buildList {
        pattern.entryPrice?.let { add("تفعيل" to it) }
        pattern.stopPrice?.let { add("وقف" to it) }
        pattern.targets.forEachIndexed { index, value -> add("م${index + 1}" to value) }
    }
    val allPrices = data.flatMap { listOf(it.high, it.low) } + levels.map { it.second }
    val minPrice = allPrices.minOrNull() ?: return
    val maxPrice = allPrices.maxOrNull() ?: return
    val padding = ((maxPrice - minPrice) * 0.10).coerceAtLeast(maxPrice * 0.002)
    val low = (minPrice - padding).coerceAtLeast(0.000001)
    val high = maxPrice + padding
    val range = (high - low).coerceAtLeast(0.000001)
    val patternIndex = data.indexOfLast { it.date == pattern.date }
    val confirmationIndex = pattern.confirmationDate?.let { date -> data.indexOfLast { it.date == date } } ?: -1
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFBFDFC)), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Text("خريطة النموذج والمستهدفات السعرية", color = Navy, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Canvas(Modifier.fillMaxWidth().height(220.dp)) {
                val left = 8f
                val right = size.width - 66f
                val top = 12f
                val bottom = size.height - 20f
                val plotWidth = (right - left).coerceAtLeast(1f)
                val plotHeight = (bottom - top).coerceAtLeast(1f)
                val dx = plotWidth / data.size
                val bodyWidth = (dx * 0.58f).coerceAtLeast(2f)
                fun y(price: Double) = bottom - ((price - low) / range).toFloat() * plotHeight

                val native = drawContext.canvas.nativeCanvas
                val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.rgb(113, 128, 150)
                    textSize = 23f
                    textAlign = AndroidPaint.Align.LEFT
                }
                for (grid in 0..4) {
                    val gy = top + plotHeight * grid / 4f
                    drawLine(Color(0xFFE4EBE7), Offset(left, gy), Offset(right, gy), strokeWidth = 1f)
                    val price = high - range * grid / 4.0
                    native.drawText(String.format(Locale.US, "%.2f", price), right + 5f, gy + 7f, paint)
                }
                data.forEachIndexed { i, candle ->
                    val x = left + (i + 0.5f) * dx
                    val isPattern = i == patternIndex
                    val color = when {
                        isPattern -> Color(0xFFB7791F)
                        candle.close >= candle.open -> Green
                        else -> Red
                    }
                    drawLine(color, Offset(x, y(candle.high)), Offset(x, y(candle.low)), strokeWidth = if (isPattern) 3f else 1.5f)
                    val bodyTop = minOf(y(candle.open), y(candle.close))
                    val bodyBottom = maxOf(y(candle.open), y(candle.close))
                    drawRect(
                        color,
                        topLeft = Offset(x - bodyWidth / 2f, bodyTop),
                        size = androidx.compose.ui.geometry.Size(bodyWidth, (bodyBottom - bodyTop).coerceAtLeast(2f))
                    )
                    if (isPattern) {
                        drawLine(Color(0xFFB7791F), Offset(x, top), Offset(x, bottom), strokeWidth = 1.5f)
                    }
                    if (i == confirmationIndex) {
                        drawCircle(Color(0xFF168A62), radius = 5f, center = Offset(x, y(candle.close)))
                    }
                }
                levels.forEach { (label, price) ->
                    val yy = y(price)
                    val color = when {
                        label == "وقف" -> Red
                        label == "تفعيل" -> Color(0xFF315E9B)
                        else -> Green
                    }
                    var x = left
                    while (x < right) {
                        drawLine(color.copy(alpha = 0.75f), Offset(x, yy), Offset((x + 7f).coerceAtMost(right), yy), strokeWidth = 1.4f)
                        x += 12f
                    }
                    paint.color = color.toArgb()
                    paint.textSize = 22f
                    native.drawText("$label ${String.format(Locale.US, "%.2f", price)}", left + 3f, (yy - 4f).coerceAtLeast(top + 13f), paint)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(data.first().date, color = Muted, fontSize = 9.sp)
                Text("النموذج: ${pattern.date}", color = Color(0xFFB7791F), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text(data.last().date, color = Muted, fontSize = 9.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("● النموذج", color = Color(0xFFB7791F), fontSize = 9.sp)
                Text("● التأكيد", color = Green, fontSize = 9.sp)
                Text("— المستهدفات", color = Green, fontSize = 9.sp)
                Text("— الوقف", color = Red, fontSize = 9.sp)
            }
        }
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
fun Metric(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = semanticColor(value)) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(11.dp)) {
            Text(label, color = Muted, fontSize = 10.sp)
            Spacer(Modifier.height(5.dp))
            Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 17.sp)
        }
    }
}

@Composable
fun ResultCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            text.split("\\n").forEach { line ->
                Text(line, color = semanticColor(line), fontSize = 13.sp, lineHeight = 22.sp)
            }
        }
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
    "إيجابي فنيًا", "يميل للإيجابية", "قوي جداً", "قوي", "إيجابي" -> Green
    "ضعيف فنيًا", "ضعيف", "ضعيف جداً" -> Red
    else -> Amber
}

private fun classificationColor(label: String): Color = when (label) {
    "قوي جداً", "قوي", "إيجابي" -> Green
    "ضعيف", "ضعيف جداً" -> Red
    else -> Amber
}

private fun recommendationColor(label: String): Color = when (label) {
    "شراء", "شراء تدريجي" -> Green
    "تجنب" -> Red
    else -> Amber
}

// Apply the same positive/negative palette to all result text and metric values.
private fun semanticColor(text: String): Color {
    val s = text.trim()
    return when {
        listOf("ضعيف جداً", "ضعيف فنيًا", "ضعيف", "سلبي", "هابط", "ضغط هابط",
            "تصريف", "بيع قوي", "بيع بحذر", "تجنب", "كسر الدعم", "وقف الخسارة")
            .any { s.contains(it) } -> Red
        listOf("قوي جداً", "قوي", "إيجابي", "صاعد", "تحسن صاعد", "تجميع",
            "شراء قوي", "شراء بحذر", "شراء تدريجي", "شراء اختراق", "ارتداد")
            .any { s.contains(it) } -> Green
        else -> Navy
    }
}

private fun marketStateColor(label: String): Color = when (label) {
    "تجميع محتمل" -> Color(0xFF087F5B)
    "تصريف محتمل" -> Red
    "غير محسوم" -> Muted
    else -> Amber
}

private fun finalRatingColor(label: String): Color = when (label) {
    "شراء قوي" -> Green
    "شراء بحذر" -> Green
    "بيع بحذر" -> Red
    "بيع قوي" -> Red
    else -> Amber
}
private fun money(v: Double) = String.format(Locale.US, "%.2f", v)
private fun one(v: Double) = String.format(Locale.US, "%.1f", v)