package co.fallsoft.pocket

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class GalleryPagePreviewTest {
    @Test fun publishedPageFormsPreserveRelativeMediaAndNeverHonorForeignBaseTags() {
        for (path in listOf("/8bar/review/", "/8bar/review/index.html#capture", "/8bar/index.html#card", "/8bar", "/8bar/#card")) {
            assertNotNull(path,GalleryCardLinks.target("https://gallery.example$path"))
            assertTrue(MarkdownContent.blocks("[Review](https://gallery.example$path)").single().content.images.single().gallery)
        }
        val page=URI("https://gallery.example/8bar/review/index.html")
        val result=galleryPagePreview("<title>Real &amp; original</title><base href='https://wrong.example/'><figure><img src='../image.png'></figure>",page)
        assertEquals(GalleryPreviewCard("Real & original","https://gallery.example/8bar/image.png"),result)
        assertNull(galleryPagePreview("<img src='https://foreign.example/secret.png'><img src='../../../private.png'>",page))
        assertNull(GalleryCardLinks.target("https://name:secret@gallery.example/8bar/review/"))
        assertNull(GalleryCardLinks.target("https://gallery.example/8bar/../../private/"))
    }
}
