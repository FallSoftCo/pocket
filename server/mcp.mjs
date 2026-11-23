import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
import { readFileSync,existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const token=()=>process.env.POCKET_TOKEN||JSON.parse(readFileSync(resolve(process.env.POCKET_DATA||resolve(root,'data'),'secrets.json'))).adminToken;
const server=new McpServer({name:'pocket',version:'0.5.0-alpha.5'},{instructions:'Notify the user about your own work only when they request it or a requested condition is met. Use the original current Codex thread ID (CODEX_THREAD_ID in your shell environment). Do not guess IDs. Messages reach only the paired owner. Include useful results and explicitly requested local attachments. Phone replies return as normal user messages to the same task.'});
server.tool('notify_user','Send the paired user a phone notification about this Codex task. Call when the notification condition requested by the user is met. The message opens the original thread and supports replies.',{
  thread_id:z.string().describe('Current Codex thread UUID, available as CODEX_THREAD_ID in the shell environment.'),
  title:z.string().max(180).describe('Specific outcome or action needed; avoid generic reply instructions.'),message:z.string().max(32000).describe('Useful task context and, if input is needed, the decision the user needs to make. Reply is already a native action.'),kind:z.enum(['update','complete','question','error']).default('update').describe('question and error create attention items with bounded reminders. Use update or complete for information that needs no user action.'),
  spoken_context:z.string().max(120).optional().describe('Summarize the user input being answered in about three words, for example Fix notification speech. Use the latest actual prompt, never the chat title. Pocket speaks this context before your response.'),
  spoken_summary:z.string().max(32000).optional().describe('Optional natural spoken version of this update. Use complete thoughts; Pocket plays it to the end without a word cutoff. No code, secrets, paths or URLs. Do not repeat the contextual label or chat title; Pocket adds spoken_context before this response. If omitted, Pocket reads the notification response.'),
  files:z.array(z.string()).max(5).optional().describe('Explicit local files to share with the owner, up to 12 MB each. Do not include credentials or unrelated files.')
},{readOnlyHint:false,destructiveHint:false,openWorldHint:false},async args=>{
  try{const r=await fetch(`${process.env.POCKET_URL||'http://127.0.0.1:18880'}/api/notify`,{method:'POST',headers:{Authorization:`Bearer ${token()}`,'Content-Type':'application/json'},body:JSON.stringify(args),signal:AbortSignal.timeout(30000)});const d=await r.json();if(!r.ok)throw new Error(d.error);return {content:[{type:'text',text:JSON.stringify({notification_id:d.notification.id,status:'accepted_by_pocket',thread_id:args.thread_id})}]};}
  catch(e){return {isError:true,content:[{type:'text',text:e.message}]};}
});
const migrationRegistryPath=resolve(process.env.POCKET_DATA||resolve(root,'data'),'terminal-forks.json');
const migrationTargets=existsSync(migrationRegistryPath)?Object.keys(JSON.parse(readFileSync(migrationRegistryPath,'utf8')).targets||{}):[];
if(migrationTargets.length)server.tool('terminal_fork','Prepare a native fork of your OWN Codex session on an owner-registered environment. Use the actual CODEX_THREAD_ID, never a title or guessed ID. request saves a checkpoint without starting a successor. release yields primary ownership after this turn finishes; make it your final tool action and end the source turn with the checkpoint. The source remains available for explicitly named dependencies. status observes receipts; cancel is permitted only before native fork creation. Unknown delivery is never resent.',{
  operation:z.enum(['request','release','status','cancel']),migration_id:z.string().min(5).max(100),
  source_thread_id:z.string().min(5).max(100).describe('This session’s CODEX_THREAD_ID. Do not transfer other sessions.'),
  target:z.string().optional().describe('Owner-registered destinations: '+migrationTargets.join(', ')),cwd:z.string().optional(),handoff:z.string().max(16000).optional(),primary_scope:z.string().max(4000).optional(),source_scope:z.string().max(4000).optional(),
  workspace_files:z.array(z.string()).max(256).optional(),dependencies:z.array(z.object({id:z.string(),state:z.enum(['settled','source-retained','unknown']),description:z.string()})).max(64).optional()
},{readOnlyHint:false,destructiveHint:false,openWorldHint:false},async args=>{
  try{
    if(process.env.CODEX_THREAD_ID&&args.source_thread_id!==process.env.CODEX_THREAD_ID)throw Error('This terminal tool can transfer only its own source session.');
    const api=async(path,body)=>{const r=await fetch(`${process.env.POCKET_URL||'http://127.0.0.1:18880'}${path}`,{headers:{Authorization:`Bearer ${token()}`,'Content-Type':'application/json'},...(body?{method:'POST',body:JSON.stringify(body)}:{}),signal:AbortSignal.timeout(15000)});const d=await r.json();if(!r.ok)throw Error(d.error||'Terminal transfer failed.');return d;};
    let result;
    if(args.operation==='request')result=await api('/api/terminal-forks',{id:args.migration_id,threadId:args.source_thread_id,target:args.target,cwd:args.cwd,handoff:args.handoff,primaryScope:args.primary_scope,sourceScope:args.source_scope||'',workspaceFiles:args.workspace_files||[],dependencies:args.dependencies||[]});
    else{const path='/api/terminal-forks/'+encodeURIComponent(args.migration_id),existing=await api(path);if(existing.sourceThreadId!==args.source_thread_id)throw Error('This checkpoint belongs to another source session.');result=args.operation==='status'?existing:await api(path+'/'+args.operation,{});}
    return {content:[{type:'text',text:JSON.stringify(result)}]};
  }catch(e){return {isError:true,content:[{type:'text',text:e.message}]};}
});
await server.connect(new StdioServerTransport());
