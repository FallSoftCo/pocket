package co.fallsoft.pocket

import androidx.compose.ui.text.AnnotatedString

/** Structural inputs for a shared inline layout. Offsets remain native to each
 * form; unchanged runs cannot silently be paired by fuzzy/text-only matching. */
internal data class ImmersionSharedRun(
    val id:String?,val forms:List<AnnotatedString>
)
internal fun immersionSharedRuns(forms:List<AnnotatedString>):List<ImmersionSharedRun>? {
    if(forms.isEmpty())return emptyList()
    val marks=forms.map{form->form.getStringAnnotations("immersion-reserve",0,form.length).sortedBy{it.start}}
    val ids=marks.first().map{it.item}
    if(ids.distinct().size!=ids.size||marks.any{it.map{range->range.item}!=ids})return null
    if(marks.any{ranges->ranges.zipWithNext().any{(a,b)->a.end>b.start}})return null
    val runs=mutableListOf<ImmersionSharedRun>()
    val cursors=IntArray(forms.size)
    for(index in ids.indices){
        val before=forms.indices.map{i->forms[i].subSequence(cursors[i],marks[i][index].start)}
        // The shared-layout guarantee applies only to verified unchanged text.
        if(before.any{it.text!=before.first().text})return null
        if(before.first().isNotEmpty())runs+=ImmersionSharedRun(null,before)
        runs+=ImmersionSharedRun(ids[index],forms.indices.map{i->
            val range=marks[i][index];cursors[i]=range.end
            forms[i].subSequence(range.start,range.end)
        })
    }
    val tail=forms.indices.map{i->forms[i].subSequence(cursors[i],forms[i].length)}
    if(tail.any{it.text!=tail.first().text})return null
    if(tail.first().isNotEmpty())runs+=ImmersionSharedRun(null,tail)
    return runs
}
