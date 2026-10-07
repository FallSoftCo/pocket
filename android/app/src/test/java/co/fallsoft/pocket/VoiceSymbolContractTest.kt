package co.fallsoft.pocket

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compilation does not validate SymbolIcon's strict authored-asset registry. */
class VoiceSymbolContractTest {
    @Test fun everyVoiceControlHasAnAuthoredSymbol(){
        val root=File("src/main/java/co/fallsoft/pocket")
        val registry=Regex("\"([A-Za-z0-9]+)\" to R\\.drawable").findAll(File(root,"SymbolIcon.kt").readText()).map{it.groupValues[1]}.toSet()
        val controls=Regex("Icons\\.Rounded\\.([A-Za-z0-9]+)").findAll(File(root,"VoiceScreen.kt").readText()).map{it.groupValues[1]}.toSet()
        assertTrue("Voice control symbols absent from authored registry: ${controls-registry}",controls.isNotEmpty()&&registry.isNotEmpty()&&registry.containsAll(controls))
    }
}
