package co.fallsoft.pocket

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*

internal fun markdownCellAlignment(cell:MarkdownTableCell):TextAlign=when(cell.alignment){
    "right"->TextAlign.Right
    "center"->TextAlign.Center
    else->TextAlign.Left
}

/** One shared horizontal viewport: every row retains exactly the same column geometry. */
@Composable internal fun MarkdownTableContent(
    table:MarkdownTable,reserve:List<MarkdownTable> = emptyList(),
    content:@Composable (MarkdownTableCell)->Unit
) {
    val density=LocalDensity.current
    val measurer=rememberTextMeasurer()
    val scroll=rememberScrollState()
    val columns=(listOf(table)+reserve).flatMap{it.rows}.maxOfOrNull{it.size}?:0
    if(columns==0)return
    val baseStyle=LocalTextStyle.current
    BoxWithConstraints(Modifier.fillMaxWidth()){
        val viewportWidth=maxWidth
        val natural=remember(table,reserve,density.density,density.fontScale,measurer,baseStyle,viewportWidth){
            (0 until columns).map { column->
                val cells=(listOf(table)+reserve).flatMap{it.rows.take(65)}.mapNotNull{it.getOrNull(column)}
                var preferred=0
                var longestWord=0
                cells.forEach { cell->
                    val styled=cell.content.annotated(clickableLinks=false)
                    val style=baseStyle.merge(TextStyle(fontSize=17.sp,lineHeight=26.sp,
                        fontWeight=if(cell.header)FontWeight.SemiBold else FontWeight.Normal))
                    fun width(value:AnnotatedString)=measurer.measure(value,style=style,
                        softWrap=false,maxLines=1).size.width
                    preferred=maxOf(preferred,width(styled))
                    Regex("\\S+").findAll(styled.text).forEach { word->
                        longestWord=maxOf(longestWord,width(styled.subSequence(word.range.first,word.range.last+1)))
                    }
                }
                val balancedCap=(viewportWidth*.55f).coerceIn(120.dp,280.dp)
                val tokenCap=viewportWidth.coerceAtLeast(280.dp)
                val preferredDp=with(density){preferred.toDp()+24.dp}
                val wordDp=with(density){longestWord.toDp()+24.dp}.coerceAtMost(tokenCap)
                preferredDp.coerceIn(64.dp,balancedCap).coerceAtLeast(wordDp)
            }
        }
        val extra=((viewportWidth-natural.fold(0.dp){sum,value->sum+value})/columns).coerceAtLeast(0.dp)
        val widths=natural.map{it+extra}
        Column {
            Column(Modifier.fillMaxWidth().horizontalScroll(scroll)
                .semantics{collectionInfo=CollectionInfo(table.rows.size,columns)}){
                table.rows.forEachIndexed { rowIndex,row->
                    Row(Modifier.height(IntrinsicSize.Min)){
                        for(column in 0 until columns){
                            val cell=row.getOrNull(column)?:MarkdownTableCell(MarkdownInline(""),"left",rowIndex==0,0)
                            Box(Modifier.width(widths[column]).heightIn(min=48.dp).fillMaxHeight()
                                .background(if(cell.header)Panel else if(rowIndex%2==0)Panel.copy(alpha=.35f)else Color.Transparent)
                                .border(1.dp,Line)
                                .semantics{
                                    collectionItemInfo=CollectionItemInfo(rowIndex,1,column,1)
                                    if(cell.header)heading()
                                }.padding(horizontal=12.dp,vertical=10.dp)){
                                content(cell)
                            }
                        }
                    }
                }
            }
            if(scroll.maxValue>0){
                // An unobtrusive thumb reveals horizontal overflow without a label or menu.
                val viewport=with(density){viewportWidth.roundToPx()}.coerceAtLeast(1)
                val fraction=viewport.toFloat()/(viewport+scroll.maxValue)
                val travel=viewportWidth*(1f-fraction)
                Box(Modifier.fillMaxWidth().padding(top=4.dp).height(3.dp).background(Line)){
                    Box(Modifier.offset(x=travel*(scroll.value.toFloat()/scroll.maxValue))
                        .width(viewportWidth*fraction).fillMaxHeight().background(Muted))
                }
            }
        }
    }
}
