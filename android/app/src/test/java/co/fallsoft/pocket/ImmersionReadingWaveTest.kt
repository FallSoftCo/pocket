package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class ImmersionReadingWaveTest {
    private val wave=ImmersionReadingWave()

    @Test fun laterRowsStartAndFinishLaterWithoutAccelerating() {
        // Same viewport locations whether these rows belong to one paragraph
        // or separate controls: no paragraph count enters the policy.
        for(row in listOf(0,1,3,9)) {
            val y=row*26.0
            val start=wave.onsetMs(y)
            assertEquals(row*350L,start)
            assertEquals(0f,wave.frame(start,y).progress,0f)
            assertEquals(.5f,wave.frame(start+600,y).progress,0f)
            assertEquals(1f,wave.frame(start+1200,y).progress,0f)
        }
        assertTrue(wave.frame(600,0.0).progress>wave.frame(600,26.0).progress)
    }

    @Test fun bothLanguagesHoldLocallyAndWavesRepeatWithoutACompletionBarrier() {
        for(y in listOf(0.0,26.0,234.0,900.0)) {
            val onset=wave.onsetMs(y)
            for(delta in listOf(1200L,2400L,4199L)) {
                val frame=wave.frame(onset+delta,y)
                assertTrue(frame.towardSupport)
                assertEquals(1f,frame.progress,0f)
            }
            assertFalse(wave.frame(onset+4200,y).towardSupport)
            assertEquals(1f,wave.frame(onset+5400,y).progress,0f)
            for(delta in listOf(0L,600L,1200L,4199L,4800L,8399L))
                assertEquals(wave.frame(onset+delta,y),wave.frame(onset+delta+wave.periodMs*10000,y))
        }
    }

    @Test fun complementaryInkNeverHasABlackGapAndKeepsOneHorizontalFrontier() {
        for(ms in 0L..1200L step 100L) {
            val forward=wave.frame(ms,0.0)
            val reverse=wave.frame(ms+wave.halfPeriodMs,0.0)
            for(x in listOf(0f,.1f,.5f,.9f,1f)) {
                val support=forward.supportCoverage(x)
                assertEquals(1f,support+reverse.supportCoverage(x),.00001f)
                assertTrue(support in 0f..1f)
            }
        }
        assertEquals(.5f,wave.frame(600,0.0).supportCoverage(.5f),.00001f)
    }

    @Test fun clockIsSafeAtLongRunningAndNegativeEpochs() {
        for(t in listOf(Long.MIN_VALUE,Long.MAX_VALUE,-1L,0L)) {
            val frame=wave.frame(t,100.0)
            assertEquals(wave.frame(Math.floorMod(t,wave.periodMs),100.0),frame)
            assertTrue(frame.progress in 0f..1f)
        }
    }

 @org.junit.Test fun viewportWaterfallNeverWrapsBeforeReachingTheBottom() {
  for(height in listOf(480.0,900.0,1400.0)) {
   val wave=ImmersionReadingWave.forViewport(height)
   assertTrue(wave.onsetMs(height)+wave.sweepMs<wave.halfPeriodMs)
   assertEquals(1600L,wave.onsetMs(height))
   for(time in 0L..wave.halfPeriodMs step 40L) {
    val coverage=(0..32).map{line->wave.frame(time,height*line/32).supportCoverage(.5f)}
    coverage.zipWithNext().forEach{(above,below)->assertTrue("Downward wave reversed at $time",above+0.0001f>=below)}
    val returning=(0..32).map{line->wave.frame(time+wave.halfPeriodMs,height*line/32).supportCoverage(.5f)}
    returning.zipWithNext().forEach{(above,below)->assertTrue("Returning wave reversed at $time",above<=below+0.0001f)}
   }
  }
 }
}

class ImmersionStableLineIdentityTest {
 @org.junit.Test fun separateContainersShareRowsWithoutOverlappingRowWipes(){
  val clock=ImmersionReadingWaveClock()
  clock.viewport(androidx.compose.ui.geometry.Offset.Zero,400f,800f,1f)
  val rect=androidx.compose.ui.geometry.Rect(0f,0f,150f,24f)
  clock.lines("first",androidx.compose.ui.geometry.Offset(0f,20f),listOf(rect))
  clock.lines("same-row",androidx.compose.ui.geometry.Offset(180f,20f),listOf(rect))
  clock.lines("next",androidx.compose.ui.geometry.Offset(0f,50f),listOf(rect))
  clock.advanceNanos(0)
  clock.advanceNanos(210_000_000)
  assertEquals(clock.lineMask("first",0f,false),clock.lineMask("same-row",0f,false))
  assertTrue(clock.lineMask("first",0f,false).center>clock.lineMask("next",0f,false).center)
  clock.advanceNanos(420_000_000)
  val completed=clock.lineMask("first",0f,false)
  assertTrue(completed.center>400f)
  assertTrue(clock.lineMask("next",0f,false).center in 0f..400f)
 }
 @org.junit.Test fun scrollingRecyclingAndLocalSelectionDoNotRemapOrPauseTheGlobalWave(){
  val clock=ImmersionReadingWaveClock()
  clock.viewport(androidx.compose.ui.geometry.Offset.Zero,400f,800f,1f)
  val a="row-a";val b="row-b"
  val rect=androidx.compose.ui.geometry.Rect(0f,0f,300f,24f)
  clock.lines(a,androidx.compose.ui.geometry.Offset(0f,100f),listOf(rect))
  clock.lines(b,androidx.compose.ui.geometry.Offset(0f,600f),listOf(rect))
  clock.advanceNanos(0);clock.advanceNanos(100_000_000)
  val before=clock.lineMask(b,0f,false)
  clock.lines(b,androidx.compose.ui.geometry.Offset(0f,200f),listOf(rect))
  org.junit.Assert.assertEquals("Scroll changed language without elapsed time",before,clock.lineMask(b,0f,false))
  clock.remove(b);clock.lines(b,androidx.compose.ui.geometry.Offset(0f,250f),listOf(rect))
  org.junit.Assert.assertEquals("Recycling borrowed another line phase",before,clock.lineMask(b,0f,false))
  val group=Any();clock.selecting(group,true)
  val frozen=clock.lineMask(a,0f,false,group)
  val elapsed=clock.elapsedMs;clock.advanceNanos(100_000_000)
  org.junit.Assert.assertEquals(elapsed+100,clock.elapsedMs)
  org.junit.Assert.assertEquals(frozen,clock.lineMask(a,0f,false,group))
  org.junit.Assert.assertTrue(clock.lineMask(a,0f,false).center>clock.lineMask(b,0f,false).center)
 }
}
