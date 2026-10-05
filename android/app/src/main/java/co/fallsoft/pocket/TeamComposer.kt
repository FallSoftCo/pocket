package co.fallsoft.pocket

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** A shifted physical Enter is editing even when the soft keyboard uses Send. */
internal fun teamInsertNewline(value:TextFieldValue):TextFieldValue {
    val start=value.selection.min
    val end=value.selection.max
    return value.copy(text=value.text.replaceRange(start,end,"\n"),selection=TextRange(start+1),composition=null)
}
