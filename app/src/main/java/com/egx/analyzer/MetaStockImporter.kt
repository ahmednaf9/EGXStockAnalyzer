package com.egx.analyzer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.util.Locale

/** MetaStock folder importer for classic EMASTER files and Fxxx.DAT/MWD data. */
object MetaStockImporter {
 data class Result(val symbol:String,val candles:List<Candle>,val note:String,val indexedSymbols:List<String>)

 fun importFolder(resolver:ContentResolver, tree:Uri, requestedSymbol:String=""):Result {
  val children=mutableMapOf<String,Uri>()
  val rootId=DocumentsContract.getTreeDocumentId(tree)
  val cursor=resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,rootId),arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null) ?: error("تعذرت قراءة محتويات المجلد. تأكد من منح التطبيق صلاحية الوصول إلى مجلد MetaStock.")
  cursor.use { while(it.moveToNext()){val id=it.getString(0);val name=it.getString(1);children[name.uppercase(Locale.ROOT)]=DocumentsContract.buildDocumentUriUsingTree(tree,id)} }
  val index=children["EMASTER"]?:children["XMASTER"]?:children["MASTER"]?:error("لم يتم العثور على ملف فهرس الأسهم داخل المجلد. اختر المجلد الرئيسي الذي يحتوي على أحد الملفات: EMASTER أو MASTER أو XMASTER.")
  val bytes=resolver.openInputStream(index)?.use{it.readBytes()}?:error("تعذرت قراءة ملف قائمة الأسهم")
  val isExtended=index==children["EMASTER"]
  val recordSize=if(isExtended)192 else 53
  if(bytes.size<recordSize||bytes.size%recordSize!=0)error("ملف فهرس الأسهم موجود، لكن حجمه لا يتوافق مع صيغة MetaStock المتوقعة. تحقق من اكتمال نسخ ملفات المجلد.")
  val entries=mutableListOf<Entry>()
  for(off in recordSize until bytes.size step recordSize){
   val rec=bytes.copyOfRange(off,off+recordSize)
   val fileNum=if(isExtended)rec[2].toInt() and 255 else rec[0].toInt() and 255
   val fields=if(isExtended)rec[6].toInt() and 255 else rec[5].toInt() and 255
   val symbolOffset=if(isExtended)11 else 37
   val symbolLength=if(isExtended)14 else 14
   val sym=rec.copyOfRange(symbolOffset,symbolOffset+symbolLength).toString(Charsets.US_ASCII).trim('\u0000',' ')
   if(sym.isNotBlank()&&fileNum>0&&fields>=6)entries.add(Entry(sym,fileNum,fields))
  }
  if(requestedSymbol.isBlank()) return Result("", emptyList(), "تمت قراءة قائمة الأسهم من MetaStock. ابحث عن رمز السهم واختره لتحميل بيانات الأسعار.", entries.map { it.symbol }.distinct().sorted())
  val selected=entries.firstOrNull{it.symbol.equals(requestedSymbol.trim(),true)}?:error("رمز السهم ${requestedSymbol.trim()} غير موجود في فهرس MetaStock لهذا المجلد. تحقق من كتابة الرمز أو اختر رمزًا من القائمة المعروضة.")
  val dataFileName=if(children.containsKey("F${selected.fileNum}.DAT")) "F${selected.fileNum}.DAT" else "F${selected.fileNum}.MWD"
  val dataUri=children[dataFileName]?:error("لم يتم العثور على ملف الأسعار الخاص بالسهم ${selected.symbol} (F${selected.fileNum}.DAT أو F${selected.fileNum}.MWD). تأكد من وجود جميع ملفات MetaStock في المجلد نفسه.")
  val data=resolver.openInputStream(dataUri)?.use{it.readBytes()}?:error("تعذر قراءة ملف الأسعار")
  val recordBytes=selected.fields*4
  if(data.size<recordBytes||data.size%recordBytes!=0)error("تنسيق ملف الأسعار غير متوافق مع الفهرس؛ عدد الحقول المتوقع هو ${selected.fields}. قد يكون الملف ناقصًا أو غير تابع لهذا الفهرس.")
  val candles=mutableListOf<Candle>()
  var scannedRecords=0
  var rejectedRecords=0
  var invalidDateRecords=0
  var invalidPriceRecords=0
  var invalidVolumeRecords=0
  for(off in recordBytes until data.size step recordBytes){
   if(off+recordBytes>data.size)break
   scannedRecords++
   val date=formatDate(mbf(data,off))
   if(date==null){rejectedRecords++;invalidDateRecords++;continue}
   val o=mbf(data,off+4);val h=mbf(data,off+8);val l=mbf(data,off+12);val c=mbf(data,off+16);val v=mbf(data,off+20)
   val pricesValid=o.isFinite() && h.isFinite() && l.isFinite() && c.isFinite() &&
    o>0.0 && h>0.0 && l>0.0 && c>0.0 &&
    h>=maxOf(o,c,l) && l<=minOf(o,c)
   if(!pricesValid){rejectedRecords++;invalidPriceRecords++;continue}
   if(!v.isFinite() || v<0.0){rejectedRecords++;invalidVolumeRecords++;continue}
   candles.add(Candle(date,o,h,l,c,v))
  }
  val result=candles.sortedBy{it.date}.distinctBy{it.date}
  if(result.isEmpty())error("لم يتم العثور على جلسات أسعار صالحة للتحليل. قد تكون تواريخ أو أسعار الملف غير سليمة؛ تحقق من ملفات السهم داخل مجلد MetaStock.")
  val duplicateRecords=candles.size-result.size
  val note="MetaStock - السهم: ${selected.symbol} | ملف قائمة الأسهم: ${if(isExtended) "EMASTER" else if(children.containsKey("XMASTER")) "XMASTER" else "MASTER"} | عدد الرموز المفهرسة: ${entries.size} | ملف الأسعار: $dataFileName | السجلات التي تمت مراجعتها: $scannedRecords | السجلات السليمة: ${candles.size} | السجلات المكررة: $duplicateRecords | السجلات المستبعدة: $rejectedRecords (تاريخ غير صالح: $invalidDateRecords، أسعار غير منطقية: $invalidPriceRecords، حجم غير صالح: $invalidVolumeRecords) | عدد الجلسات بعد التنقية: ${result.size}."
  return Result(selected.symbol,result,note,entries.map { it.symbol }.distinct().sorted())
 }
 private data class Entry(val symbol:String,val fileNum:Int,val fields:Int)
 private fun mbf(b:ByteArray,o:Int):Double {
  val exponent=b[o+3].toInt() and 255
  if(exponent==0)return 0.0
  val negative=(b[o+2].toInt() and 128)!=0
  val mantissa=((b[o+2].toInt() and 127) shl 16) or ((b[o+1].toInt() and 255) shl 8) or (b[o].toInt() and 255)
  val value=(1.0+mantissa/8388608.0)*Math.pow(2.0,exponent-129.0)
  return if(negative)-value else value
 }
 private fun formatDate(v:Double):String? {
  if(!v.isFinite()||v<=0)return null
  val encoded=v.toLong()
  val raw=if(encoded>=1000000L) encoded-1000000L else encoded
  val s=raw.toString().padStart(6,'0')
  if(s.length!=6)return null
  val yy=s.substring(0,2).toIntOrNull()?:return null
  val mm=s.substring(2,4).toIntOrNull()?:return null
  val dd=s.substring(4,6).toIntOrNull()?:return null
  val year=if(yy>=70)1900+yy else 2000+yy
  if(mm !in 1..12||dd !in 1..31)return null
  return String.format(Locale.US,"%04d-%02d-%02d",year,mm,dd)
 }
}
