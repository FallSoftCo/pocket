// Human actions use server-provided offered action IDs, never client-authored grants.
export function renderNativeRequest({request,box,create,action,send,controls,prior={}}){
 const p=request.params||{};let ui=request._pocketUI;
 if(!ui&&/^item\/(commandExecution|fileChange)\/requestApproval$/.test(request.method))ui={kind:'approval',legacy:true,actions:(p.availableDecisions||['accept','decline','cancel']).filter(d=>['accept','decline','cancel'].includes(d)).map(d=>({id:d,label:{accept:'Allow once',decline:'Decline',cancel:'Cancel request'}[d]}))};
 if(!ui||ui.kind==='unsupported'){box.append(create('p','','This runtime request needs the terminal.'));return;}
 box.append(create('p','',p.reason||p.message||p.title||'Review this request.'));
 if(ui.kind==='elicitation'&&p.mode==='url'){
  try{const url=new URL(p.url);if(!['https:','http:'].includes(url.protocol))throw Error();const link=create('a','',`Open ${url.hostname}`);link.href=url.href;link.target='_blank';link.rel='noopener noreferrer';box.append(link,create('p','','Complete the external step, then continue here.'));}catch{box.append(create('p','','The request supplied an unsupported URL. Use the terminal.'));}
 }
 let form;
 if(ui.kind==='elicitation'&&['form','openai/form','openaiForm'].includes(p.mode)){
  const schema=p.requestedSchema||{},fields=Object.entries(schema.properties||{}),simple=schema.type==='object'&&fields.every(([,v])=>['string','number','integer','boolean'].includes(v.type)||v.type==='array'&&(v.items?.enum||v.items?.anyOf));
  if(simple){
   form={};for(const [key,field] of fields){
    const label=create('label','question-label',field.title||key);let input;
    const enumField=field.type==='array'?field.items:field,choices=enumField.enum?.map((value,i)=>({value,label:enumField.enumNames?.[i]??String(value)}))||(enumField.oneOf||enumField.anyOf)?.map(v=>({value:v.const,label:v.title||String(v.const)}))||(field.type==='boolean'?[{value:true,label:'Yes'},{value:false,label:'No'}]:null);
    if(choices){
     input=create('select');input.multiple=field.type==='array';for(const choice of choices){const option=create('option','',choice.label);option.value=String(choice.value);input.append(option);}
    }else{input=create('input');input.type=['number','integer'].includes(field.type)?'number':'text';}
    input.dataset.question=key;input.value=prior[key]??(field.default!==undefined?String(field.default):'');if(input.multiple){let selected=[];try{selected=JSON.parse(prior[key]||JSON.stringify(field.default||[]));}catch{}for(const option of input.options)option.selected=selected.includes(option.value);}input.setAttribute('aria-label',field.title||key);controls.push(input);form[key]={input,field};label.append(input);if(field.description)label.append(create('small','',field.description));box.append(label);
   }
  }else{const label=create('label','','Form values (JSON)'),input=create('textarea');input.dataset.question='_form';input.value=prior._form||'{}';input.setAttribute('aria-label','Form values (JSON)');label.append(input);box.append(label);controls.push(input);form=input;}
  const details=create('details'),summary=create('summary','','Requested form');details.append(summary,create('pre','',JSON.stringify(schema,null,2)));box.append(details);
 }
 if(ui.kind==='permissions')box.append(create('pre','',JSON.stringify(p.permissions,null,2)));
 if(ui.kind==='approval')box.append(create('pre','',p.command?Array.isArray(p.command)?p.command.join(' '):p.command:JSON.stringify(p.fileChanges||p,null,2)));
 if(p.cwd)box.append(create('p','',`Working directory: ${p.cwd}`));
 if(p.mode==='openai/userVerification')box.append(create('p','','Complete identity verification in the terminal. You can decline or cancel here.'));
 for(const choice of ui.actions||[]){if(choice.detail)box.append(create('pre','',choice.detail));action(choice.label,()=>{
  let content;if(form?.tagName==='TEXTAREA')content=form.value;else if(form){content={};for(const [key,{input,field}] of Object.entries(form)){if(input.multiple){content[key]=[...input.selectedOptions].map(o=>o.value);continue;}if(input.value==='')continue;content[key]=['number','integer'].includes(field.type)?Number(input.value):field.type==='boolean'?input.value==='true':input.value;}}
  send({...ui.legacy?{decision:choice.id}:{actionId:choice.id},...(content!==undefined?{content}:{})});
 });}
}
