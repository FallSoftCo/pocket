import {ownerRequest} from './local-client.mjs';
const args=process.argv.slice(2);
const value=name=>{const i=args.indexOf(name);return i<0?undefined:args[i+1];};
const threadId=process.env.CODEX_THREAD_ID,state=value('--state'),evidence=value('--evidence');
if(!threadId||!/^[a-zA-Z0-9_-]{5,100}$/.test(threadId))throw Error('Run this from the original Codex session with CODEX_THREAD_ID.');
if(!['continue','completed','needsInput','waitingDependency'].includes(state)||!evidence?.trim()||evidence.length>8000)throw Error('Provide --state continue|completed|needsInput|waitingDependency and --evidence with a concrete task checkpoint.');
const result=await ownerRequest(`/api/threads/${encodeURIComponent(threadId)}/continuation/checkpoint`,{method:'POST',body:{state,evidence}});
console.log(JSON.stringify({accepted:result.accepted,state:result.recovery?.state}));
