package co.fallsoft.pocket

import org.commonmark.node.*
import org.commonmark.parser.Parser

internal data class MarkdownSpan(val start:Int,val end:Int,val kind:String,val destination:String="")
internal data class MarkdownImage(val destination:String,val description:String,val requiresMimeCheck:Boolean=false,val position:Int=Int.MAX_VALUE)
internal data class MarkdownInline(val text:String,val spans:List<MarkdownSpan> = emptyList(),val images:List<MarkdownImage> = emptyList())
internal data class MarkdownBlock(val content:MarkdownInline,val kind:String="paragraph",val level:Int=0,val prefix:String="",val language:String="")
internal data class ImmersionDisplayRange(val span:ImmersionSpan,val start:Int,val end:Int)

/** Parse content rather than stripping delimiters; code and escaped punctuation stay literal. */
internal object MarkdownContent {
    private val parser=Parser.builder().build()
    fun blocks(raw:String):List<MarkdownBlock> {
        val result=mutableListOf<MarkdownBlock>()
        fun walk(parent:Node,depth:Int=0,quote:Boolean=false){
            var node=parent.firstChild
            while(node!=null){
                when(val current=node){
                    is Paragraph->result.add(MarkdownBlock(inline(current),if(quote)"quote" else "paragraph",depth))
                    is Heading->result.add(MarkdownBlock(inline(current),"heading",current.level))
                    is FencedCodeBlock->result.add(MarkdownBlock(MarkdownInline(current.literal.trimEnd('\n')),"code",language=current.info.orEmpty().substringBefore(' ')))
                    is IndentedCodeBlock->result.add(MarkdownBlock(MarkdownInline(current.literal.trimEnd('\n')),"code"))
                    is ThematicBreak->result.add(MarkdownBlock(MarkdownInline(""),"rule"))
                    is BlockQuote->walk(current,depth,true)
                    is BulletList,is OrderedList->{
                        var item=current.firstChild;var number=if(current is OrderedList)current.markerStartNumber else 1
                        while(item!=null){val begin=result.size;walk(item,depth+1,quote)
                            if(result.size>begin){val first=result[begin];result[begin]=first.copy(prefix=if(current is OrderedList)"${number++}. " else "• ")}
                            item=item.next
                        }
                    }
                    is HtmlBlock->result.add(MarkdownBlock(MarkdownInline(current.literal),"paragraph",depth))
                    else->walk(current,depth,quote)
                }
                node=node.next
            }
        }
        walk(parser.parse(raw));return result
    }
    private fun inline(parent:Node):MarkdownInline {
        val text=StringBuilder();val spans=mutableListOf<MarkdownSpan>();val images=mutableListOf<MarkdownImage>()
        fun visit(node:Node){
            val start=text.length
            when(node){
                is Text->text.append(node.literal)
                is Code->text.append(node.literal)
                is SoftLineBreak->text.append('\n')
                is HardLineBreak->text.append('\n')
                is HtmlInline->text.append(node.literal)
                else->{var child=node.firstChild;while(child!=null){visit(child);child=child.next}}
            }
            if(node is Image)images.add(MarkdownImage(node.destination,text.substring(start),position=text.length))
            if(node is Link&&LinkedImagePolicy.isImageUrl(node.destination))images.add(MarkdownImage(node.destination,text.substring(start),LinkedImagePolicy.isOpaqueFileLink(node.destination),text.length))
            val kind=when(node){is StrongEmphasis->"bold";is Emphasis->"italic";is Code->"code";is Link,is Image->"link";else->null}
            if(kind!=null&&text.length>start)spans.add(MarkdownSpan(start,text.length,kind,when(node){is Link->node.destination;is Image->node.destination;else->""}))
        }
        var child=parent.firstChild;while(child!=null){visit(child);child=child.next}
        Regex("https?://[^\\s<>]+",RegexOption.IGNORE_CASE).findAll(text).forEach{match->
            val url=match.value.trimEnd('.',',',')',';','!')
            if(spans.none{it.kind in listOf("code","link")&&it.start<=match.range.first&&it.end>match.range.first}&&LinkedImagePolicy.isImageUrl(url))images.add(MarkdownImage(url,"Image",LinkedImagePolicy.isOpaqueFileLink(url),match.range.first+url.length))
        }
        return MarkdownInline(text.toString(),spans,images.distinctBy{it.destination}.take(8))
    }
    fun preview(raw:String):MarkdownInline {
        val text=StringBuilder();val spans=mutableListOf<MarkdownSpan>()
        for(block in blocks(raw)){
            if(block.kind=="rule")continue
            if(text.isNotEmpty())text.append('\n')
            text.append(block.prefix);val offset=text.length;text.append(block.content.text)
            spans.addAll(block.content.spans.map{it.copy(start=it.start+offset,end=it.end+offset)})
        }
        return MarkdownInline(text.toString(),spans)
    }
    /** Resolve each exact source-anchored occurrence through Markdown, never by guessing a word match. */
    fun replacementRanges(plan:ImmersionPresentation,shown:String,selected:ImmersionSpan?):List<ImmersionDisplayRange>{
        if(contextualHybridText(plan.source,plan.spans)!=plan.text||shown!=immersionReplacementText(plan,selected)||shown.contains('\uE000')||shown.contains('\uE001'))return emptyList()
        var delta=0;var cursor=0
        val marked=buildString {
            plan.spans.forEachIndexed { index,span ->
                val visible=if(span==selected)span.source else span.target
                val start=span.start+delta;val end=start+visible.length
                if(start<cursor||end>shown.length)return emptyList()
                append(shown,cursor,start);append("\uE000S${index}\uE001");append(shown,start,end);append("\uE000E${index}\uE001")
                cursor=end;delta+=visible.length-span.source.length
            }
            append(shown,cursor,shown.length)
        }
        val rendered=preview(marked).text;val clean=StringBuilder();val starts=mutableMapOf<Int,Int>();val ranges=mutableListOf<ImmersionDisplayRange>();cursor=0
        Regex("\uE000([SE])(\\d+)\uE001").findAll(rendered).forEach { marker ->
            clean.append(rendered,cursor,marker.range.first);val index=marker.groupValues[2].toInt()
            if(marker.groupValues[1]=="S")starts[index]=clean.length
            else starts[index]?.let{start->if(index in plan.spans.indices&&clean.length>start)ranges+=ImmersionDisplayRange(plan.spans[index],start,clean.length)}
            cursor=marker.range.last+1
        }
        clean.append(rendered,cursor,rendered.length)
        // Markers never escape into presentation/copy. Any parser ambiguity withholds help safely.
        return if(clean.toString()==preview(shown).text)ranges else emptyList()
    }
}
