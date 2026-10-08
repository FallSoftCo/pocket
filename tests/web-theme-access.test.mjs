import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import http from 'node:http';
import {once} from 'node:events';
import WebSocket,{WebSocketServer} from 'ws';
import {createWebClient} from '../server/web-client.mjs';
import {clientBase,clientURL} from '../web/paths.mjs';
import {acceptProfile} from '../web/migration.mjs';

const script=readFileSync(new URL('../web/theme.js',import.meta.url),'utf8');
function themeFixture({dark=false,saved=null,unavailable=false,mounted=false}={}){
 const data=new Map(saved?[['nextcomp.web.theme',JSON.stringify(saved)]]:[]),listeners={},selectors=[{value:null,addEventListener(type,fn){this.change=fn;}},{value:null,addEventListener(type,fn){this.change=fn;}}];
 const location={pathname:mounted?'/nextcomp':'/',search:'?view=work',hash:'#latest'},history={state:null,replaceState(_state,_title,url){this.url=url;}};
 const media={matches:dark,addEventListener(type,fn){this.change=fn;}},meta={setAttribute(k,v){this[k]=v;}},document={currentScript:mounted?{src:'https://private.example.test/nextcomp/theme.js'}:null,documentElement:{dataset:{},style:{}},querySelector(){return meta;},querySelectorAll(){return selectors;},addEventListener(type,fn){listeners[type]=fn;}};
 const storage={getItem:k=>{if(unavailable)throw Error('Unavailable');return data.get(k)??null;},setItem(k,v){if(unavailable)throw Error('Unavailable');data.set(k,v);}};
 const window={matchMedia:()=>media,addEventListener(type,fn){listeners[type]=fn;}};vm.runInNewContext(script,{window,document,localStorage:storage,location,history,URL});listeners.DOMContentLoaded();return {window,document,media,meta,data,selectors,listeners,history};
}
test('theme follows live system, respects explicit choices, persists and synchronizes controls',()=>{
 const f=themeFixture({dark:true,mounted:true});assert.equal(f.history.url,'/nextcomp/?view=work#latest');assert.equal(f.document.documentElement.dataset.theme,'dark');assert.equal(f.meta.content,'#11191b');assert.equal(f.selectors[0].value,'system');
 f.media.matches=false;f.media.change();assert.equal(f.document.documentElement.dataset.theme,'light');f.selectors[0].value='dark';f.selectors[0].change();assert.equal(f.selectors[1].value,'dark');assert.equal(f.data.get('nextcomp.web.theme'),'"dark"');
 f.media.change();assert.equal(f.document.documentElement.dataset.theme,'dark');const restored=themeFixture({saved:JSON.parse(f.data.get('nextcomp.web.theme'))});assert.equal(restored.document.documentElement.dataset.theme,'dark');
 restored.window.NextCompTheme.select('system');assert.equal(restored.document.documentElement.dataset.theme,'light');restored.media.matches=true;restored.media.change();assert.equal(restored.document.documentElement.dataset.theme,'dark');
 restored.listeners.storage({key:'nextcomp.web.theme',newValue:'"light"'});assert.equal(restored.selectors[0].value,'light');assert.equal(restored.document.documentElement.dataset.theme,'light');assert.equal(themeFixture({dark:true,unavailable:true}).document.documentElement.dataset.theme,'dark');
});

