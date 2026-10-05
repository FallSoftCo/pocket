import {readFileSync} from 'node:fs';
import test from 'node:test';import assert from 'node:assert/strict';
import {validateImmersionPlan,immersionHash,protectedImmersionRanges,validateTargetSegments,deriveImmersionText,resolveImmersionAnchor} from '../server/immersion-plan.mjs';
const row=(source,text,spans)=>({version:immersionHash(source),text,spans:spans.map(([source,target,occurrence=0])=>({source,target,occurrence,unit:'phrase',note:'',targetSegments:[{target,occurrence:0,meaning:source,role:'other',features:[],relations:[]}]}))});
test('complete contextual grammar units produce exact inline bilingual source',()=>{
 const source="I opened two new sessions and have an important question. I'm checking the model, then return to the session.";
 const text="I opened due nuove sessioni and have una domanda importante. Sto verificando il modello, then return alla sessione.";
 const result=validateImmersionPlan(source,row(source,text,[['two new sessions','due nuove sessioni'],['an important question','una domanda importante'],["I'm checking the model",'Sto verificando il modello'],['to the session','alla sessione']]));
 assert.equal(result.text,text);assert.equal(result.spans.length,4);for(const span of result.spans)assert.equal(source.slice(span.start,span.end),span.source);
});
test('UTF16 offsets resolved locally with repeated anchors and emoji',()=>{const source='🌍 the session and the session';const p=validateImmersionPlan(source,row(source,'🌍 the session and la sessione',[['the session','la sessione',1]]));assert.equal(p.spans[0].start,19);assert.equal(p.spans[0].end,30);});
test('wrong source version, overlap, substring splice and inconsistent hybrid are rejected',()=>{
 const source='two new sessions';const valid=row(source,'due nuove sessioni',[['two new sessions','due nuove sessioni']]);assert.equal(validateImmersionPlan(source,{...valid,version:'old'}),null);assert.equal(validateImmersionPlan(source,{...valid,text:'Invented claim'}),null);assert.equal(validateImmersionPlan(source,row(source,'bad',[['two new sessions','due nuove sessioni'],['new sessions','nuove sessioni']])),null);assert.equal(validateImmersionPlan(source,row(source,'two new sessionXYZ',[['s','XYZ',0]])),null);
});
test('executable syntax URLs filenames and model identifiers remain protected',()=>{const source='Open `notes.md`, run ```sh\ngit status\n``` with gpt-6-luna from /tmp/project and https://example.com --force';for(const token of ['notes.md','git status','gpt-6-luna','/tmp/project','https://example.com','--force'])assert.equal(validateImmersionPlan(source,row(source,source.replace(token,'changed'),[[token,'changed']])),null);assert.ok(protectedImmersionRanges(source).length>=5);});
test('complete fenced blocks including longer and tilde fences are protected',()=>{for(const f of ['~~~','````']){const source=`Before.\n${f}sh\necho hello\n${f}\nAfter.`;assert.equal(validateImmersionPlan(source,row(source,source.replace('echo hello','ciao'),[['echo hello','ciao']])),null)}});
test('numeric amounts percentages and deadlines cannot be included in replacement spans',()=>{for(const token of ['$12.50','68%','2026-10-04','10:30','3']){const source=`Preserve ${token} when checking the latest update.`;assert.equal(validateImmersionPlan(source,row(source,source.replace(token,'changed'),[[token,'changed']])),null);assert.ok(validateImmersionPlan(source,row(source,source.replace('the latest update','l’ultimo aggiornamento'),[['the latest update','l’ultimo aggiornamento']])));}});
test('spans cannot split surrogate pairs emoji sequences or combining characters',()=>{for(const [source,part]of [['🙂 here','\ude42'],['👩‍💻 here','👩'],['cafe\u0301 here','e']]){assert.equal(validateImmersionPlan(source,row(source,source.replace(part,'changed'),[[part,'changed']])),null)}const source='🙂 a new session';assert.ok(validateImmersionPlan(source,row(source,'🙂 una nuova sessione',[['a new session','una nuova sessione']])));});

