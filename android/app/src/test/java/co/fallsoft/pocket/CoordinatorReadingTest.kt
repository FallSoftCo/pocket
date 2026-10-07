package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class CoordinatorReadingTest {
    @Test fun scrollingThroughLongAnswerCoversEveryPortion(){
        val coverage=CoordinatorReadingCoverage()
        assertFalse(coverage.expose("answer",0,240,0,120))
        assertTrue(coverage.expose("answer",-120,240,0,120))
    }
    @Test fun missingMiddleAndRepeatedPartialVisibilityDoNotAcknowledge(){
        val coverage=CoordinatorReadingCoverage()
        repeat(3){assertFalse(coverage.expose("answer",0,300,0,100))}
        assertFalse(coverage.expose("answer",-200,300,0,100))
        assertTrue(coverage.expose("answer",-100,300,0,100))
    }
    @Test fun resizedAnswerAndDifferentTurnCannotReuseCoverage(){
        val coverage=CoordinatorReadingCoverage()
        assertFalse(coverage.expose("old",0,200,0,100))
        assertFalse(coverage.expose("new",-100,200,0,100))
        assertFalse(coverage.expose("old",-100,250,0,150))
        assertTrue(coverage.expose("old",0,250,0,100))
    }
    @Test fun absentAndEmptyViewportDoNotCountAsPresentation(){
        val coverage=CoordinatorReadingCoverage()
        assertFalse(coverage.expose("answer",200,120,0,100))
        assertFalse(coverage.expose("answer",0,120,50,50))
        assertTrue(coverage.expose("answer",0,120,0,120))
    }
}
