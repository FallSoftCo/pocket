// Authored synthetic grammar cases, decoded through the production teacher contract.
import {prepareTransgrammaticBatch,validateTransgrammaticPlan,transgrammaticContract} from '../../server/immersion-transgrammatic.mjs';
import {immersionHash} from '../../server/immersion-plan.mjs';
const w=(target,meaning,role,group,linkPrevious='none')=>({target,meaning,role,group,linkPrevious,occurrence:0});
export const grammarBoundaryExamples=[
 {source:'The saved recordings are ready.',target:'Le registrazioni salvate sono pronte',words:[w('Le','The','function',0),w('registrazioni','recordings','noun',0),w('salvate','saved','adjective',0,'postnominal'),w('sono','are','auxiliary',1),w('pronte','ready','adjective',1)]},
 {source:'You can send it to the station.',target:'Lo puoi inviare alla stazione',words:[w('Lo','It','clitic',0),w('puoi','you can','auxiliary',0,'cliticPredicate'),w('inviare','send','verb',0),w('alla','to the','function',1),w('stazione','station','noun',1)]},
 {source:'Your latest message is in this conversation.',target:'Il tuo ultimo messaggio è in questa conversazione',words:[w('Il','The','function',0),w('tuo','your','adjective',0),w('ultimo','latest','adjective',0),w('messaggio','message','noun',0),w('è','is','verb',1),w('in','in','function',2),w('questa','this','function',2),w('conversazione','conversation','noun',2)]},
 {source:'I have not yet finished the guide.',target:'Non ho ancora finito la guida',words:[w('Non','Not','function',0),w('ho','I have','auxiliary',0),w('ancora','yet','other',0),w('finito','finished','verb',0),w('la','the','function',1),w('guida','guide','noun',1)]},
 {source:'Menu',target:'Menu',words:[w('Menu','Menu','noun',0)]}
];
export function grammarBoundaryPlans(){return grammarBoundaryExamples.map(({source,target,words},i)=>{
 const prepared=prepareTransgrammaticBatch([{id:'grammar-'+i,text:source,version:immersionHash(source)}]);
 const row=prepared.decode({'0':{'0':{target,words}}}).translations[0];row.text=transgrammaticContract.derive(source,row);
 const plan=validateTransgrammaticPlan(source,row);if(!plan)throw Error('Invalid authored grammar case '+i);
 return {...plan,id:row.id};
});}
