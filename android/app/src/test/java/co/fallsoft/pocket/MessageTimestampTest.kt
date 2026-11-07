package co.fallsoft.pocket
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
class MessageTimestampTest {
 @Test fun absoluteTimeUsesViewerDayAndTimezoneWithAccessibleDstDisambiguation(){
  val at=Instant.parse("2026-11-07T02:05:00Z").toEpochMilli()
  assertEquals("Nov 6 · 21:05",messageTime(at,zone=ZoneId.of("America/New_York"),locale=Locale.US)!!.label)
  assertEquals("Nov 7 · 07:35",messageTime(at,zone=ZoneId.of("Asia/Kolkata"),locale=Locale.US)!!.label)
  for((instant,zone) in listOf("2026-11-01T05:30:00Z" to "EDT","2026-11-01T06:30:00Z" to "EST"))assertTrue(messageTime(Instant.parse(instant).toEpochMilli(),zone=ZoneId.of("America/New_York"),locale=Locale.US)!!.full.endsWith(zone))
  assertNull(messageTime(null));assertNull(messageTime(0));assertNull(messageTime(Long.MAX_VALUE))
  assertEquals(messageTime(at),messageTime(null,at))
 }
}
