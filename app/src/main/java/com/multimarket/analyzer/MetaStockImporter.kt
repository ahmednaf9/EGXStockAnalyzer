package com.multimarket.analyzer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.util.Locale

/** Reads stock symbols and company names from MetaStock MASTER, EMASTER and XMASTER indexes. */
object MetaStockImporter {
    data class StockEntry(val symbol: String, val name: String, val fileNum: Int, val fields: Int, val dataExtension: String)
    data class Result(val symbol: String, val name: String, val candles: List<Candle>, val note: String, val indexedStocks: List<StockEntry>)

    fun importFolder(resolver: ContentResolver, tree: Uri, requestedSymbol: String = ""): Result {
        val children = mutableMapOf<String, Uri>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val cursor = resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        ) ?: error("تعذرت قراءة محتويات المجلد. تأكد من منح التطبيق صلاحية الوصول إلى مجلد MetaStock.")
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val displayName = it.getString(1)
                children[displayName.uppercase(Locale.ROOT)] = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            }
        }

        // XMASTER is the extended index and must take precedence when present.
        val indexName = when {
            children.containsKey("XMASTER") -> "XMASTER"
            children.containsKey("EMASTER") -> "EMASTER"
            children.containsKey("MASTER") -> "MASTER"
            else -> error("لم يتم العثور على ملف فهرس الأسهم داخل المجلد. اختر المجلد الرئيسي الذي يحتوي على أحد الملفات: XMASTER أو EMASTER أو MASTER.")
        }
        val bytes = resolver.openInputStream(children[indexName]!!)?.use { it.readBytes() }
            ?: error("تعذرت قراءة ملف قائمة الأسهم")
        val recordSize = when (indexName) { "EMASTER" -> 192; "XMASTER" -> 150; else -> 53 }
        if (bytes.size < recordSize || bytes.size % recordSize != 0) {
            error("ملف فهرس الأسهم موجود، لكن حجمه لا يتوافق مع صيغة $indexName المتوقعة. تحقق من اكتمال نسخ ملفات المجلد.")
        }

        fun ascii(record: ByteArray, offset: Int, length: Int): String {
            val raw = record.copyOfRange(offset, offset + length).takeWhile { it.toInt() != 0 }.toByteArray()
            if (raw.isEmpty()) return ""
            // MetaStock indexes are usually ASCII; some regional datasets store display names
            // using the Windows Arabic code page. Preserve normal ASCII names unchanged.
            val charset = if (raw.any { (it.toInt() and 0xFF) >= 0x80 })
                runCatching { java.nio.charset.Charset.forName("windows-1256") }.getOrDefault(Charsets.ISO_8859_1)
            else Charsets.US_ASCII
            return raw.toString(charset).trim()
        }

        val entries = mutableListOf<StockEntry>()
        for (off in recordSize until bytes.size step recordSize) {
            val rec = bytes.copyOfRange(off, off + recordSize)
            val entry = when (indexName) {
                "EMASTER" -> StockEntry(ascii(rec, 11, 14), ascii(rec, 32, 16),
                    rec[2].toInt() and 0xFF, rec[6].toInt() and 0xFF, "DAT")
                "MASTER" -> StockEntry(ascii(rec, 36, 14), ascii(rec, 7, 16),
                    rec[0].toInt() and 0xFF, rec[5].toInt() and 0xFF, "DAT")
                else -> {
                    val fileNum = ((rec[65].toInt() and 0xFF) shl 8) or (rec[66].toInt() and 0xFF)
                    StockEntry(ascii(rec, 1, 15), ascii(rec, 16, 46), fileNum, 7, "MWD")
                }
            }
            if (entry.symbol.isNotBlank() && entry.fileNum > 0 && entry.fields >= 6) entries.add(entry)
        }
        val indexed = entries.distinctBy { it.symbol.uppercase(Locale.ROOT) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.symbol } })

        if (requestedSymbol.isBlank()) {
            return Result("", "", emptyList(),
                "تمت قراءة قائمة الأسهم من $indexName. عدد الأسهم: ${indexed.size}. ابحث باسم الشركة أو برمز السهم واختره لتحميل بيانات الأسعار.",
                indexed)
        }

        val selected = entries.firstOrNull { it.symbol.equals(requestedSymbol.trim(), true) }
            ?: error("رمز السهم ${requestedSymbol.trim()} غير موجود في فهرس MetaStock لهذا المجلد. اختر سهمًا من القائمة.")
        // XMASTER stores the MWD file number as a 16-bit value. Accommodate both
        // byte orders, but only accept a candidate whose actual file exists in this folder.
        val selectedRecordBytes = if (indexName == "XMASTER") {
            (recordSize until bytes.size step recordSize)
                .map { bytes.copyOfRange(it, it + recordSize) }
                .firstOrNull { ascii(it, 1, 15).equals(selected.symbol, true) }
        } else null
        val bigEndianNumber = selectedRecordBytes?.let {
            ((it[65].toInt() and 0xFF) shl 8) or (it[66].toInt() and 0xFF)
        } ?: selected.fileNum
        val littleEndianNumber = selectedRecordBytes?.let {
            ((it[66].toInt() and 0xFF) shl 8) or (it[65].toInt() and 0xFF)
        } ?: selected.fileNum
        val candidateNumbers = listOf(selected.fileNum, bigEndianNumber, littleEndianNumber).distinct()
        val matchedFile = candidateNumbers.firstNotNullOfOrNull { number ->
            val fileName = "F$number.${selected.dataExtension}"
            children[fileName.uppercase(Locale.ROOT)]?.let { uri -> fileName to uri }
        } ?: error("لم يتم العثور على ملف الأسعار للسهم ${selected.symbol}. تم البحث عن الملفات: ${candidateNumbers.joinToString { "F$it.${selected.dataExtension}" }}. تأكد أن ملفات MWD موجودة بجوار XMASTER داخل المجلد المختار.")
        val dataFileName = matchedFile.first
        val dataUri = matchedFile.second
        val data = resolver.openInputStream(dataUri)?.use { it.readBytes() } ?: error("تعذر قراءة ملف الأسعار")
        val recordBytes = selected.fields * 4
        if (data.size < recordBytes || data.size % recordBytes != 0) {
            error("تنسيق ملف الأسعار غير متوافق مع الفهرس؛ عدد الحقول المتوقع هو ${selected.fields}. قد يكون الملف ناقصًا أو غير تابع لهذا الفهرس.")
        }

        val candles = mutableListOf<Candle>()
        var scannedRecords = 0
        var rejectedRecords = 0
        var invalidDateRecords = 0
        var invalidPriceRecords = 0
        var invalidVolumeRecords = 0
        for (off in recordBytes until data.size step recordBytes) {
            if (off + recordBytes > data.size) break
            scannedRecords++
            val date = formatDate(mbf(data, off))
            if (date == null) { rejectedRecords++; invalidDateRecords++; continue }
            val o = mbf(data, off + 4); val h = mbf(data, off + 8); val l = mbf(data, off + 12)
            val c = mbf(data, off + 16); val v = mbf(data, off + 20)
            val pricesValid = o.isFinite() && h.isFinite() && l.isFinite() && c.isFinite() &&
                o > 0.0 && h > 0.0 && l > 0.0 && c > 0.0 &&
                h >= maxOf(o, c, l) && l <= minOf(o, c)
            if (!pricesValid) { rejectedRecords++; invalidPriceRecords++; continue }
            if (!v.isFinite() || v < 0.0) { rejectedRecords++; invalidVolumeRecords++; continue }
            candles.add(Candle(date, o, h, l, c, v))
        }
        val result = candles.sortedBy { it.date }.distinctBy { it.date }
        if (result.isEmpty()) error("لم يتم العثور على جلسات أسعار صالحة للتحليل. تحقق من ملفات السهم داخل مجلد MetaStock.")
        val duplicateRecords = candles.size - result.size
        val note = "MetaStock - السهم: ${selected.name.ifBlank { selected.symbol }} (${selected.symbol}) | ملف قائمة الأسهم: $indexName | عدد الرموز المفهرسة: ${entries.size} | ملف الأسعار: $dataFileName | السجلات التي تمت مراجعتها: $scannedRecords | السجلات السليمة: ${candles.size} | السجلات المكررة: $duplicateRecords | السجلات المستبعدة: $rejectedRecords (تاريخ غير صالح: $invalidDateRecords، أسعار غير منطقية: $invalidPriceRecords، حجم غير صالح: $invalidVolumeRecords) | عدد الجلسات بعد التنقية: ${result.size}."
        return Result(selected.symbol, selected.name, result, note, indexed)
    }

    private fun mbf(b: ByteArray, o: Int): Double {
        val exponent = b[o + 3].toInt() and 255
        if (exponent == 0) return 0.0
        val negative = (b[o + 2].toInt() and 128) != 0
        val mantissa = ((b[o + 2].toInt() and 127) shl 16) or
            ((b[o + 1].toInt() and 255) shl 8) or (b[o].toInt() and 255)
        val value = (1.0 + mantissa / 8388608.0) * Math.pow(2.0, exponent - 129.0)
        return if (negative) -value else value
    }

    private fun formatDate(v: Double): String? {
        if (!v.isFinite() || v <= 0) return null
        val encoded = v.toLong()
        val raw = if (encoded >= 1000000L) encoded - 1000000L else encoded
        val s = raw.toString().padStart(6, '0')
        if (s.length != 6) return null
        val yy = s.substring(0, 2).toIntOrNull() ?: return null
        val mm = s.substring(2, 4).toIntOrNull() ?: return null
        val dd = s.substring(4, 6).toIntOrNull() ?: return null
        val year = if (yy >= 70) 1900 + yy else 2000 + yy
        if (mm !in 1..12 || dd !in 1..31) return null
        return String.format(Locale.US, "%04d-%02d-%02d", year, mm, dd)
    }
}
