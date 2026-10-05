// Computer ownership belongs to the host broker, never to a Codex turn.
export const COMPUTER_USE_INSTRUCTIONS=`Shared computer-use ownership: Before browser or desktop control, use ComputerUseClient from the deployment-configured COMPUTER_USE_CLIENT_PATH with COMPUTER_USE_BROKER_URL (default http://127.0.0.1:9340), COMPUTER_USE_BROKER_TOKEN and an owner identifying NextComp plus your thread/task. Source the deployment-provided private worker-only $HOME/.config/computer-use-broker/worker.env in your worker shell without printing its contents; it supplies credentials and canonical resource names. Never print credentials or lease tokens. Never guess resource aliases. If the client path, broker or credentials are unavailable, do not perform computer control; unrelated coding and read-only shell work may continue. Acquire only around a bounded interaction using client.withLease({resources,priority:0,ttlMs:15000,waitMs:30000}, async lease => { await lease.withAction(async () => { /* observe and act */ }); }). Global input, foreground browser actions, focus changes and desktop capture/effect transactions require the configured COMPUTER_USE_DESKTOP_RESOURCE. Background DOM control requires the configured COMPUTER_USE_BROWSER_PROFILE_RESOURCE plus :page:<target> for your owned page; profile/account mutations require COMPUTER_USE_BROWSER_PROFILE_RESOURCE itself. Missing canonical resource configuration prohibits the corresponding control action. Acquire all needed resources together. Preserve existing tabs and focus/input state; before withAction returns, confirm the native worker has stopped and restore only your own held keys/input and changed focus where still safe. Never reset another owner's keyboard, pointer or tab. Exceptions quarantine the action conservatively; do not suppress uncertainty. Observe again after waiting; do not replay uncertain actions. Continuous lease tenure is capped at 30 seconds; renewals cannot extend that cap, and active actions drain safely. Release after each bounded interaction, yield at safe boundaries, and stop on cancellation or lost ownership. Do not hold a lease while thinking, coding or waiting for a network response. This instruction is cooperative; existing Codex tools are not automatically fenced by NextComp.`;

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
