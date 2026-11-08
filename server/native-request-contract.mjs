import {isDeepStrictEqual} from 'node:util';
const fail=message=>{throw Object.assign(Error(message),{status:400});};
const names={accept:'Allow once',acceptForSession:'Allow for this session',decline:'Decline',cancel:'Cancel request',approved:'Allow once',approved_for_session:'Allow for this session',abort:'Cancel request'};
export function approvalDecisions(request){
 const p=request.params||{};
 if(request.method==='item/commandExecution/requestApproval'){
  if(p.availableDecisions!==undefined&&p.availableDecisions!==null)return p.availableDecisions;
  // Native ExecApprovalRequestEvent::default_available_decisions, including
  // the narrower additional-permission and managed-network cases.
  if(p.networkApprovalContext){const amendment=p.proposedNetworkPolicyAmendments?.find(value=>value.action==='allow');return ['accept','acceptForSession',...(amendment?[{applyNetworkPolicyAmendment:{network_policy_amendment:amendment}}]:[]),'cancel'];}
  if(p.additionalPermissions)return ['accept','cancel'];
  return ['accept',...(p.proposedExecpolicyAmendment?[{acceptWithExecpolicyAmendment:{execpolicy_amendment:p.proposedExecpolicyAmendment}}]:[]),'cancel'];
 }
 if(request.method==='item/fileChange/requestApproval')return ['accept','acceptForSession','decline','cancel'];
 if(['execCommandApproval','applyPatchApproval'].includes(request.method))return ['approved','approved_for_session',{denied:{rejection:'Declined by the user.'}},'abort'];
 return [];
}
const decisionLabel=d=>typeof d==='string'?names[d]||d:d.acceptWithExecpolicyAmendment?'Allow with command rule':d.applyNetworkPolicyAmendment?'Apply network rule':d.denied?'Decline':'Review decision';
export function requestPresentation(request){
 const p=request.params||{},decisions=approvalDecisions(request);
 if(decisions.length)return {kind:'approval',actions:decisions.map((d,index)=>({id:String(index),label:decisionLabel(d),detail:typeof d==='object'?JSON.stringify(d,null,2):null}))};
 if(request.method==='item/permissions/requestApproval')return {kind:'permissions',actions:[{id:'turn',label:'Allow for this turn'},{id:'session',label:'Allow for this session'},{id:'decline',label:'Decline'}]};
 if(request.method==='mcpServer/elicitation/request')return {kind:'elicitation',mode:p.mode,actions:[...(p.mode!=='openai/userVerification'?[{id:'accept',label:p.mode==='url'?'Continue after opening link':'Submit form'}]:[]),{id:'decline',label:'Decline'},{id:'cancel',label:'Cancel request'}]};
 return {kind:request.method==='item/tool/requestUserInput'?'question':'unsupported',actions:[]};
}
// Validate ordinary MCP flat schemas and richer object/array forms without
// evaluating remote schema code or accepting unvalidated constraint keywords.
export function validateForm(value,schema,path='Form',depth=0){
 if(depth>16||!schema||typeof schema!=='object')fail(`${path}: unsupported form schema. Use the terminal.`);
 const supported=new Set(['type','properties','required','additionalProperties','title','description','default','enum','enumNames','oneOf','anyOf','const','items','minItems','maxItems','minLength','maxLength','minimum','maximum','format','$schema']);
 if(Object.keys(schema).some(k=>!supported.has(k)))fail(`${path}: this form constraint needs the terminal.`);
 if(schema.oneOf||schema.anyOf){const choices=schema.oneOf||schema.anyOf;let matches=0;for(const choice of choices){try{validateForm(value,choice,path,depth+1);matches++;}catch{}}if(schema.oneOf?matches!==1:!matches)fail(`${path}: choose a valid value.`);}
 if(schema.const!==undefined&&!isDeepStrictEqual(value,schema.const))fail(`${path}: invalid value.`);
 if(schema.enum&&!schema.enum.some(v=>isDeepStrictEqual(v,value)))fail(`${path}: choose an offered value.`);
 if(schema.type==='object'){
  if(!value||Array.isArray(value)||typeof value!=='object')fail(`${path}: enter an object.`);
  for(const key of schema.required||[])if(!(key in value))fail(`${path}: ${key} is required.`);
  for(const [key,v] of Object.entries(value)){if(schema.properties?.[key])validateForm(v,schema.properties[key],`${path}.${key}`,depth+1);else if(schema.additionalProperties===false)fail(`${path}: unexpected ${key}.`);else if(schema.additionalProperties&&typeof schema.additionalProperties==='object')validateForm(v,schema.additionalProperties,`${path}.${key}`,depth+1);}
 }else if(schema.type==='array'){
  if(!Array.isArray(value)||value.length<(schema.minItems??0)||value.length>(schema.maxItems??1000))fail(`${path}: invalid list.`);
  for(const v of value)validateForm(v,schema.items,path,depth+1);
 }else if(schema.type==='string'){
  if(typeof value!=='string'||value.length<(schema.minLength??0)||value.length>(schema.maxLength??8000))fail(`${path}: invalid text.`);
  if(schema.format&&!['email','uri','date','date-time'].includes(schema.format))fail(`${path}: unsupported format.`);
  if(schema.format==='email'&&!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value))fail(`${path}: enter an email address.`);
  if(schema.format==='uri'){try{new URL(value);}catch{fail(`${path}: enter a URI.`);}}
  if(['date','date-time'].includes(schema.format)&&!Number.isFinite(Date.parse(value)))fail(`${path}: enter a date.`);
 }else if(['number','integer'].includes(schema.type)){
  if(typeof value!=='number'||!Number.isFinite(value)||schema.type==='integer'&&!Number.isInteger(value)||value<(schema.minimum??-Infinity)||value>(schema.maximum??Infinity))fail(`${path}: invalid number.`);
 }else if(schema.type==='boolean'&&typeof value!=='boolean')fail(`${path}: choose true or false.`);
 else if(schema.type&& !['object','array','string','number','integer','boolean'].includes(schema.type))fail(`${path}: unsupported form type.`);
}
export function nativeRequestResult(request,body){
 const p=request.params||{},decisions=approvalDecisions(request);
 if(decisions.length){
  const offered=body.actionId!==undefined?decisions[Number(body.actionId)]:decisions.find(d=>isDeepStrictEqual(d,body.decision));
  if(body.skip||offered===undefined||body.actionId!==undefined&&!/^\d+$/.test(String(body.actionId)))fail('This decision is not offered by Codex.');
  return {decision:offered};
 }
 if(request.method==='item/permissions/requestApproval'){
  if(!['turn','session','decline'].includes(body.actionId))fail('Choose the permission scope.');
  return {permissions:body.actionId==='decline'?{}:Object.fromEntries(Object.entries(p.permissions||{}).filter(([,v])=>v!==null)),scope:body.actionId==='session'?'session':'turn'};
 }
 if(request.method==='mcpServer/elicitation/request'){
  const action=body.actionId||body.action;
  if(!requestPresentation(request).actions.some(a=>a.id===action))fail(p.mode==='openai/userVerification'?'This remote client cannot complete identity verification. A supported local Codex setup is required. Decline or cancel this request here.':'This action is not offered for this elicitation.');
  let content=null;
  if(action==='accept'&&p.mode!=='url'){
   content=body.content;
   if(typeof content==='string'){try{content=JSON.parse(content);}catch{fail('Enter valid JSON form values.');}}
   if(JSON.stringify(content)?.length>32000)fail('Form is too large.');
   validateForm(content,p.requestedSchema);
  }
  return {action,content,_meta:null};
 }
 fail('This runtime request must be handled in the terminal.');
}
