package co.fallsoft.pocket

import org.commonmark.node.*
import org.commonmark.parser.Parser

internal data class MarkdownSpan(val start:Int,val end:Int,val kind:String,val destination:String="")
internal data class MarkdownInline(val text:String,val spans:List<MarkdownSpan> = emptyList())
internal data class MarkdownBlock(val content:MarkdownInline,val kind:String="paragraph",val level:Int=0,val prefix:String="",val language:String="")

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
        val text=StringBuilder();val spans=mutableListOf<MarkdownSpan>()
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
            val kind=when(node){is StrongEmphasis->"bold";is Emphasis->"italic";is Code->"code";is Link,is Image->"link";else->null}
            if(kind!=null&&text.length>start)spans.add(MarkdownSpan(start,text.length,kind,when(node){is Link->node.destination;is Image->node.destination;else->""}))
        }
        var child=parent.firstChild;while(child!=null){visit(child);child=child.next}
        return MarkdownInline(text.toString(),spans)
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
}
