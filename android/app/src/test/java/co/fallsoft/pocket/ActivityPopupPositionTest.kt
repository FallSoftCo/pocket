package co.fallsoft.pocket

import androidx.compose.ui.unit.*
import org.junit.Assert.*
import org.junit.Test

class ActivityPopupPositionTest {
    @Test fun footerPopupMovesAboveImeWhenAnchorRemainsAtBottom(){
        val size=IntSize(800,420)
        val position=activityPopupOffset(IntRect(430,1700,530,1850),IntSize(1080,1920),size,8,700,30,24)
        assertTrue(position.y>=38)
        assertTrue(position.y+size.height<=1920-700-8)
    }
    @Test fun headerPopupKeepsBelowAnchorWhileKeyboardVisible(){
        val position=activityPopupOffset(IntRect(0,40,1080,120),IntSize(1080,1920),IntSize(800,420),8,700,30,24)
        assertEquals(128,position.y)
    }
    @Test fun enlargedPopupClampsBothAxesIntoVisibleArea(){
        val position=activityPopupOffset(IntRect(0,1300,60,1400),IntSize(1080,1920),IntSize(1000,1050),8,700,30,24)
        assertEquals(8,position.x)
        assertEquals(162,position.y)
    }
}
