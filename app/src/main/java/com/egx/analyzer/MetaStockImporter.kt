package com.egx.analyzer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.util.Locale

/** Classic MetaStock EMASTER folder reader. */
object MetaStockImporter {
 data class Result(val symbol:String,val candles:List<Candle>,val note:String)
 fun importFolder(resolver:ContentResolver, tree:Uri, requestedSymbol:String=""):Result {
  val children=mutableMapOf<String,Uri>(); val rootId=DocumentsContract.getTreeDocumentId(tree)
  val cursor=resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,rootId),arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null) ?: error("تعذر قراءة المجلد")
  cursor.use { while(it.moveToNext()){val id=it.getString(0);val name=it.getString(1);children[name.uppercase(Locale.ROOT)]=DocumentsContract.buildDocumentUriUsingTree(tree,id)} }
  val master=children["EMASTER"]?:error("لم أجد EMASTER")
  val bytes=resolver.openInputStream(master)?.use{it.readBytes()}?:error("تعذر قراءة EMASTER")
  if(bytes.size<192||bytes.size%192!=0)error("حجم EMASTER غير متوافق")
  val entries=mutableListOf<Pair<String,Int>>()
  for(off in 192 until bytes.size step 192){val rec=bytes.copyOfRange(off,off+192);val sym=rec.copyOfRange(11,25).toString(Charsets.US_ASCII).trim('\u0000',' ');val num=rec[2].toInt() and 255;if(sym.isNotBlank()&&num>0)entries.add(sym to num)}
  val selected=entries.firstOrNull{it.first.equals(requestedSymbol.trim(),true)}?:entries.firstOrNull()?:error("لم يتم العثور على رموز")
  val uri=children["F${selected.second}.DAT"]?:children["F${selected.second}.MWD"]?:error("لم أجد ملف أسعار للسهم ${selected.first}")
  val data=resolver.openInputStream(uri)?.use{it.readBytes()}?:error("تعذر قراءة ملف الأسعار")
  val candles=mutableListOf<Candle>();val size=28
  for(off in size until data.size step size){if(off+size>data.size)break;val date=formatDate(mbf(data,off))?:continue;val o=mbf(data,off+4);val h=mbf(data,off+8);val l=mbf(data,off+12);val c=mbf(data,off+16);val v=mbf(data,off+20);val x=Candle(date,o,h,l,c,v.coerceAtLeast(0.0));if(x.isValidForAnalysis())candles.add(x)}
  val result=candles.sortedBy{it.date}.distinctBy{it.date};if(result.isEmpty())error("لم يتم فك بيانات السعر بصورة سليمة")
  return Result(selected.first,result,"MetaStock: ${result.size} جلسة للسهم ${selected.first}.")
 }
 private fun mbf(b:ByteArray,o:Int):Double{val exp=b[o+3].toInt() and 255;if(exp==0)return 0.0;val sign=(b[o+2].toInt() and 128)!=0;val m=((b[o+2].toInt() and 127) shl 16)|((b[o+1].toInt() and 255) shl 8)|(b[o].toInt() and 255);val v=(1.0+m/8388608.0)*Math.pow(2.0,exp-129.0);return if(sign)-v else v}
 private fun formatDate(v:Double):String?{val s=v.toLong().toString().padStart(6,'0');val yy=s.substring(0,2).toIntOrNull()?:return null;val mm=s.substring(2,4).toIntOrNull()?:return null;val dd=s.substring(4,6).toIntOrNull()?:return null;val year=if(yy>=70)1900+yy else 2000+yy;if(mm !in 1..12||dd !in 1..31)return null;return String.format(Locale.US,"%04d-%02d-%02d",year,mm,dd)}
}