test('dark text, muted text, controls and attention maintain readable contrast',()=>{
 const css=readFileSync(new URL('../web/style.css',import.meta.url),'utf8'),light=css.match(/:root\{([^}]+)\}/)[1],dark=css.match(/:root\[data-theme="dark"\]\{([^}]+)\}/)[1];
 const tokens=text=>Object.fromEntries([...text.matchAll(/--([a-z-]+):(#\w+)/g)].map(m=>[m[1],m[2]]));
 const luminance=hex=>{if(hex.length===4)hex='#'+[...hex.slice(1)].map(c=>c+c).join('');const values=[1,3,5].map(i=>parseInt(hex.slice(i,i+2),16)/255).map(x=>x<=.04045?x/12.92:((x+.055)/1.055)**2.4);return .2126*values[0]+.7152*values[1]+.0722*values[2];};
 for(const text of [light,dark]){const t=tokens(text);for(const [foreground,background] of [['ink','paper'],['ink','surface'],['muted','sidebar'],['muted','field'],['ink','user'],['ink','attention'],['on-action','action'],['error-ink','error-bg'],['accent','surface']]){const values=[luminance(t[foreground]),luminance(t[background])].sort((a,b)=>a-b);assert.ok((values[1]+.05)/(values[0]+.05)>=4.5,`${foreground}/${background} contrast`);}}
});

test('path URLs keep API, attachments, WebSockets and worker on the app mount',()=>{
 const base=clientBase('https://private.example.test/nextcomp/app.mjs');assert.equal(clientURL(base,'/api/status'),'https://private.example.test/nextcomp/api/status');assert.equal(clientURL(base,'events').replace(/^http/,'ws'),'wss://private.example.test/nextcomp/events');assert.equal(clientURL(base,'sw.js'),'https://private.example.test/nextcomp/sw.js');assert.equal(clientURL(base,'/api/files/file-123'),'https://private.example.test/nextcomp/api/files/file-123');
});

test('profile migration requires matching device identity, copies only missing app state and never unrelated storage',()=>{
 const saved=new Map([['nextcomp.web.v1.draft.device.thread','New draft']]),storage={getItem:key=>saved.get(key)??null,setItem:(key,value)=>saved.set(key,value)};
 const entries={'nextcomp.web.v1.deviceId':'"device"','nextcomp.web.v1.draft.device.thread':'"Old draft"','nextcomp.web.v1.draft.device.other':'"Other draft"','nextcomp.web.theme':'"dark"','unrelated-token':'secret'};
 assert.equal(acceptProfile({entries},'another-device',storage),false);assert.equal(saved.size,1);assert.equal(acceptProfile({entries},'device',storage),true);assert.equal(saved.get('nextcomp.web.v1.draft.device.thread'),'New draft');assert.equal(saved.get('nextcomp.web.v1.draft.device.other'),'"Other draft"');assert.equal(saved.get('nextcomp.web.theme'),'"dark"');assert.equal(saved.has('unrelated-token'),false);
});

test('443 path gateway preserves existing signed pairing and authenticates writes/WebSockets against exact new origin',async t=>{
 const token='f'.repeat(64),key=Buffer.alloc(32,7),oldOrigin='https://private.example.test:8448',origin='https://private.example.test';let writes=0;
 const backend=http.createServer((req,res)=>{res.setHeader('Content-Type','application/json');if(req.url==='/api/pair')return res.end(JSON.stringify({token,id:'browser'}));if(req.headers.authorization!==`Bearer ${token}`){res.statusCode=401;return res.end('{}');}if(req.method==='POST')writes++;res.end(JSON.stringify({deviceId:'browser',ok:true}));});const wss=new WebSocketServer({server:backend});wss.on('connection',ws=>ws.on('message',message=>ws.send(message)));backend.listen(0,'127.0.0.1');await once(backend,'listening');t.after(()=>{wss.close();backend.close();});const url=`http://127.0.0.1:${backend.address().port}`;
 const old=createWebClient({backend:url,origin:oldOrigin,key,migrationTargetOrigin:origin}),clean=createWebClient({backend:url,origin,key,basePath:'/nextcomp/',migrationSourceOrigin:oldOrigin});old.server.listen(0,'127.0.0.1');await once(old.server,'listening');t.after(()=>old.server.close());
 const mount=http.createServer((req,res)=>{if(!req.url.startsWith('/nextcomp/')){res.statusCode=404;return res.end();}req.url=req.url.replace(/^\/nextcomp/,'');clean.server.emit('request',req,res);});mount.on('upgrade',(req,socket,head)=>{req.url=req.url.replace(/^\/nextcomp/,'');clean.server.emit('upgrade',req,socket,head);});mount.listen(0,'127.0.0.1');await once(mount,'listening');t.after(()=>mount.close());const base=`http://127.0.0.1:${mount.address().port}/nextcomp`,oldBase=`http://127.0.0.1:${old.server.address().port}`;
 const pair=await fetch(oldBase+'/web/pair',{method:'POST',headers:{Origin:oldOrigin,'Content-Type':'application/json'},body:'{}'}),cookie=pair.headers.get('set-cookie').split(';')[0];assert.equal((await fetch(base+'/api/status',{headers:{Cookie:cookie}})).status,200);
 const html=await (await fetch(base+'/')).text();assert.match(html,/src="\/nextcomp\/theme.js"/);assert.match(html,/src="\/nextcomp\/app.mjs"/);assert.ok(html.indexOf('theme.js')<html.indexOf('style.css'));
 const manifest=await (await fetch(base+'/manifest.webmanifest')).json();assert.equal(manifest.scope,'/nextcomp/');assert.equal(manifest.start_url,'/nextcomp/');assert.equal(manifest.id,'/nextcomp/');assert.equal(manifest.icons[0].src,'/nextcomp/icon-192.png');assert.equal((await fetch(base+'/style.css')).status,200);
 const headers={Cookie:cookie,Origin:oldOrigin,'Content-Type':'application/json'};assert.equal((await fetch(base+'/api/voice/text',{method:'POST',headers,body:'{}'})).status,403);assert.equal(writes,0);assert.equal((await fetch(base+'/api/voice/text',{method:'POST',headers:{...headers,Origin:origin},body:'{}'})).status,200);assert.equal(writes,1);
 const ws=new WebSocket(base.replace('http:','ws:')+'/events',{headers:{Cookie:cookie,Origin:origin}});await once(ws,'open');ws.send('mounted event');assert.equal((await once(ws,'message'))[0].toString(),'mounted event');ws.close();await once(ws,'close');
 const frame=await fetch(oldBase+'/migration.html');assert.match(frame.headers.get('content-security-policy'),/frame-ancestors https:\/\/private.example.test;/);assert.match((await fetch(base+'/')).headers.get('content-security-policy'),/frame-src https:\/\/private.example.test:8448/);assert.equal((await fetch(base+'/migration.html')).status,404);
 assert.throws(()=>createWebClient({backend:url,origin,basePath:'/../'}),/WEB_BASE_PATH/);assert.throws(()=>createWebClient({backend:url,origin,migrationSourceOrigin:'https://evil.example.test'}),/same private host/);
});
