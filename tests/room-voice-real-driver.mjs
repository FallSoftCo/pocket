// Owned real-HTTP qualification driver; configuration remains in private evidence.
import {readFileSync} from 'node:fs';
import {DatabaseSync} from 'node:sqlite';
import {HollywoodClient} from '../server/hollywood.mjs';
import {HollywoodVoice} from '../server/hollywood-voice.mjs';
const [file,action]=process.argv.slice(2),c=JSON.parse(readFileSync(file,'utf8'));
const client=new HollywoodClient(c.binding),db=new DatabaseSync(c.database);
const voice=new HollywoodVoice({db,gateway:{client:()=>client}});
if(action==='submit'){
 const destination=await voice.destination(c.device,c.destination);
 await voice.submitText(c.device,c.id,c.text,destination);
 await voice.refresh(c.device,c.id);
 const row=voice.row(c.device,c.id);
 if(!row.event)throw Error('Original voice request was not accepted');
 console.log(JSON.stringify({eventId:row.event,operation:row.operation,state:row.state}));
}else if(action==='read'){
 const result=await voice.refresh(c.device,c.id);
 if(result.state!=='completed'||!result.response?.includes('ORCHID'))throw Error('Explicit native answer not available');
 console.log(JSON.stringify({state:result.state,response:result.response}));
}else throw Error('Unknown driver action');
db.close();
