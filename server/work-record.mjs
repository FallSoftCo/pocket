import {itemRow} from './timeline.mjs';

const clip=value=>String(value||'').slice(0,16000);
const timestamp=value=>value ? (Number(value)<1e12?Number(value)*1000:Number(value)) : null;

/** A local, derived record. Completion is a protocol state, never a success rating. */
export class WorkRecord {
  constructor(db,{version,clock=Date.now}={}) {
    Object.assign(this,{db,version,clock});
    db.exec(`CREATE TABLE IF NOT EXISTS work_record_sessions (
      id TEXT PRIMARY KEY, name TEXT, cwd TEXT, first_seen INTEGER, version TEXT,
      cursor TEXT, hydrated INTEGER DEFAULT 0, last_scan INTEGER DEFAULT 0);
      CREATE TABLE IF NOT EXISTS work_record_turns (
      session TEXT, id TEXT, status TEXT, source_at INTEGER, observed_at INTEGER,
      PRIMARY KEY(session,id));
      CREATE TABLE IF NOT EXISTS work_record_items (
      session TEXT, turn TEXT, id TEXT, kind TEXT, value TEXT,
      PRIMARY KEY(session,turn,id));
      CREATE TABLE IF NOT EXISTS work_record_team_messages (
      device TEXT, environment TEXT, team TEXT, id TEXT, value TEXT,
      observed_at INTEGER, PRIMARY KEY(device,environment,team,id));`);
  }
  enroll(thread) {
    if(!thread?.id)return;
    this.db.prepare(`INSERT INTO work_record_sessions(id,name,cwd,first_seen,version) VALUES(?,?,?,?,?)
      ON CONFLICT(id) DO UPDATE SET name=COALESCE(excluded.name,name),cwd=COALESCE(excluded.cwd,cwd)`)
      .run(thread.id,thread.name||null,thread.cwd||null,this.clock(),this.version||'unknown');
  }
  has(id){return typeof id==='string'&&!!this.db.prepare('SELECT 1 FROM work_record_sessions WHERE id=?').get(id);}
  turn(session,turn,{historical=false}={}) {
    if(!this.has(session)||!turn?.id)return;
    this.db.prepare(`INSERT INTO work_record_turns VALUES(?,?,?,?,?) ON CONFLICT(session,id) DO UPDATE SET
      status=CASE WHEN ? AND work_record_turns.status IN ('completed','failed','interrupted') THEN work_record_turns.status ELSE COALESCE(excluded.status,status) END,
      source_at=COALESCE(excluded.source_at,source_at)`)
      .run(session,turn.id,turn.status||null,timestamp(turn.startedAt),this.clock(),historical?1:0);
    for(const item of turn.items||[])this.item(session,turn.id,item);
  }
  item(session,turn,item) {
    if(!this.has(session)||!turn||!item?.id)return;
    const row=itemRow(item,turn);if(!row)return;
    let value,kind;
    if(item.type==='userMessage'){kind='request';value={text:clip(row.text),truncated:!!row.truncated||row.text.length>16000};}
    else if(item.type==='agentMessage'){kind='output';value={text:clip(row.text),truncated:!!row.truncated||row.text.length>16000,phase:row.phase||null,evidence:'agent-reported'};}
    else if(item.type==='fileChange'){kind='files';value={paths:row.text.split('\n').filter(Boolean).slice(0,100),status:item.status||row.status,evidence:'tool-observed'};}
    else if(item.type==='commandExecution'){kind='check';value={exitCode:row.exitCode??null,evidence:'tool-observed'};}
    else if(item.type==='imageGeneration'&&item.savedPath){kind='artifact';value={path:clip(item.savedPath),evidence:'tool-observed'};}
    else return; // No reasoning, command output, diffs, or arbitrary tool payloads.
    this.db.prepare('INSERT OR IGNORE INTO work_record_turns VALUES(?,?,NULL,NULL,?)').run(session,turn,this.clock());
    this.db.prepare('INSERT INTO work_record_items VALUES(?,?,?,?,?) ON CONFLICT(session,turn,id) DO UPDATE SET kind=excluded.kind,value=excluded.value')
      .run(session,turn,item.id,kind,JSON.stringify(value));
  }
  remember(thread) {
    this.enroll(thread);
    for(const turn of thread.turns||[])this.turn(thread.id,turn,{historical:true});
  }
  observe(m) {
    const p=m.params||{},id=p.threadId||p.thread?.id;
    if(!this.has(id))return;
    if(m.method==='turn/started'||m.method==='turn/completed')this.turn(id,p.turn);
    if(m.method==='item/completed'||p.item?.type==='userMessage')this.item(id,p.turnId,p.item);
  }
  team(device,environment,command,value) {
    const parts=command.path.split('/');
    if(parts[0]!=='teams'||!parts[1]||!['history','messages'].includes(parts[2]))return;
    // Copy only messages actually returned through this device's authorized proxy.
    const messages=Array.isArray(value.data)?value.data:[];
    for(const message of messages){
      if(message.id==null||typeof message.body!=='string')continue;
      const record={id:String(message.id),team:parts[1],task:message.task_id||message.task||command.query?.task||null,author:message.author||null,kind:message.kind||null,body:clip(message.body),visibility:message.visibility||command.query?.visibility||null,replyTo:message.replyTo||message.reply?.id||null,createdAt:message.createdAt||message.created_at||null,source:{backend:'losangelex',environment,team:parts[1],messageId:String(message.id)},evidence:'message-observed'};
      this.db.prepare('INSERT INTO work_record_team_messages VALUES(?,?,?,?,?,?) ON CONFLICT(device,environment,team,id) DO UPDATE SET value=excluded.value')
        .run(device,environment||'unknown',parts[1],String(message.id),JSON.stringify(record),this.clock());
    }
  }
  teamList(device){return this.db.prepare('SELECT value FROM work_record_team_messages WHERE device=? ORDER BY observed_at DESC,rowid DESC LIMIT 30').all(device).map(row=>JSON.parse(row.value));}
  async scan(history,metadata) {
    // One bounded page per tick. No resume, turn creation, or changes to agent work.
    const session=this.db.prepare('SELECT * FROM work_record_sessions ORDER BY last_scan,id LIMIT 1').get();
    if(!session)return;
    this.db.prepare('UPDATE work_record_sessions SET last_scan=? WHERE id=?').run(this.clock(),session.id);
    const thread=await history.read({...metadata(session.id),id:session.id},{before:session.cursor||null,preferPaging:true,maxTurns:4,maxItems:80,maxBytes:160000});
    this.remember(thread);
    this.db.prepare('UPDATE work_record_sessions SET cursor=?,hydrated=? WHERE id=?')
      .run(thread._pocketPage?.hasEarlier?thread._pocketPage.before:null,thread._pocketPage?.hasEarlier?0:1,session.id);
  }
  list({before=null,limit=30}={}) {
    let edge=[Number.MAX_SAFE_INTEGER,Number.MAX_SAFE_INTEGER];
    if(before){try{edge=JSON.parse(Buffer.from(String(before),"base64url").toString());if(!Array.isArray(edge)||edge.length!==2||!edge.every(Number.isSafeInteger))throw Error();}catch{throw Object.assign(Error("Invalid work-record cursor"),{status:400});}}
    limit=Math.min(50,Math.max(1,Math.trunc(Number(limit))||30));
    const rows=this.db.prepare(`SELECT t.rowid AS cursor,t.*,s.name,s.cwd,s.version,s.first_seen,s.hydrated
      FROM work_record_turns t JOIN work_record_sessions s ON s.id=t.session WHERE (COALESCE(t.source_at,t.observed_at),t.rowid)<(?,?)
      ORDER BY COALESCE(t.source_at,t.observed_at) DESC,t.rowid DESC LIMIT ?`).all(...edge,limit+1);
    const entries=rows.slice(0,limit).map(row=>{
      const items=this.db.prepare('SELECT id,kind,value FROM work_record_items WHERE session=? AND turn=? ORDER BY rowid').all(row.session,row.id).map(i=>({id:i.id,kind:i.kind,...JSON.parse(i.value)}));
      return {...row,items,source:{backend:'codex',threadId:row.session,turnId:row.id},continuation:!!this.db.prepare('SELECT 1 FROM work_record_turns WHERE session=? AND (COALESCE(source_at,observed_at),rowid)<(?,?) LIMIT 1').get(row.session,row.source_at||row.observed_at,row.cursor)};
    });
    return {entries,before:rows.length>limit?Buffer.from(JSON.stringify([entries.at(-1).source_at||entries.at(-1).observed_at,entries.at(-1).cursor])).toString("base64url"):null,coverage:{sessions:this.db.prepare('SELECT COUNT(*) n FROM work_record_sessions').get().n,backfilling:this.db.prepare('SELECT COUNT(*) n FROM work_record_sessions WHERE hydrated=0').get().n},limits:'Observed requests, responses and tool results. A completed turn is not independently verified delivery. Follow-ups are linked by conversation, not classified as satisfaction or success.'};
  }
}
