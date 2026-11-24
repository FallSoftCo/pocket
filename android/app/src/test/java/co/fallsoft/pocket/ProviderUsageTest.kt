package co.fallsoft.pocket

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderUsageTest{
    private val deepSeek=ProviderUsage.fromJson(JSONObject().put("providerId","deepseek").put("configured",true).put("available",true).put("currency","USD").put("totalBalance","9.96").put("updatedAt",1000).put("stale",false).put("state","available"))!!

    @Test fun deepSeekBalanceIsAnAccountAmountNotCodexCredits(){
        assertTrue(deepSeek.isDeepSeek())
        assertEquals("$9.96",deepSeek.compactBalanceLabel())
        assertTrue(JSONObject().put("providerId","deepseek").toString().contains("deepseek"))
    }

    @Test fun providerBalanceOnlyReplacesStockFooterForTheMatchingProvider(){
        assertSame(deepSeek,providerFooterUsage(deepSeek,"deepseek"))
        assertSame(deepSeek,providerFooterUsage(deepSeek,""))
        assertNull(providerFooterUsage(deepSeek,"openai"))
        assertNull(providerFooterUsage(null,"deepseek"))
        assertNull(providerFooterUsage(deepSeek,"deepseek","Team"))
    }

    @Test fun tokenFooterUsesCompactObservedTotalsOnly(){
        val usage=ConversationUsage.fromJson(JSONObject().put("last",JSONObject().put("totalTokens",13200)).put("total",JSONObject().put("totalTokens",48600)).put("modelContextWindow",128000))
        assertEquals("13.2k ctx · 48.6k total",usage?.footerLabel())
        assertEquals(10,usage?.contextPercent())
        assertNull(ConversationUsage.fromJson(JSONObject())?.footerLabel())
        assertEquals("750",compactTokenCount(750))
        assertEquals("1m",compactTokenCount(1_000_000))
    }

    @Test fun malformedBalanceAndProviderStayUnavailable(){
        assertNull(ProviderUsage.fromJson(JSONObject().put("providerId","bad provider").put("totalBalance","9.96")))
        val malformed=ProviderUsage.fromJson(JSONObject().put("providerId","deepseek").put("totalBalance","9.96; rm -rf"))!!
        assertEquals("Balance unavailable",malformed.balanceLabel())
    }
}
