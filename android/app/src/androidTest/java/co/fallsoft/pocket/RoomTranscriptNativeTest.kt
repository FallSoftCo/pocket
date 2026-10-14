package co.fallsoft.pocket

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomTranscriptNativeTest {
    @Test fun operationUpdatesCondenseAndNamesFollowUninterruptedBlocks(){
        val j=SessionRoomJourney()
        try{
            j.start(historyMode="condensed-history");j.click(j.lobby)
            j.await{j.device.hasObject(By.text("Violet returns after Amber"))}
            val list=requireNotNull(j.tag("room-history"))
            j.device.swipe(list.visibleBounds.centerX(),list.visibleBounds.top+100,list.visibleBounds.centerX(),list.visibleBounds.bottom-100,20)
            j.await{j.device.hasObject(By.desc("Expand command runs"))}
            assertEquals(1,j.device.findObjects(By.text("Amber")).size)
            assertFalse(j.device.hasObject(By.text("Synthetic command A completed")))
            j.click(By.desc("Expand command runs"))
            j.await{j.device.hasObject(By.text("Synthetic command A completed"))}
            assertFalse(j.device.hasObject(By.text("Running synthetic command A")))
            j.click(By.desc("Collapse command runs"))
            j.await{!j.device.hasObject(By.text("Synthetic command A completed"))}
            assertTrue(j.fixture().getJSONArray("requests").objects().none{it.s("method") in listOf("send","control","membership")})
        }finally{j.close()}
    }

    @Test fun roomEntryOpensLatestAndRefreshDoesNotMoveManualReading(){
        val j=SessionRoomJourney()
        try{
            j.start(historyMode="long-history");j.click(j.lobby)
            j.await{j.device.hasObject(By.text("LATEST ROOM MESSAGE"))}
            val list=requireNotNull(j.tag("room-history"));val bounds=list.visibleBounds
            j.device.swipe(bounds.centerX(),bounds.top+100,bounds.centerX(),bounds.bottom-100,30)
            j.await{!j.device.hasObject(By.text("LATEST ROOM MESSAGE"))}
            val before=j.device.findObjects(By.textContains("Earlier room message")).map{it.text}
            Thread.sleep(3500)
            assertEquals(before,j.device.findObjects(By.textContains("Earlier room message")).map{it.text})
            j.back();j.click(j.lobby);j.await{j.device.hasObject(By.text("LATEST ROOM MESSAGE"))}
        }finally{j.close()}
    }
}