const lexical=(target,meaning,role,features={},relations=[])=>({target,occurrence:0,meaning,role,features:Object.entries(features).map(([name,value])=>({name,value})),relations:relations.map(([kind,toSegment])=>({kind,toSegment,note:''}))});
const space=occurrence=>({...lexical(' ','','separator'),occurrence});
test('intact Italian plural agreement retains lexical source order and explicit linked grammar',()=>{
 const target='Le nuove sessioni sono pronte';const parts=[lexical('Le','the','determiner',{gender:'feminine',number:'plural'},[['agreesWith',4]]),space(0),lexical('nuove','new','adjective',{gender:'feminine',number:'plural'},[['agreesWith',4],['head',4]]),space(1),lexical('sessioni','sessions','noun',{gender:'feminine',number:'plural'}),space(2),lexical('sono','are','verb',{number:'plural',person:'third',tense:'present'},[['agreesWith',4]]),space(3),lexical('pronte','ready','adjective',{gender:'feminine',number:'plural'},[['agreesWith',4]])];
 const accepted=validateTargetSegments(target,parts);assert.equal(accepted.map(x=>x.target).join(''),target);assert.equal(accepted.filter(x=>x.meaning).map(x=>x.meaning).join(' '),'the new sessions are ready');assert.equal(accepted[2].relations[0].toSegment,4);assert.equal(accepted[0].start,0);assert.equal(accepted[8].end,target.length);
 const mismatch=structuredClone(parts);mismatch[4].features.find(x=>x.name==='gender').value='masculine';assert.equal(validateTargetSegments(target,mismatch),null);
 const missingEvidence=structuredClone(parts);missingEvidence[0].features=[];assert.equal(validateTargetSegments(target,missingEvidence),null);
});
test('contracted preposition remains whole with contextual gender rather than invented English gender',()=>{
 const target='alla sessione';const parts=[lexical('alla','to the','preposition',{gender:'feminine',number:'singular'},[['agreesWith',2]]),space(0),lexical('sessione','session','noun',{gender:'feminine',number:'singular'})];const p=validateTargetSegments(target,parts);assert.equal(p[0].target,'alla');assert.equal(p[0].meaning,'to the');assert.equal(p[0].features[0].value,'feminine');
});
test('negation and progressive auxiliary have their own honest contextual meanings and relations',()=>{
 const target='Non sto controllando';const parts=[lexical('Non','not','adverb',{},[['negates',4]]),space(0),lexical('sto','am','auxiliary',{person:'first',number:'singular',tense:'present',aspect:'progressive'},[['auxiliaryOf',4]]),space(1),lexical('controllando','checking','verb',{mood:'gerund',aspect:'progressive'})];const p=validateTargetSegments(target,parts);assert.equal(p.filter(x=>x.meaning).map(x=>x.meaning).join(' '),'not am checking');assert.equal(p[2].relations[0].kind,'auxiliaryOf');
});
test('ambiguous e ending uses contextual features rather than suffix inference',()=>{
 for(const [target,gender,number]of [['sessione importante','feminine','singular'],['sessioni importanti','feminine','plural'],['modello importante','masculine','singular']]){const [noun,adjective]=target.split(' ');const p=validateTargetSegments(target,[lexical(noun,'subject','noun',{gender,number}),space(0),lexical(adjective,'important','adjective',{gender,number},[['agreesWith',0]])]);assert.equal(p[2].features.find(x=>x.name==='gender').value,gender);}
});
test('exact target coverage repeated anchors and relation bounds reject false alignment',()=>{
 const p=[lexical('ciao','hello','other'),space(0),{...lexical('ciao','hello','other'),occurrence:1}];assert.equal(validateTargetSegments('ciao ciao',p).length,3);assert.equal(validateTargetSegments('ciao ciao',[p[0],p[2]]).map(x=>x.target).join(''),'ciao ciao');assert.equal(validateTargetSegments('ciao ciao',[p[0],p[1],{...p[2],occurrence:0}]),null);assert.equal(validateTargetSegments('ciao ciao',[p[0],p[1],{...p[2],relations:[{kind:'head',toSegment:9,note:''}]}]),null);assert.equal(validateTargetSegments('ciao ciao',[{...p[0],relations:[{kind:'head',toSegment:0,note:''}]},p[1],p[2]]),null);assert.equal(validateTargetSegments('ciao ciao',[p[0],{...p[1],meaning:'imaginary grammar'},p[2]]),null);
});

