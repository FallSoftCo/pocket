import {createHash} from 'node:crypto';
import {supportsNativeMethod} from './native-capabilities.mjs';
import {admissionTransaction} from './human-ingress.mjs';
const fail=(message,status=409)=>Object.assign(Error(message),{status,mcpReload:true});
const id=v=>typeof v==='string'&&/^[A-Za-z0-9_-]{1,150}$/.test(v);
export const MCP_RELOAD_NOTICE='Reload MCP configuration across this host’s loaded conversations, not just this server or conversation. This can refresh connections and tool availability, invalidate resource caches, and stop connections whose enterprise authorization was revoked. Native errors can follow partial changes. No model turn is started; acknowledgement does not prove that every tool is ready.';
/** Parameterless host operation. The original RPC observer outlives the UI wait. */
export class NativeMcpReload {
 constructor({db,codex,admissions,source}){Object.assign(this,{db,codex,admissions,source});db.exec(`CREATE TABLE IF NOT EXISTS native_mcp_reload(id TEXT PRIMARY KEY,device TEXT NOT NULL,thread TEXT NOT NULL,binding TEXT NOT NULL,state TEXT NOT NULL,native_outcome TEXT,at INTEGER NOT NULL); UPDATE native_mcp_reload SET state='unknown' WHERE state='dispatching';`);}
 receipt(row){return {attemptId:row.id,threadId:row.thread||null,state:row.state,nativeOutcome:row.native_outcome||null,canAcknowledge:row.state==='unknown'&&row.binding===this.source.binding()&&this.codex.operationCapacityAvailable(),notice:({dispatching:'Waiting for the original native reload reply. Do not submit another reload.',accepted:'Native reload acknowledged. Inspect MCP status; individual connections and tools may still be unavailable.',failed:'Native reload reported an error. Some loaded conversations may already have changed. Inspect MCP status before another reload.',unknown:'Reload outcome unconfirmed. Inspect this host before dismissing this receipt; the original operation is never replayed.',acknowledged:'Uncertain reload inspected and dismissed, without cancellation or replay.'})[row.state]+(row.state==='acknowledged'&&row.native_outcome?' Original native reply later reported '+row.native_outcome+'.':'')};}
 recent(device,threadId){return this.db.prepare("SELECT * FROM native_mcp_reload WHERE binding=? OR (device=? AND thread=?) ORDER BY state IN ('unknown','dispatching') DESC,at DESC,rowid DESC LIMIT 12").all(this.source.binding(),device,threadId||'').map(row=>this.receipt(row));}
 async detail({threadId},device){try{
  if(threadId!=null&&!id(threadId))throw fail('Invalid originating conversation.',400);
  await this.codex.connect();if(!supportsNativeMethod(this.codex,'config/mcpServer/reload'))throw fail('MCP reload is unavailable on this runtime.',422);
  const snapshot=this.source.capture();this.source.check(snapshot);
  if(threadId){await this.source.context(threadId);this.source.check(snapshot);}
  const config=await this.codex.call('config/read',{includeLayers:true});this.source.check(snapshot);
  const binding=snapshot.binding,configHash=createHash('sha256').update(JSON.stringify(config)).digest('hex');
  const blocked=!!this.db.prepare("SELECT 1 FROM native_mcp_reload WHERE binding=? AND state IN ('dispatching','unknown')").get(binding);
  return {notice:MCP_RELOAD_NOTICE,threadId:threadId||null,canReload:!blocked&&this.codex.operationCapacityAvailable(),reviewToken:this.source.token({kind:'mcpReload',threadId:threadId||null,binding,epoch:snapshot.epoch,configHash}),receipts:this.recent(device,threadId),_snapshot:snapshot};
 }catch(e){if(e.mcpReload)throw e;throw fail('MCP reload inspection unavailable. No reload was submitted.',503);}}
 async mutate(input,principal){
  if(principal?.kind!=='paired-device'||!principal.deviceId||principal.deviceId==='owner')throw fail('MCP reload requires a paired human device.',403);
  const {attemptId,threadId,action}=input;if(!id(attemptId))throw fail('Invalid reload identity.',400);
  const old=this.db.prepare('SELECT * FROM native_mcp_reload WHERE id=?').get(attemptId);
  if(action==='reloadAcknowledge'){
   if(!old||old.thread!==(threadId||'')||input.confirmInspected!==true||!this.receipt(old).canAcknowledge)throw fail('Inspect the original host and its uncertain reload receipt first.');
   admissionTransaction(this.db,()=>{this.admissions?.action('mcp-reload',attemptId,'inspected-uncertain-reload',principal);this.db.prepare("UPDATE native_mcp_reload SET state='acknowledged' WHERE id=?").run(attemptId);});return this.receipt({...old,state:'acknowledged'});
  }
  if(action!=='reload'||input.confirmHostWide!==true)throw fail('Review and confirm the host-wide MCP impact.',400);
  if(old){if(old.device!==principal.deviceId||old.thread!==(threadId||''))throw fail('Reload identity belongs to another origin.');return this.receipt(old);}
  const view=await this.detail({threadId},principal.deviceId);
  if(!view.canReload||view.reviewToken!==input.reviewToken)throw fail('MCP source or configuration changed, or an earlier reload needs inspection. Review again.');
  admissionTransaction(this.db,()=>{this.source.check(view._snapshot);if(!this.codex.operationCapacityAvailable()||this.db.prepare("SELECT 1 FROM native_mcp_reload WHERE binding=? AND state IN ('dispatching','unknown')").get(view._snapshot.binding))throw fail('Awaiting an original operation receipt.');this.admissions?.record('mcp-reload',attemptId,principal);this.db.prepare("INSERT INTO native_mcp_reload VALUES(?,?,?,?,'dispatching',NULL,?)").run(attemptId,principal.deviceId,threadId||'',view._snapshot.binding,Date.now());});
  const complete=({result,error,terminal})=>{
   const outcome=terminal&&error?.rpc?'failed':terminal&&!error&&result&&typeof result==='object'&&!Array.isArray(result)&&Object.keys(result).length===0?'accepted':'unknown';
   // Exact RPC outcome belongs to the original receipt even if the UI/account moved.
   this.db.prepare("UPDATE native_mcp_reload SET state=CASE WHEN state='acknowledged' THEN state ELSE ? END,native_outcome=? WHERE id=?").run(outcome,outcome==='unknown'?null:outcome,attemptId);
  };
  try{await this.codex.callOperation('config/mcpServer/reload',undefined,complete);}catch{}
  return this.receipt(this.db.prepare('SELECT * FROM native_mcp_reload WHERE id=?').get(attemptId));
 }
}
