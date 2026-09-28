import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const token=()=>process.env.POCKET_TOKEN||JSON.parse(readFileSync(resolve(process.env.POCKET_DATA||resolve(root,'data'),'secrets.json'))).adminToken;
const server=new McpServer({name:'pocket',version:'0.4.4-alpha.4'},{instructions:'Notify the user about your own work only when they request it or a requested condition is met. Use the original current Codex thread ID (CODEX_THREAD_ID in your shell environment). Do not guess IDs. Messages reach only the paired owner. Include useful results and explicitly requested local attachments. Phone replies return as normal user messages to the same task.'});
server.tool('notify_user','Send the paired user a phone notification about this Codex task. Call when the notification condition requested by the user is met. The message opens the original thread and supports replies.',{
  thread_id:z.string().describe('Current Codex thread UUID, available as CODEX_THREAD_ID in the shell environment.'),
  title:z.string().max(180).describe('Specific outcome or action needed; avoid generic reply instructions.'),message:z.string().max(32000).describe('Useful task context and, if input is needed, the decision the user needs to make. Reply is already a native action.'),kind:z.enum(['update','complete','question','error']).default('update').describe('question and error create attention items with bounded reminders. Use update or complete for information that needs no user action.'),
  spoken_summary:z.string().max(32000).optional().describe('Optional natural spoken version of this update. Use complete thoughts; Pocket plays it to the end without a word cutoff. No code, secrets, paths or URLs. If omitted, Pocket reads the notification text.'),
  files:z.array(z.string()).max(5).optional().describe('Explicit local files to share with the owner, up to 12 MB each. Do not include credentials or unrelated files.')
},{readOnlyHint:false,destructiveHint:false,openWorldHint:false},async args=>{
  try{const r=await fetch(`${process.env.POCKET_URL||'http://127.0.0.1:18880'}/api/notify`,{method:'POST',headers:{Authorization:`Bearer ${token()}`,'Content-Type':'application/json'},body:JSON.stringify(args),signal:AbortSignal.timeout(30000)});const d=await r.json();if(!r.ok)throw new Error(d.error);return {content:[{type:'text',text:JSON.stringify({notification_id:d.notification.id,status:'accepted_by_pocket',thread_id:args.thread_id})}]};}
  catch(e){return {isError:true,content:[{type:'text',text:e.message}]};}
});
await server.connect(new StdioServerTransport());
