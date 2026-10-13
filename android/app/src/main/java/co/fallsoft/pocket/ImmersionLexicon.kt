package co.fallsoft.pocket

/** One authored catalog owns translation and its target-grammar vocabulary scaffold.
 * Literal parameters are retained; executable syntax is never translated.
 */
object ImmersionLexicon {
    val common:Map<String,String> = InterfaceGrammarScaffolds.translations
    fun italian(text:String):String? = InterfaceGrammarScaffolds.italian(text)
}
