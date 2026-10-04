package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
class NotificationReadPolicyTest {
    @Test fun unresolvedAndUnknownAttentionNeverClears(){for(kind in listOf("question","approval","error")){assertFalse(NotificationReadPolicy.read(3,9,kind,true));assertFalse(NotificationReadPolicy.read(3,9,kind,null));assertTrue(NotificationReadPolicy.read(3,9,kind,false))}}
    @Test fun futureAndUnfetchedUpdatesStayUnread(){assertTrue(NotificationReadPolicy.read(2,3,"complete",null));assertFalse(NotificationReadPolicy.read(4,3,"update",null));assertFalse(NotificationReadPolicy.read(0,3,"update",null))}
}
