package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
class LinkedImageTest {
 @Test fun markdownPlainAndLinkedImagesAreRecognizedWithoutChangingText(){
  val blocks=MarkdownContent.blocks("![A render](https://example.org/render.png)\n\n[Result](https://example.org/result.webp?q=1)\n\nhttps://example.org/photo.jpg\n\n`https://example.org/not-an-image.png`")
  assertEquals(listOf("https://example.org/render.png","https://example.org/result.webp?q=1","https://example.org/photo.jpg"),blocks.flatMap{it.content.images}.map{it.destination})
  assertEquals("A render",blocks.first().content.text)
 }
 @Test fun vectorAndAnimatedImageLinksAreRecognized(){
  val content=MarkdownContent.blocks("[Logo](https://example.org/logo.SVG?v=1) and https://example.org/motion.gif").single().content
  assertEquals(listOf("https://example.org/logo.SVG?v=1","https://example.org/motion.gif"),content.images.map{it.destination})
  assertFalse(LinkedImagePolicy.isImageUrl("https://example.org/page.html"))
 }
 @Test fun emptyAltImagesAreKeptAndNormalLinksRemainLinks(){
  assertEquals(1,MarkdownContent.blocks("![](https://example.org/image)").single().content.images.size)
  assertTrue(MarkdownContent.blocks("[Docs](https://example.org/docs)").single().content.images.isEmpty())
 }
 @Test fun narrativeImagePositionsRemainCorrectDuringInlineReplacement(){
  val source="Before ![one](https://example.org/one.png) between ![two](https://example.org/two.png) after."
  val parsed=MarkdownContent.blocks(source).single().content
  assertEquals(listOf("Before one"," between two"),listOf(parsed.text.substring(0,parsed.images[0].position),parsed.text.substring(parsed.images[0].position,parsed.images[1].position)))
  assertEquals(" after.",parsed.text.substring(parsed.images.last().position))
  val span=ImmersionSpan(0,6,"Before","Prima")
  val plan=ImmersionPresentation(contextualHybridText(source,listOf(span))!!,source,true,listOf(span))
  val hybrid=MarkdownContent.blocks(plan.text).single().content
  assertEquals("Prima one",hybrid.text.substring(0,hybrid.images[0].position))
  val range=MarkdownContent.replacementRanges(plan,plan.text,null).single()
  assertEquals("Prima",MarkdownContent.preview(plan.text).text.substring(range.start,range.end))
 }
 @Test fun opaqueLinkedFilesRequireMimeBeforeReservingAnImagePreview(){
  val linked=MarkdownContent.blocks("[Attachment](/api/files/123)").single().content.images.single()
  assertTrue(linked.requiresMimeCheck)
  assertFalse(MarkdownContent.blocks("![Declared image](/api/files/123)").single().content.images.single().requiresMimeCheck)
 }
 @Test fun credentialsAreConfinedToExactBackendFileOrigin(){
  val base="https://work.example:8443"
  assertTrue(LinkedImagePolicy.authenticated("https://work.example:8443/api/files/123",base))
  for(url in listOf("https://work.example/api/files/123","https://other.example:8443/api/files/123","https://work.example:8443/image.png"))assertFalse(LinkedImagePolicy.authenticated(url,base))
  assertEquals("https://work.example:8443/api/files/123",LinkedImagePolicy.resolve("/api/files/123",base))
  for(url in listOf("file:///secret.png","data:image/png;base64,x","//evil.example/api/files/1","/api/files/../secret","https://user:password@work.example/i.png"))assertNull(LinkedImagePolicy.resolve(url,base))
 }
}