test('untrusted malformed annotations missing lexical meanings and suffix guessing are rejected without throwing',()=>{
 const base=lexical('sessione','session','noun',{gender:'feminine',number:'singular'});
 for(const parts of [[null],[{...base,features:[null]}],[{...base,relations:[null]}],[{...base,meaning:''}],[{...base,role:'punctuation',meaning:'',features:[]}],[lexical('session','session','noun'),lexical('e','','other')]])assert.equal(validateTargetSegments('sessione',parts),null);
 assert.equal(validateImmersionPlan('hello',null),null);assert.equal(validateImmersionPlan('hello',{version:immersionHash('hello'),text:'ciao',spans:[null]}),null);
});

test('quoted grammatical relation anchors resolve including separator positions without guessed indices',()=>{
 const parts=[lexical('Le','the','determiner',{gender:'feminine',number:'plural'}),space(0),lexical('nuove','new','adjective',{gender:'feminine',number:'plural'}),space(1),lexical('sessioni','sessions','noun',{gender:'feminine',number:'plural'})];parts[0].relations=[{kind:'agreesWith',target:'sessioni',occurrence:0,note:''}];parts[2].relations=[{kind:'head',target:'sessioni',occurrence:0,note:''}];const p=validateTargetSegments('Le nuove sessioni',parts);assert.equal(p[0].relations[0].toSegment,4);assert.equal(p[2].relations[0].toSegment,4);parts[0].relations[0].target='invented';assert.equal(validateTargetSegments('Le nuove sessioni',parts),null);
});
test('teacher deterministic reconstruction preserves all unselected characters and still validates strict external text',()=>{
 const source='Read `notes.md`: two new sessions.';const r=row(source,'wrong duplicate text',[['two new sessions','due nuove sessioni']]);const text=deriveImmersionText(source,r);assert.equal(text,'Read `notes.md`: due nuove sessioni.');assert.equal(validateImmersionPlan(source,r),null);assert.ok(validateImmersionPlan(source,{...r,text}));assert.equal(deriveImmersionText(source,{...r,spans:[...r.spans,...r.spans]}),null);
});

test('grammatical relationship directions reject fabricated inverse auxiliary subject and object links',()=>{
 const cases=[['sto modello',[lexical('sto','am','auxiliary'),space(0),lexical('modello','model','noun',{},[['auxiliaryOf',0]])]],['sono sessioni',[lexical('sono','are','verb',{},[['subjectOf',2]]),space(0),lexical('sessioni','sessions','noun')]],['verifico modello',[lexical('verifico','check','verb',{},[['objectOf',2]]),space(0),lexical('modello','model','noun')]]];for(const [target,parts]of cases)assert.equal(validateTargetSegments(target,parts),null);
});

