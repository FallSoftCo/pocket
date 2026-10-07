import {speechText,spokenText} from './speech.mjs';
import {existsSync,readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {initializeApp,cert,applicationDefault} from 'firebase-admin/app';
import {getMessaging} from 'firebase-admin/messaging';

export function pushData(n) {
  // FCM data has a 4096-byte limit. Full content and files stay on the host.
  const clip=(text,bytes)=>{let out='';for(const char of String(text||'')){if(Buffer.byteLength(out+char)>bytes)break;out+=char;}return out;};
  const report=n.kind==='coordinator_report';
  const full=report?'':n.spoken_text||spokenText(n.title,n.body,n.spoken_summary);
  const spoken=report?{}:Buffer.byteLength(full)<=600?{spoken_text:full}:{speech_pending:'1'};
  // Reports are a versioned, visual-only envelope. Older clients ignore the
  // missing ordinary id rather than enqueueing a paid spoken work update.
  const identity=report?{coordinator_report:'1',report_id:String(n.id)}:{id:String(n.id)};
  const data={...spoken,...identity,thread_id:n.thread_id||'',title:clip(n.title,500),body:clip(n.body,2000),...(report?{}:{spoken_summary:speechText(n.spoken_summary)}),kind:n.kind||'update',created_at:String(n.created_at),title_revision:String(n.title_revision||0)};
  // Leave room for the device ID and envelope, including JSON-escaped characters.
  while(Buffer.byteLength(JSON.stringify(data))>3700&&data.body)data.body=clip(data.body,Math.floor(Buffer.byteLength(data.body)/2));
  return data;
}
export class PushDelivery {
  constructor(db,{config=null,send=null,clock=()=>Date.now()}={}){this.db=db;this.config=config;this.send=send;this.clock=clock;this.busy=false;this.hasTitles=!!db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='notification_thread_titles'").get();}
  get enabled(){return !!this.config&&!!this.send;}
  enqueue(notificationId,{flush=true}={}){
    if(!this.enabled)return;
    this.db.prepare("INSERT OR IGNORE INTO push_deliveries(notification_id,device_id,state,updated_at) SELECT ?,device_id,'queued',? FROM push_tokens WHERE project_id=?").run(notificationId,this.clock(),this.config.projectId);
    if(flush)void this.flush().catch(e=>console.error('Push retry',e.message));
  }
  async renameThread({threadId,name,notificationIds,revision=0}){
    if(!this.enabled)return;
    const tokens=this.db.prepare('SELECT device_id,token FROM push_tokens WHERE project_id=?').all(this.config.projectId);
    for(const row of tokens){
      if(!this.db.prepare('SELECT 1 FROM push_tokens WHERE device_id=? AND token=?').get(row.device_id,row.token))continue;
      // A data-only label refresh is ignored by older clients and never becomes another chat alert.
      await this.send({token:row.token,data:{thread_renamed:'1',thread_id:threadId,thread_title:name,title_revision:String(revision),notification_ids:JSON.stringify(notificationIds.slice(-128)),device_id:row.device_id},android:{priority:'high',ttl:86400000,restrictedPackageName:'co.fallsoft.pocket'}});
    }
  }
  async flush(){
    if(!this.enabled||this.busy)return;this.busy=true;
    try{
      const rows=this.db.prepare("SELECT p.*,t.token,n.thread_id,n.title,n.body,n.kind,n.created_at,n.spoken_summary,n.spoken_text FROM push_deliveries p JOIN push_tokens t ON t.device_id=p.device_id JOIN notifications n ON n.id=p.notification_id WHERE p.state IN ('queued','retry') AND p.next_attempt_at<=? AND t.project_id=? ORDER BY p.notification_id LIMIT 100").all(this.clock(),this.config.projectId);
      for(const row of rows){
        // Recheck revocation immediately before handing the token to Firebase.
        if(!this.db.prepare('SELECT 1 FROM push_tokens WHERE device_id=? AND token=?').get(row.device_id,row.token))continue;
        const at=this.clock(),attempts=row.attempts+1;
        if(at-row.created_at>86400000){this.db.prepare("UPDATE push_deliveries SET state='expired',updated_at=? WHERE notification_id=? AND device_id=?").run(at,row.notification_id,row.device_id);continue;}
        try{
          const canonical=this.hasTitles?this.db.prepare('SELECT title,revision FROM notification_thread_titles WHERE thread_id=?').get(row.thread_id):null;
          const messageId=await this.send({token:row.token,data:{...pushData({...row,title:canonical?.title||row.title,title_revision:canonical?.revision||0,id:row.notification_id}),device_id:row.device_id},android:{priority:'high',ttl:86400000,restrictedPackageName:'co.fallsoft.pocket'}});
          this.db.prepare("UPDATE push_deliveries SET state='accepted_by_fcm',attempts=?,message_id=?,error=NULL,updated_at=? WHERE notification_id=? AND device_id=?").run(attempts,messageId,this.clock(),row.notification_id,row.device_id);
        }catch(e){
          const code=e.code||'push/send-failed';
          const invalid=['messaging/registration-token-not-registered','messaging/invalid-registration-token','messaging/mismatched-credential'].includes(code);
          const permanent=invalid||['messaging/invalid-argument','messaging/authentication-error','messaging/invalid-package-name'].includes(code);
          const state=permanent||attempts>=10?'failed':'retry';
          this.db.prepare('UPDATE push_deliveries SET state=?,attempts=?,next_attempt_at=?,error=?,updated_at=? WHERE notification_id=? AND device_id=?').run(state,attempts,at+Math.min(3600000,30000*2**(attempts-1)),code,at,row.notification_id,row.device_id);
          if(invalid)this.db.prepare('DELETE FROM push_tokens WHERE device_id=? AND token=?').run(row.device_id,row.token);
          console.error('Firebase delivery',row.notification_id,state,code);
        }
      }
    }finally{this.busy=false;}
  }
}
export function loadPush(db,dir){
  const configFile=resolve(dir,'firebase-client.json');
  if(!existsSync(configFile))return new PushDelivery(db);
  const config=JSON.parse(readFileSync(configFile));
  for(const k of ['projectId','senderId','applicationId','apiKey'])if(typeof config[k]!=='string'||!config[k])throw Error(`Firebase client configuration is missing ${k}`);
  const key=process.env.GOOGLE_APPLICATION_CREDENTIALS||resolve(dir,'firebase-admin.json');
  const app=initializeApp({projectId:config.projectId,credential:existsSync(key)?cert(JSON.parse(readFileSync(key))):applicationDefault()},'pocket');
  return new PushDelivery(db,{config,send:message=>getMessaging(app).send(message)});
}
