package co.fallsoft.pocket

import android.content.ClipData
import android.content.ClipDescription
import android.text.SpannableStringBuilder
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.geometry.Rect

/** Projection tokens are owned by this selectable message, never original text. */
internal class ImmersionProjectionMarkers(private val original:String,private val nextId:()->String={java.util.UUID.randomUUID().toString()}){
    private val owned=linkedSetOf<String>()
    private var serial=0L
    @Synchronized fun create(forms:List<String>):String{
        val id=nextId()
        var value:String
        do{value="\u2063nextcomp-slot:$id:${serial++}\u2063"}
        while(value in owned||original.contains(value)||forms.any{it.contains(value)})
        owned.add(value)
        return value
    }
    @Synchronized fun snapshot():Set<String> = owned.toSet()
}
internal val LocalImmersionProjectionMarkers=staticCompositionLocalOf<ImmersionProjectionMarkers?>{null}
internal fun immersionOwnedProjectionRanges(text:String,owned:Set<String>):List<IntRange>{
    val ranges=mutableListOf<IntRange>()
    for(marker in owned.filter{it.isNotEmpty()}){
        var at=text.indexOf(marker)
        while(at>=0){ranges.add(at until at+marker.length);at=text.indexOf(marker,at+marker.length)}
    }
    return ranges.distinct().sortedByDescending{it.first}
}
@Composable internal fun ImmersionSelection(original:String,content:@Composable ()->Unit){
    val wave=LocalImmersionReadingWave.current
    val owner=remember{Any()}
    val nativeToolbar=LocalTextToolbar.current
    val toolbar=remember(nativeToolbar,wave,owner){object:TextToolbar by nativeToolbar {
        override fun showMenu(rect:Rect,onCopyRequested:(()->Unit)?,onPasteRequested:(()->Unit)?,
            onCutRequested:(()->Unit)?,onSelectAllRequested:(()->Unit)?) {
            wave?.selecting(owner,true)
            try{nativeToolbar.showMenu(rect,onCopyRequested,onPasteRequested,onCutRequested,onSelectAllRequested)}
            catch(error:Throwable){wave?.selecting(owner,false);throw error}
        }
        override fun showMenu(rect:Rect,onCopyRequested:(()->Unit)?,onPasteRequested:(()->Unit)?,
            onCutRequested:(()->Unit)?,onSelectAllRequested:(()->Unit)?,onAutofillRequested:(()->Unit)?) {
            wave?.selecting(owner,true)
            try{nativeToolbar.showMenu(rect,onCopyRequested,onPasteRequested,onCutRequested,onSelectAllRequested,onAutofillRequested)}
            catch(error:Throwable){wave?.selecting(owner,false);throw error}
        }
        override fun hide(){try{nativeToolbar.hide()}finally{wave?.selecting(owner,false)}}
    }}
    DisposableEffect(wave,owner){onDispose{wave?.selecting(owner,false)}}
    val delegate=LocalClipboard.current
    val markers=remember(original){ImmersionProjectionMarkers(original)}
    val clipboard=remember(delegate,markers){object:Clipboard by delegate{
        override suspend fun setClipEntry(clipEntry:ClipEntry?){
            val data=clipEntry?.clipData
            if(data==null||data.itemCount!=1){delegate.setClipEntry(clipEntry);return}
            val item=data.getItemAt(0)
            val text=item.text
            if(text==null||item.uri!=null||item.intent!=null||item.htmlText!=null){delegate.setClipEntry(clipEntry);return}
            val ranges=immersionOwnedProjectionRanges(text.toString(),markers.snapshot())
            if(ranges.isEmpty()){delegate.setClipEntry(clipEntry);return}
            val clean=SpannableStringBuilder(text)
            ranges.forEach{clean.delete(it.first,it.last+1)}
            delegate.setClipEntry(ClipEntry(ClipData(ClipDescription(data.description),ClipData.Item(clean))))
        }
    }}
    CompositionLocalProvider(LocalClipboard provides clipboard,LocalImmersionProjectionMarkers provides markers,LocalTextToolbar provides toolbar){
        SelectionContainer(content=content)
    }
}