test('lexical teacher anchors receive only verified whitespace and punctuation gaps with resolved agreement',()=>{
 const target='Le nuove sessioni sono pronte.';const p=validateTargetSegments(target,[lexical('Le','the','determiner',{gender:'feminine',number:'plural'}),lexical('nuove','new','adjective',{gender:'feminine',number:'plural'}),lexical('sessioni','sessions','noun',{gender:'feminine',number:'plural'}),lexical('sono','are','verb',{number:'plural'}),{...lexical('pronte','ready','adjective',{gender:'feminine',number:'plural'}),relations:[{kind:'agreesWith',target:'sessioni',occurrence:0,note:''}]}]);assert.equal(p.map(x=>x.target).join(''),target);assert.equal(p[8].relations[0].toSegment,4);assert.equal(p[9].role,'punctuation');assert.equal(p[7].target,' ');
 assert.equal(validateTargetSegments('Le nuove sessioni',[lexical('Le','the','determiner'),lexical('sessioni','sessions','noun')]),null);
});

test('quoted source anchors cannot splice larger words or English contractions',()=>{
 for(const [source,part] of [['possessions','sessions'],['𐐀sessions','sessions'],['sessions𐐀','sessions'],["I can't continue",'can'],["I'm ready",'I'],["I'm ready", "I'"],["I'm ready", "'m"],['we’ll continue','we']]){const text=source.replace(part,'ciao');assert.equal(validateImmersionPlan(source,row(source,text,[[part,'ciao']])),null);}
 const source="Open 'the session'";assert.ok(validateImmersionPlan(source,row(source,"Open 'la sessione'",[['the session','la sessione']])));
});

test('lexical quoted occurrences exclude embedded i a il and preserve exact repeated complete anchors',()=>{
 const target='Le nuove sessioni aiutano i nuovi clienti';const words=target.split(' ');const parts=words.map(word=>lexical(word,word,'other'));parts[4]={...lexical('i','the','determiner',{gender:'masculine',number:'plural'})};parts[6]={...lexical('clienti','clients','noun',{gender:'masculine',number:'plural'}),relations:[{kind:'agreesWith',target:'i',occurrence:0,note:''}]};const accepted=validateTargetSegments(target,parts);assert.ok(accepted);assert.equal(accepted[8].target,'i');assert.equal(accepted[12].relations[0].toSegment,8);
 for(const [text,quote]of [['sessioni aiutano i clienti','i'],['alla casa a Roma','a'],['simili il modello','il']])assert.equal(resolveImmersionAnchor(text,quote,0).start,text.indexOf(' '+quote+' ')+1);
 const repeated='simili il modello e il file';assert.equal(resolveImmersionAnchor(repeated,'il',0).start,7);assert.equal(resolveImmersionAnchor(repeated,'il',1).start,20);assert.equal(resolveImmersionAnchor(repeated,'il',2),null);
});
test('source reconstruction and validation share bounded occurrence semantics and reject unsafe joined graphemes',()=>{
 const source='sessionish session and session';const r=row(source,'sessionish sessione and session',[['session','sessione',0]]);assert.equal(deriveImmersionText(source,r),r.text);assert.equal(validateImmersionPlan(source,r).spans[0].start,11);
 for(const [text,quote]of [["I'm checking","I"],["l’acqua pronta","acqua"],["ozzz’s project","ozzz"],['cafe\u0301 e','e'],['👩‍💻 i','👩']]){const match=resolveImmersionAnchor(text,quote,0);if(text==='cafe\u0301 e')assert.equal(match.start,6);else assert.equal(match,null);}
 const t='A    B   C';const parts=['A','B','C'].map(word=>lexical(word,word,'other'));assert.equal(validateTargetSegments(t,parts).map(s=>s.target).join(''),t);
});

