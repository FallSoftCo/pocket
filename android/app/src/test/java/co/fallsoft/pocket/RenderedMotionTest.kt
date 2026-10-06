package co.fallsoft.pocket

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderedMotionTest {
    @Test fun sixtyFramesKeepTwoSecondLoopAndSkipStalls() {
        assertEquals(0, renderedFrame(0, 60, 120))
        assertEquals(59, renderedFrame(999_999_999L, 60, 120))
        assertEquals(60, renderedFrame(1_000_000_000L, 60, 120))
        assertEquals(119, renderedFrame(1_999_999_999L, 60, 120))
        assertEquals(0, renderedFrame(2_000_000_000L, 60, 120))
        assertEquals(78, renderedFrame(1_300_000_000L, 60, 120))
        assertEquals(0, renderedFrame(-1, 60, 120))
    }
    @Test fun highRefreshDisplaysRepeatRealFramesWithoutChangingSpeed() {
        val at120 = (0 until 240).map { renderedFrame(it * 1_000_000_000L / 120, 60, 120) }
        assertEquals((0 until 120).toList(), at120.distinct())
        assertEquals(119, at120.last())
        assertEquals(0, renderedFrame(8_000_000_000L, 60, 160))
    }
    @Test fun pagesRemainWithinPortableTextureDimensions() {
        val symbols = RenderedAtlas("motion", 120, 128, 5, 30)
        val busy = RenderedAtlas("busy", 160, 96, 8, 32)
        val mark = RenderedAtlas("mark", 160, 256, 8, 32)
        assertEquals(4, symbols.pages)
        assertEquals(5, busy.pages)
        assertEquals(5, mark.pages)
        assertEquals(2048, mark.columns * mark.cell)
        assertEquals(1024, (mark.pageFrames / mark.columns) * mark.cell)
    }
}
