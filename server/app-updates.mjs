import {existsSync,readFileSync,realpathSync,statSync,openSync,readSync,closeSync} from 'node:fs';
import {resolve,relative,isAbsolute} from 'node:path';
import {createHash} from 'node:crypto';
const digest=/^[a-f0-9]{64}$/;
const fail=message=>Object.assign(Error(message),{status:400});
export class AppUpdates {
  constructor({dir,db,push,clock=()=>Date.now()}){this.dir=resolve(dir,'app-updates');this.db=db;this.push=push;this.clock=clock;db.exec(`CREATE TABLE IF NOT EXISTS app_update_announcements(version_code INTEGER PRIMARY KEY,notification_id INTEGER NOT NULL REFERENCES notifications(id));CREATE TABLE IF NOT EXISTS app_update_announced_devices(version_code INTEGER NOT NULL,device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,PRIMARY KEY(version_code,device_id));`);}
  manifest(){
    const file=resolve(this.dir,'manifest.json');if(!existsSync(file))return null;
    let m;try{m=JSON.parse(readFileSync(file,'utf8'));}catch{throw fail('Invalid app update manifest.');}
    if(m.packageName!=='co.fallsoft.pocket'||!Number.isSafeInteger(m.versionCode)||m.versionCode<1||typeof m.versionName!=='string'||m.versionName.length>100||!Array.isArray(m.variants)||!m.variants.length||m.variants.length>8)throw fail('Invalid app update manifest.');
    const signers=new Set();for(const v of m.variants){if(!digest.test(v.signerSha256)||signers.has(v.signerSha256)||!digest.test(v.sha256)||!Number.isSafeInteger(v.size)||v.size<1||v.size>200*1024*1024||typeof v.path!=='string'||!v.path||isAbsolute(v.path)||v.path.includes('\0'))throw fail('Invalid app update variant.');signers.add(v.signerSha256);}
    return m;
  }
  artifact(signer){
    if(typeof signer!=='string'||!digest.test(signer))throw fail('Invalid installed signing certificate.');
    const m=this.manifest();if(!m)return null;const v=m.variants.find(v=>v.signerSha256===signer);if(!v)return null;
    let root,path;try{root=realpathSync(this.dir);path=realpathSync(resolve(root,v.path));}catch{throw Object.assign(Error('The update artifact is unavailable.'),{status:503});}const rel=relative(root,path);
    if(rel.startsWith('..')||isAbsolute(rel)||!rel||!path.endsWith('.apk'))throw fail('Update artifact must remain inside the update directory.');
    const st=statSync(path);if(!st.isFile()||st.size!==v.size)throw fail('Update artifact size does not match its manifest.');
    const h=createHash('sha256'),buf=Buffer.alloc(1024*1024),fd=openSync(path,'r');try{let n;while((n=readSync(fd,buf,0,buf.length,null)))h.update(buf.subarray(0,n));}finally{closeSync(fd);}
    if(h.digest('hex')!==v.sha256)throw fail('Update artifact checksum does not match its manifest.');
    return {...v,versionCode:m.versionCode,versionName:m.versionName,packageName:m.packageName,file:path};
  }
  metadata(signer,current){
    if(typeof current!=='string'||!/^\d{1,10}$/.test(current)||!Number.isSafeInteger(Number(current)))throw fail('Invalid installed app version.');
    const a=this.artifact(signer);if(!a)return {available:false,reason:this.manifest()?'unsupported_signer':'not_published'};if(a.versionCode<=Number(current))return {available:false};
    const {file,path,...publicData}=a;return {available:true,...publicData,apkUrl:'/api/app/update/apk'};
  }
  async announce(){
    const m=this.manifest();if(!m)return {announced:0,available:false};
    // Do not advertise a corrupt or absent variant to any phone.
    for(const v of m.variants)this.artifact(v.signerSha256);
    if(!this.push.enabled)return {announced:0,pushEnabled:false};
    const at=this.clock();this.db.exec('BEGIN IMMEDIATE');let count=0,id;
    try{
      let row=this.db.prepare('SELECT notification_id FROM app_update_announcements WHERE version_code=?').get(m.versionCode);
      if(!row){const r=this.db.prepare("INSERT INTO notifications(thread_id,title,body,kind,attachments,created_at) VALUES(NULL,?,?,'app_update','[]',?)").run('NextComp update ready',`NextComp ${m.versionName} is ready. Tap to update.`,at);id=Number(r.lastInsertRowid);this.db.prepare('INSERT INTO app_update_announcements VALUES(?,?)').run(m.versionCode,id);}else id=row.notification_id;
      const devices=this.db.prepare('SELECT p.device_id FROM push_tokens p JOIN devices d ON d.id=p.device_id WHERE p.project_id=?').all(this.push.config.projectId);
      for(const d of devices){const r=this.db.prepare('INSERT OR IGNORE INTO app_update_announced_devices VALUES(?,?)').run(m.versionCode,d.device_id);if(!r.changes)continue;this.db.prepare("INSERT OR IGNORE INTO push_deliveries(notification_id,device_id,state,updated_at) VALUES(?,?,'queued',?)").run(id,d.device_id,at);count++;}
      this.db.exec('COMMIT');
    }catch(e){this.db.exec('ROLLBACK');throw e;}
    await this.push.flush();return {announced:count,versionCode:m.versionCode,notificationId:id,pushEnabled:true};
  }
}
export function mountAppUpdates(app,{updates,owner,route,onAnnounce=()=>{}}){
  app.get('/api/app/update',route(async(req,res)=>res.json(updates.metadata(req.query.signer,req.query.versionCode))));
  app.get('/api/app/update/apk',route(async(req,res)=>{const a=updates.artifact(req.query.signer);if(!a)return res.status(404).json({error:'No update matches this app signing identity.'});res.set('Content-Type','application/vnd.android.package-archive');res.set('Content-Disposition',`attachment; filename="nextcomp-${a.versionName.replace(/[^\w.-]/g,'_')}.apk"`);res.set('X-APK-SHA256',a.sha256);res.sendFile(a.file);}));
  app.post('/api/app/update/announce',owner,route(async(_req,res)=>{const result=await updates.announce();if(result.notificationId)onAnnounce({id:result.notificationId,kind:'app_update',title:'NextComp update ready',body:'A new NextComp build is ready. Tap to update.',created_at:Date.now()});res.json(result);}));
}
