// Audited against `codex app-server generate-json-schema --experimental`, 0.167.0.
// A protocol capability is not account entitlement or permission to use a tool.
export const AUDITED_NATIVE_VERSION='0.167.0';
const methods=new Set(['model/list','config/read','config/value/write','configRequirements/read','skills/list','skills/config/write','hooks/list','plugin/installed','app/installed','app/list','mcpServerStatus/list','config/mcpServer/reload','thread/list','thread/read','thread/settings/update','thread/fork','thread/revert','thread/compact/start','review/start','fuzzyFileSearch','thread/items/read','thread/backgroundTerminals/list','thread/backgroundTerminals/terminate','thread/backgroundTerminals/clean']);
export function nativeVersion(codex){return /(?:^|[^\d])(\d+\.\d+\.\d+)(?:[^\d]|$)/.exec(codex.initializeResult?.userAgent||'')?.[1]||null;}
export function supportsNativeMethod(codex,method){return nativeVersion(codex)===AUDITED_NATIVE_VERSION&&methods.has(method);}
const featureMethods={models:'model/list',serviceTiers:'model/list',configuration:'config/read',skills:'skills/list',skillConfiguration:'skills/config/write',mcp:'mcpServerStatus/list',apps:'app/installed',plugins:'plugin/installed',hooks:'hooks/list',agents:'thread/list',fork:'thread/fork',revert:'thread/revert',compact:'thread/compact/start',review:'review/start',fileSearch:'fuzzyFileSearch',changeDirectory:'thread/settings/update',executionOutput:'thread/items/read',backgroundTerminals:'thread/backgroundTerminals/list'};
export function describeNativeCapabilities({version,connected=true}={}){
 const qualified=version===AUDITED_NATIVE_VERSION,features={};
 for(const [key,method]of Object.entries(featureMethods))features[key]={state:qualified&&connected?'available':'unavailable',method,reason:!connected?'Codex is disconnected.':qualified?'Protocol verified; runtime policy and resource availability still apply.':'This runtime version has not been qualified for this operation.'};
 features.nativeRequests={state:qualified&&connected?'available':'unavailable',reason:'Only supported requests actually emitted by Codex can be answered.'};
 features.workspaceDiff={state:'available',reason:'Read-only local Git inspection; repository availability is checked on demand.'};
 for(const [key,reason]of Object.entries({worktree:'No native worktree lifecycle method is exposed by this protocol.',hookConfiguration:'Hook inspection is available; no hook configuration RPC is exposed.',pluginInstallation:'Native plugin install APIs remain under development; manage installation in Codex.',agentSpawn:'Delegation is controlled by the parent Codex agent; no standalone agent-spawn RPC exists.',goals:'Goal controls are deliberately disabled. Opening this view never activates continuation.'}))features[key]={state:'unsupported',reason};
 return {schemaVersion:1,runtime:{version:version||null,auditedVersion:AUDITED_NATIVE_VERSION,evidence:qualified?'installed-generated-schema':'unqualified-runtime'},features};
}
export function observedRuntimeSettings(codex,threadId){
 const observation=codex.runtimeSettings?.get(threadId);
 return observation?{state:'observed',...observation,notice:'Last native response observed on this connection; other clients may have changed settings since.'}:{state:'unavailable',reason:'No permission-bearing native response has been observed on this connection. Reading configuration does not resume a session.'};
}
export function rememberRuntimeSettings(codex,method,result){
 if(!['thread/start','thread/resume','thread/fork','thread/settings/update'].includes(method))return;
 const id=result?.thread?.id;if(!id||!('approvalPolicy'in result)||!('sandbox'in result))return;
 codex.runtimeSettings??=new Map();
 const picked={};for(const key of ['model','reasoningEffort','serviceTier','approvalPolicy','sandbox','activePermissionProfile','cwd'])if(key in result)picked[key]=result[key];
 codex.runtimeSettings.delete(id);codex.runtimeSettings.set(id,{source:method,observedAt:new Date().toISOString(),settings:picked});
 if(codex.runtimeSettings.size>256)codex.runtimeSettings.delete(codex.runtimeSettings.keys().next().value);
}
