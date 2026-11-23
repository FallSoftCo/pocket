import {readFileSync} from 'node:fs';
import {McpServer} from '@modelcontextprotocol/sdk/server/mcp.js';
import {StdioServerTransport} from '@modelcontextprotocol/sdk/server/stdio.js';
import {z} from 'zod';

const secretFile=process.env.POCKET_AUTOMATION_SECRET_FILE;
if(!secretFile)throw Error('POCKET_AUTOMATION_SECRET_FILE is required.');
const secret=JSON.parse(readFileSync(secretFile)).secret;
const base=process.env.POCKET_AUTOMATION_URL||'http://127.0.0.1:18881';
async function call(path,body){
  let response;
  try{response=await fetch(base+path,{method:body?'POST':'GET',headers:{Authorization:`Bearer ${secret}`,...(body?{'Content-Type':'application/json'}:{})},body:body?JSON.stringify(body):undefined});}
  catch{throw Error('Phone control is unavailable because NextComp’s Android Accessibility service is not connected. Open NextComp Settings, choose Control this phone, enable NextComp in Android Accessibility, and retry.');}
  if(!response.ok)throw Error((await response.text())||`Phone control failed (${response.status})`);return response;
}
const text=value=>({content:[{type:'text',text:typeof value==='string'?value:JSON.stringify(value)}]});
const server=new McpServer({name:'pocket-phone',version:'0.1.0'},{instructions:'Control the owner’s Android phone only when the user asks. Inspect the current screen before acting. Do not post, send messages, purchase, delete, change account/security settings, or confirm irreversible actions without a direct instruction for that exact action.'});
server.tool('phone_screen','Read the current Android accessibility tree. Password text is always omitted.',{},async()=>text(await (await call('/snapshot')).json()));
server.tool('phone_screenshot','Capture the current Android screen for visual inspection.',{},async()=>{const response=await call('/screenshot');return {content:[{type:'image',mimeType:'image/png',data:Buffer.from(await response.arrayBuffer()).toString('base64')}]};});
server.tool('phone_tap','Tap screen coordinates after inspecting the current screen.',{x:z.number().int().nonnegative(),y:z.number().int().nonnegative()},async({x,y})=>text(await (await call('/action',{type:'tap',x,y})).json()));
server.tool('phone_click','Click a node by the path returned from phone_screen.',{path:z.string().regex(/^\d+(\.\d+)*$/)},async({path})=>text(await (await call('/action',{type:'click',path})).json()));
server.tool('phone_scroll','Scroll the foreground app.',{direction:z.enum(['up','down','left','right'])},async({direction})=>text(await (await call('/action',{type:'scroll',direction})).json()));
server.tool('phone_type','Replace text in the focused editable field. Password fields are refused.',{text:z.string().max(8000)},async({text:input})=>text(await (await call('/action',{type:'text',text:input})).json()));
server.tool('phone_key','Use an Android navigation control.',{key:z.enum(['back','home','recents'])},async({key})=>text(await (await call('/action',{type:'key',key})).json()));
await server.connect(new StdioServerTransport());
