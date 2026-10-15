// Computer ownership belongs to the host broker, never to a Codex turn.
export const COMPUTER_USE_INSTRUCTIONS=`Shared computer-use ownership: For NEW system76-pc browser jobs use the registered computer-use MCP tools browser_discover, browser_command, browser_status and browser_control. Discover supported commands and session readiness first; use opaque owned page handles. These tools provide host queue admission, durable operation identities and private page capabilities internally. Never add an outer manual lease, import historical SDKs, use raw bridge JavaScript or choose service release paths for new jobs. Queued work is accepted work: observe the same operation ID with browser_status, including after a lost reply; never repeat an uncertain posting, submission or other external effect. Existing accepted legacy tickets, protected drafts and uncertain operations retain their original identities until owner migration and reconciliation; do not replace them by matching a URL. Close only your own temporary pages after operations settle. Preserve account state, drafts, business authorization, deliberate stops, usage and release holds. Missing iframe, rich-editor, upload or native capabilities require owning adapter work, not a legacy fallback. Mac remains unqualified. BLD retains its explicit direct-development exemption and user game/desktop protections. Native desktop work outside this browser surface retains its existing configured host queue requirements. Never expose credentials or private page capabilities. Browser readiness does not authorize business actions or prove their success.`;

export function newThreadComputerUseOptions(existing='') {
  return {developerInstructions:[existing,COMPUTER_USE_INSTRUCTIONS].filter(Boolean).join('\n\n')};
}

export async function computerUseStatus({env=process.env,fetchImpl=fetch}={}) {
  const token=env.COMPUTER_USE_BROKER_TOKEN;
  if(!token)return {configured:false,available:false,enforcement:'cooperative',reason:'not_configured'};
  try {
    const url=new URL(env.COMPUTER_USE_BROKER_URL||'http://127.0.0.1:9340');
    if(url.protocol!=='http:'||!['127.0.0.1','localhost','[::1]'].includes(url.hostname))throw Error('Invalid broker URL');
    const response=await fetchImpl(new URL('/v1/status',url),{headers:{Authorization:`Bearer ${token}`},signal:AbortSignal.timeout(2000)});
    if(!response.ok)throw Error('Broker unavailable');
    const value=await response.json();
    // Never forward upstream bodies, owner identifiers, resource names or capabilities.
    const status={configured:true,available:true,enforcement:'cooperative'};
    for(const [key,upstream] of Object.entries({activeLeases:'leases',queuedRequests:'queued',quarantinedResources:'quarantined'})) {
      const count=value.counts?.[upstream];
      if(Number.isSafeInteger(count)&&count>=0)status[key]=count;
    }
    return status;
  } catch {
    return {configured:true,available:false,enforcement:'cooperative',reason:'broker_unavailable'};
  }
}
