import {createHash} from 'node:crypto';
export const digest=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
const fail=message=>Object.assign(Error(message),{status:409});
export function ensureRoomReconciliations(db){db.exec(`CREATE TABLE IF NOT EXISTS coordinator_room_reconciliations(
 device TEXT NOT NULL,source TEXT NOT NULL,binding TEXT NOT NULL,operation TEXT NOT NULL,
 remote_id TEXT NOT NULL UNIQUE,metadata_digest TEXT NOT NULL,remote_digest TEXT NOT NULL,
 proof_digest TEXT NOT NULL,decision TEXT NOT NULL,evidence TEXT NOT NULL,
 PRIMARY KEY(device,source,binding,operation));`);}
function rows(db,scope){
 const metadata=db.prepare('SELECT * FROM coordinator_room_commands WHERE device=? AND source=? AND binding=? AND operation=?').get(scope.device,scope.source,scope.binding,scope.operation);
 const remote=db.prepare('SELECT * FROM coordinator_remote_commands WHERE id=?').get(scope.remoteId);
 if(!metadata||!remote)throw fail('Exact original creation/transport rows are required.');
 return {metadata,remote};
}
export function noAdmission(db,{device,source,binding,operation,remoteId}){
 const row=remoteId?db.prepare('SELECT * FROM coordinator_room_reconciliations WHERE remote_id=?').get(remoteId):db.prepare('SELECT * FROM coordinator_room_reconciliations WHERE device=? AND source=? AND binding=? AND operation=?').get(device,source,binding,operation);
 if(!row)return null;
 let original;try{original=rows(db,{device:row.device,source:row.source,binding:row.binding,operation:row.operation,remoteId:row.remote_id});}catch{return null;}
 if(row.decision!=='no_admission'||digest(original.metadata)!==row.metadata_digest||digest(original.remote)!==row.remote_digest)return null;
 return {admission:'no_admission',operationId:row.operation,proofSha256:row.proof_digest,source:'owner-reviewed-original-gateway-rejection',originalRowsPreserved:true};
}
/** Local owner-only repair preparation; never an HTTP/coordinator tool. A later
 *403/404 read is NOT admissible evidence of non-admission. */
export function prepareNoAdmission(db,scope,proof,proofSha256){
 const {metadata,remote}=rows(db,scope),body=JSON.parse(metadata.body);
 if(proof.state!=='definitively-rejected-before-canonical-admission'||proof.operationId!==scope.operation||proof.requestId!==body.id||
  proof.journalMessage!=='POST /api/hollywood/v3/rooms HOLLYWOOD_REQUEST_REJECTED Unknown Hollywood human operation.'||
  !/^[0-9a-f]{64}$/.test(proof.gatewayModuleSha256)||!/^\d+$/.test(proof.journalTimestamp)||!proof.journalInvocationId)
  throw fail('Positive exact original pre-admission validation evidence is required; inaccessible reads do not qualify.');
 if(metadata.state!=='submitting'||!['sending','unknown'].includes(remote.state)||remote.device_id!==scope.device||remote.source_id!==scope.source||remote.path!=='/api/hollywood/v3/rooms'||remote.request_id!==body.id||body.operationId!==scope.operation||digest(body)!==remote.body_hash)
  throw fail('Original creation and transport identities/payloads do not match.');
 return {schema:'coordinator-room-no-admission-plan/v1',scope,proofSha256,metadataDigest:digest(metadata),remoteDigest:digest(remote),
  decision:'no_admission',evidence:{code:'HOLLYWOOD_REQUEST_REJECTED',error:'Unknown Hollywood human operation.',
   status:404,statusEvidence:'Original owner-verified loaded validation path; original HTTP bytes may be unavailable',
   gatewayModuleSha256:proof.gatewayModuleSha256,journalTimestamp:proof.journalTimestamp,journalInvocationId:proof.journalInvocationId},
  originalRowsMustRemainUnchanged:true,noCommandDispatch:true};
}
export function applyNoAdmission(db,plan,proof,proofSha256){
 db.exec('BEGIN IMMEDIATE');
 try{
  const current=prepareNoAdmission(db,plan.scope,proof,proofSha256);
  if(plan.schema!==current.schema||plan.decision!=='no_admission'||plan.proofSha256!==proofSha256||digest(current)!==digest(plan))throw fail('Reviewed plan/proof/original-row CAS changed.');
  ensureRoomReconciliations(db);
  const s=plan.scope,old=db.prepare('SELECT * FROM coordinator_room_reconciliations WHERE device=? AND source=? AND binding=? AND operation=?').get(s.device,s.source,s.binding,s.operation);
  if(old){if(old.proof_digest!==proofSha256||old.metadata_digest!==plan.metadataDigest||old.remote_digest!==plan.remoteDigest)throw fail('Original reconciliation already differs.');}
  else db.prepare('INSERT INTO coordinator_room_reconciliations VALUES(?,?,?,?,?,?,?,?,?,?)').run(s.device,s.source,s.binding,s.operation,s.remoteId,plan.metadataDigest,plan.remoteDigest,proofSha256,'no_admission',JSON.stringify(plan.evidence));
  db.exec('COMMIT');return {applied:!old,...noAdmission(db,s)};
 }catch(error){db.exec('ROLLBACK');throw error;}
}
