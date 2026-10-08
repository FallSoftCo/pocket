package co.fallsoft.pocket

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Compilation does not validate SymbolIcon's strict authored-asset registry. */
class VoiceSymbolContractTest {
    @Test fun everyUiControlHasAnAuthoredSymbol(){
        val root=File("src/main/java/co/fallsoft/pocket")
        val registry=Regex("\"([A-Za-z0-9]+)\" to R\\.drawable").findAll(File(root,"SymbolIcon.kt").readText()).map{it.groupValues[1]}.toSet()
        val controls=Regex("Icons\\.Rounded\\.([A-Za-z0-9]+)").findAll(root.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText().replace(Regex("(?<!Symbol)Icon\\([^)]+\\)"), "") }).map{it.groupValues[1]}.toSet()
        assertTrue("UI control symbols absent from authored registry: ${controls-registry}",controls.isNotEmpty()&&registry.isNotEmpty()&&registry.containsAll(controls))
    }
    @Test fun unknownSymbolUsesAuthoredAttentionFallback() {
        assertEquals("ErrorOutline", resolveAuthoredSymbol("HelpOutline"))
        assertEquals("Mic", resolveAuthoredSymbol("Mic"))
        assertEquals("ErrorOutline", resolveAuthoredSymbol(""))
    }
}
