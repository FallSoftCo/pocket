package co.fallsoft.pocket

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString

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
    // Punctuation belongs beside its preceding glyphs, inside the reserved
    // phrase. Keeping it outside would place a comma/period after padding.
    for(index in 0 until runs.lastIndex){
        val phrase=runs[index];val next=runs[index+1]
        if(phrase.id==null||next.id!=null)continue
        val punctuation=next.forms.first().text.takeWhile{it in ",.;:!?…"}
        if(punctuation.isEmpty())continue
        runs[index]=phrase.copy(forms=phrase.forms.mapIndexed{i,form->
            buildAnnotatedString{append(form);append(next.forms[i].subSequence(0,punctuation.length))}
        })
        runs[index+1]=next.copy(forms=next.forms.map{it.subSequence(punctuation.length,it.length)})
    }
    return runs.filter{it.forms.any{form->form.isNotEmpty()}}
}

/** One complete English scaffold, then complete Italian; phrase boundaries stay intact. */
internal fun immersionFlipForms(target:AnnotatedString,meaning:AnnotatedString):List<AnnotatedString>? {
    val runs=immersionSharedRuns(listOf(target,meaning))?:return null
    val eligible=runs.filter{it.id!=null&&it.forms[0].any{c->c.isLetter()}}
    if(eligible.isEmpty()||eligible.any{it.forms[1].isBlank()})return null
    return listOf(1,0).map{form->buildAnnotatedString{
        for(run in runs)append(run.forms[if(run.id!=null)form else 0])
    }}
}
