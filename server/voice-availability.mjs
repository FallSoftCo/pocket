export const DEEPSEEK_TEXT_ONLY_REASON='DeepSeek is text-only in NextComp. Use a typed message; voice mode requires OpenAI Realtime.';

// Native voice is a Codex realtime feature. DeepSeek remains available for
// coordinator text, tools and session control, but it cannot accept audio.
export function voiceInputAvailability(providerUsage=null){
  const providerId=typeof providerUsage?.providerId==='string'?providerUsage.providerId:null;
  if(providerId==='deepseek')return {available:false,providerId,reason:DEEPSEEK_TEXT_ONLY_REASON};
  return {available:true,providerId,reason:null};
}

// Keep the older input-shaped response for existing clients while the current
// capability also suppresses manual speech output on a text-only provider.
export function voiceAvailability(providerUsage=null){
  const input=voiceInputAvailability(providerUsage);
  return {...input,input:input.available,output:input.available};
}
