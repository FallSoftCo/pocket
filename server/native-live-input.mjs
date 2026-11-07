const fail=(message,status=409)=>Object.assign(Error(message),{status});
const identity=id=>typeof id==='string'&&/^[a-zA-Z0-9_-]{8,100}$/.test(id);
/** Audio evidence only. A finalized transcript cannot admit or execute a task.
 * The existing immutable WAV admission verifies the full file before consuming it. */
export class NativeLiveInput {
 constructor({db,voice,clock=Date.now}) {
  Object.assign(this,{db,voice,clock});
  db.exec(`CREATE TABLE IF NOT EXISTS native_live_inputs(device TEXT NOT NULL,id TEXT NOT NULL,connection TEXT NOT NULL,state TEXT NOT NULL,audio_sha256 TEXT,bytes INTEGER,text TEXT,proof TEXT,created_at INTEGER NOT NULL,PRIMARY KEY(device,id))`);
  db.prepare("UPDATE native_live_inputs SET state='interrupted' WHERE state='capturing'").run();
 }
 begin(device,id,connection) {
  if(!identity(id)||!identity(connection))throw fail('Invalid live transcription identity.',400);
  const previous=this.db.prepare('SELECT * FROM native_live_inputs WHERE device=? AND id=?').get(device,id);
  if(previous){if(previous.connection!==connection||previous.state!=='capturing')throw fail('The original live input cannot be replaced.');return {ok:true};}
  this.voice.listen(device,connection,id);
  this.db.prepare("INSERT INTO native_live_inputs(device,id,connection,state,created_at) VALUES(?,?,?,'capturing',?)").run(device,id,connection,this.clock());
  return {ok:true};
 }
 async finalize(device,{id,connectionId,audioSha256,bytes,finalization}) {
  if(!/^[a-f0-9]{64}$/.test(audioSha256||'')||!Number.isSafeInteger(bytes)||bytes<3244||bytes>19200044)throw fail('Invalid sealed recording identity.',400);
  const row=this.db.prepare('SELECT * FROM native_live_inputs WHERE device=? AND id=?').get(device,id);
  if(!row||row.connection!==connectionId)throw fail('Live input belongs to another recording or device.',404);
  if(row.state==='finalized'){if(row.audio_sha256!==audioSha256||row.bytes!==bytes)throw fail('The original sealed recording changed.');return {ok:true,id};}
  if(row.state!=='capturing')throw fail('This live input no longer accepts finalization.');
  const result=await this.voice.finalTranscript(device,connectionId,{...finalization,connectionId},id);
  const proof={version:1,boundary:'session.closed',confirmed:true,audioSha256,reason:finalization.reason,segments:result.segments,liveCapture:true};
  const changed=this.db.prepare("UPDATE native_live_inputs SET state='finalized',audio_sha256=?,bytes=?,text=?,proof=? WHERE device=? AND id=? AND state='capturing'").run(audioSha256,bytes,result.text,JSON.stringify(proof),device,id);
  if(!changed.changes)throw fail('Live transcription was cancelled before finalization.');
  return {ok:true,id};
 }
 cached({device,id,sha256}) {
  const row=this.db.prepare("SELECT * FROM native_live_inputs WHERE device=? AND id=? AND state='finalized'").get(device,id);
  if(!row||row.audio_sha256!==sha256)return null;
  return {text:row.text,proof:JSON.parse(row.proof)};
 }
 cancel(device,id) {this.db.prepare("UPDATE native_live_inputs SET state='cancelled' WHERE device=? AND id=? AND state='capturing'").run(device,id);}
}

