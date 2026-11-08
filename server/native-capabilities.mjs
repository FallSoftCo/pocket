// Audited against `codex app-server generate-json-schema --experimental`, 0.167.0.
// A protocol capability is not account entitlement or permission to use a tool.
export const AUDITED_NATIVE_VERSION='0.167.0';
const methods=new Set(['model/list','config/read','config/value/write','configRequirements/read','skills/list','skills/config/write','hooks/list','plugin/installed','plugin/list','plugin/read','plugin/install','plugin/uninstall','app/installed','app/list','mcpServerStatus/list','config/mcpServer/reload','thread/list','thread/read','thread/settings/update','thread/fork','thread/revert','thread/compact/start','review/start','fuzzyFileSearch','thread/items/read','thread/backgroundTerminals/list','thread/backgroundTerminals/terminate','thread/backgroundTerminals/clean']);
export function nativeVersion(codex){return /(?:^|[^\d])(\d+\.\d+\.\d+)(?:[^\d]|$)/.exec(codex.initializeResult?.userAgent||'')?.[1]||null;}
// Narrow follow-up for the observed Mac ARM64 daemon. These catalogs were read
// successfully on its pinned 0.163.0 build and compared with stock 0.163 schemas.
// thread/read is the schema-compatible context read already used by these views.
// Matching the reported runtime family is not binary attestation; this is not an
// audit of every 0.163 method/platform or any mutation.
export const MAC_0163_READ_PROFILE='mac-arm64-0.163-catalog-reads-20261107';
const mac0163Reads=new Set(['config/read','skills/list','mcpServerStatus/list','app/installed','plugin/installed','hooks/list','thread/list','thread/read','thread/backgroundTerminals/list']);
const mac0163Profile=({version,userAgent})=>version==='0.163.0'&&/^codex_pocket\/0\.163\.0 \(Mac OS [^;()]+; arm64\)/.test(userAgent||'');
const qualifiedMethod=(runtime,method)=>runtime.version===AUDITED_NATIVE_VERSION&&methods.has(method)||mac0163Profile(runtime)&&mac0163Reads.has(method);
export function supportsNativeMethod(codex,method){return qualifiedMethod({version:nativeVersion(codex),userAgent:codex.initializeResult?.userAgent},method);}
const featureMethods={models:'model/list',serviceTiers:'model/list',configuration:'config/read',skills:'skills/list',skillConfiguration:'skills/config/write',mcp:'mcpServerStatus/list',apps:'app/installed',plugins:'plugin/installed',pluginCatalog:'plugin/list',pluginDetails:'plugin/read',pluginInstallation:'plugin/install',pluginRemoval:'plugin/uninstall',hooks:'hooks/list',agents:'thread/list',fork:'thread/fork',revert:'thread/revert',compact:'thread/compact/start',review:'review/start',fileSearch:'fuzzyFileSearch',changeDirectory:'thread/settings/update',executionOutput:'thread/items/read',backgroundTerminals:'thread/backgroundTerminals/list'};
export function describeNativeCapabilities({version,userAgent,connected=true}={}){
 const qualified=version===AUDITED_NATIVE_VERSION,legacyReads=mac0163Profile({version,userAgent}),features={};
 const describe=method=>{
  const available=connected&&qualifiedMethod({version,userAgent},method);
  return {state:available?'available':'unavailable',method,reason:!connected?'Codex is disconnected.':available?legacyReads?'Read-only catalog inspection verified on the Mac ARM64 0.163 runtime. Configuration changes remain unavailable.':'Protocol verified; runtime policy and resource availability still apply.':legacyReads?'This operation is outside the qualified Mac 0.163 read-only catalog profile.':'This runtime version has not been qualified for this operation.'};
 };
 for(const [key,method]of Object.entries(featureMethods))features[key]=describe(method);
 // Additive diagnostics: existing clients already display state/reason and use
 // item.configurable. No client has to infer write authority from a readable list.
 for(const [feature,method]of Object.entries({configuration:'config/value/write',skills:'skills/config/write',mcp:'config/value/write',apps:'config/value/write',backgroundTerminals:'thread/backgroundTerminals/terminate'})){
  features[feature].mutation=describe(method);features[feature].readOnly=features[feature].state==='available'&&features[feature].mutation.state!=='available';
 }
 features.nativeRequests={state:qualified&&connected?'available':'unavailable',reason:'Only supported requests actually emitted by Codex can be answered.'};
 features.workspaceDiff={state:'available',reason:'Read-only local Git inspection; repository availability is checked on demand.'};
 for(const [key,reason]of Object.entries({worktree:'No native worktree lifecycle method is exposed by this protocol.',hookConfiguration:'Hook inspection is available; no hook configuration RPC is exposed.',agentSpawn:'Delegation is controlled by the parent Codex agent; no standalone agent-spawn RPC exists.',goals:'Goal controls are deliberately disabled. Opening this view never activates continuation.'}))features[key]={state:'unsupported',reason};
 if(legacyReads){features.backgroundTerminals.reason='Read-only terminal inspection verified on the Mac ARM64 0.163 runtime.';features.backgroundTerminals.mutation.reason='Termination has not been qualified on this runtime. Terminals can only be inspected.';}
 return {schemaVersion:1,runtime:{version:version||null,auditedVersion:AUDITED_NATIVE_VERSION,evidence:qualified?'installed-generated-schema':legacyReads?'observed-mac-reads-and-stock-generated-schema':'unqualified-runtime',...(legacyReads?{compatibilityProfile:MAC_0163_READ_PROFILE}:{})},features};
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
