package co.fallsoft.pocket

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderChoiceTest {
    private fun view(default:String,vararg providers:JSONObject)=JSONObject().put("state","available").put("defaultProviderId",default)
        .put("providers",JSONArray(providers.toList()))

    @Test fun `default DeepSeek provider carries its model and effort`(){
        val choice=providerDefaultChoice(view("deepseek",
            JSONObject().put("id","deepseek").put("baseUrl","https://api.deepseek.com/v1"),
            JSONObject().put("id","openai")))
        assertEquals("deepseek",choice?.getString("providerId"))
        assertEquals("deepseek-flash",choice?.getString("model"))
        assertEquals("high",choice?.getString("effort"))
    }

    @Test fun `default OpenAI provider stays on Codex authentication`(){
        val choice=providerDefaultChoice(view("openai",
            JSONObject().put("id","deepseek").put("baseUrl","https://api.deepseek.com/v1"),
            JSONObject().put("id","openai")))
        assertEquals("openai",choice?.getString("providerId"))
        assertNull(choice?.optString("model")?.takeIf{it.isNotBlank()})
    }

    @Test fun `unavailable provider state does not invent a selection`(){
        assertNull(providerDefaultChoice(JSONObject().put("state","unavailable").put("defaultProviderId","deepseek")))
    }
}
