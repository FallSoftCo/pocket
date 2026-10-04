package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
class BilingualContentTest {
 @Test fun paragraphsPairWithAnEnglishSupportLane(){val rows=bilingualUnits("Hello.\n\nReady?","Ciao.\n\nPronto?");assertEquals(2,rows.size);assertEquals("Hello.",rows[0].original);assertEquals("Pronto?",rows[1].target)}
 @Test fun fencedCodeAppearsOnlyOnce(){val original="Run this.\n\n```sh\ngit status\n```\n\nThen wait.";val target="Esegui questo.\n\n```sh\ngit status\n```\n\nPoi aspetta.";val rows=bilingualUnits(original,target);assertEquals(3,rows.size);assertTrue(rows[1].code);assertNull(rows[1].original);assertTrue(rows[1].target.contains("git status"))}
 @Test fun unmatchedParagraphsUseWholeMessageSupportWithoutDuplicatingCode(){val rows=bilingualUnits("First.\n\n```sh\nls\n```\n\nSecond.","Primo. Secondo.\n\n```sh\nls\n```");assertEquals(1,rows.size);assertFalse(rows[0].original!!.contains("```"));assertTrue(rows[0].target.contains("```"))}
 @Test fun commonLabelsAvailableOfflineButPathsAndShellSyntaxUntouched(){assertEquals("Invia",ImmersionLexicon.italian("Send"));assertEquals("In coda",ImmersionLexicon.italian("Queue"));assertNull(ImmersionLexicon.italian("git status"));assertNull(ImmersionLexicon.italian("/path/Send"));assertEquals("Checks Git status",ImmersionCommands.meaning("git status --short")!!.second);assertNull(ImmersionCommands.meaning("git statusful"))}
 @Test fun tildeAndFourBacktickFencesKeepNestedMarkersInSingleCodeUnit(){
  for(fence in listOf("~~~","````")){
   val original="Before.\n\n${fence}sh\n```\necho ok\n```\n$fence\n\nAfter."
   val target="Prima.\n\n${fence}sh\n```\necho ok\n```\n$fence\n\nDopo."
   val rows=bilingualUnits(original,target);assertEquals(3,rows.size);assertTrue(rows[1].code);assertNull(rows[1].original);assertTrue(rows[1].target.contains("echo ok"))
  }
 }
 @Test fun fenceWithTrailingWordsDoesNotCloseCode(){val original="Before.\n\n~~~~sh\necho ok\n~~~~ not a closer\necho done\n~~~~\n\nAfter.";val target=original.replace("Before.","Prima.").replace("After.","Dopo.");val rows=bilingualUnits(original,target);assertEquals(3,rows.size);assertTrue(rows[1].code);assertTrue(rows[1].target.contains("echo done"));assertNull(rows[1].original)}

}