test('inline replacement accepts strong density and partial mixed grammar in one preserved flow',()=>{
 const source='I opened two new sessions and reviewed the important changes before lunch.';
 const text='I opened due nuove sessioni and rivisto le modifiche importanti prima lunch.';
 const r=validateImmersionPlan(source,row(source,text,[['two new sessions','due nuove sessioni'],['reviewed the important changes','rivisto le modifiche importanti'],['before','prima']]));assert.equal(r.text,text);assert.equal(r.original,source);assert.equal(r.planVersion,'inline-replacement-v3');assert.equal(r.spans.length,3);assert.ok(r.text.startsWith('I opened '));assert.ok(r.text.endsWith(' lunch.'));
});
test('ordinary long content rejects whole source coverage sentence fallback and overlong anchors',()=>{
 const source='I review the latest changes and prepare the final report now.';
 const all=row(source,'bad',[['I review the latest changes','Esamino le ultime modifiche'],['and prepare the final report now','e preparo il rapporto finale ora']]);assert.equal(validateImmersionPlan(source,{...all,text:deriveImmersionText(source,all)}),null);
 const sentences='The new sessions are ready. I will review the final report after lunch.';
 const full=row(sentences,'Le nuove sessioni sono pronte. I will review the final report after lunch.',[['The new sessions are ready','Le nuove sessioni sono pronte']]);assert.equal(validateImmersionPlan(sentences,full),null);
 const long=row(source,'bad',[['review the latest changes and prepare the final report','esaminare le modifiche e preparare il rapporto']]);assert.equal(validateImmersionPlan(source,{...long,text:deriveImmersionText(source,long)}),null);
 assert.ok(validateImmersionPlan('New session',row('New session','Nuova sessione',[['New session','Nuova sessione']])));
});
test('single-flow partial selection keeps protected literals and original send source exact',()=>{
 const source='Please review the latest changes in `notes.md` using gpt-6-luna and https://example.com before lunch.';
 const text='Please review le ultime modifiche in `notes.md` using gpt-6-luna and https://example.com prima lunch.';
 const accepted=validateImmersionPlan(source,row(source,text,[['the latest changes','le ultime modifiche'],['before','prima']]));assert.equal(accepted.text,text);assert.equal(accepted.original,source);assert.ok(accepted.text.includes('`notes.md` using gpt-6-luna and https://example.com'));assert.equal(accepted.text.includes('\n'),false);
});

test('recorded stock inline teacher response retains safe replacements while omitting unsupported metadata only',()=>{
 const receipt=JSON.parse(readFileSync(new URL('../docs/qualification/inline-replacement-v3-stock-codex.json',import.meta.url)));const raw=receipt.raw.translations[0];const text=deriveImmersionText(receipt.source,raw);const accepted=validateImmersionPlan(receipt.source,{...raw,text});assert.ok(accepted);assert.equal(accepted.spans.length,3);assert.deepEqual(accepted.spans[0].targetSegments,[]);assert.ok(accepted.spans[1].targetSegments.length>0);assert.ok(accepted.spans[2].targetSegments.length>0);assert.equal(accepted.text,'I opened due nuove sessioni and ho esaminato le ultime modifiche in `notes.md` with gpt-6-luna prima di pranzo.');assert.equal(accepted.original,receipt.source);assert.equal(accepted.text.includes('\n'),false);
});
test('cross-span grammatical references are omitted without relaxing protected-source or overlap guards',()=>{
 const source='We review the new sessions using `notes.md` before lunch.';const r=row(source,'Noi review le nuove sessioni using `notes.md` before lunch.',[['We','Noi'],['the new sessions','le nuove sessioni']]);r.spans[0].targetSegments[0].relations=[{kind:'agreesWith',target:'sessioni',occurrence:0,note:''}];const accepted=validateImmersionPlan(source,r);assert.deepEqual(accepted.spans[0].targetSegments,[]);assert.ok(accepted.spans[1].targetSegments.length>0);
 const unsafe=row(source,source.replace('notes.md','note.md'),[['notes.md','note.md']]);unsafe.spans[0].targetSegments=[];assert.equal(validateImmersionPlan(source,unsafe),null);
 const overlap=row(source,'wrong',[['the new sessions','le nuove sessioni'],['new sessions','nuove sessioni']]);overlap.spans.forEach(s=>s.targetSegments=[]);assert.equal(validateImmersionPlan(source,overlap),null);
});
