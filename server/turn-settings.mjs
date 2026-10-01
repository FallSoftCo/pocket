// Use the connected Codex catalogue, including its advertised effort values.
export async function modelCatalogue(codex){
 const models=[],seen=new Set();let cursor=null;
 do{
  const page=await codex.call('model/list',{...(cursor?{cursor}:{}),limit:100,includeHidden:false});
  models.push(...(page.data||[]).filter(m=>!m.hidden));cursor=page.nextCursor;
  if(cursor&&seen.has(cursor))throw Error('Codex returned a repeated model cursor.');
  if(cursor)seen.add(cursor);
 }while(cursor);
 return models;
}
export function validateTurnSettings(input,models){
 const model=models.find(m=>m.model===input.model);
 if(!model)throw Object.assign(Error('Choose a model offered by this Codex environment.'),{status:400});
 const effort=input.effort||model.defaultReasoningEffort;
 if(!model.supportedReasoningEfforts?.some(e=>e.reasoningEffort===effort))throw Object.assign(Error('Choose a reasoning effort supported by this model.'),{status:400});
 if(!['default','plan'].includes(input.mode))throw Object.assign(Error('Choose Build or Plan mode.'),{status:400});
 return {model:model.model,effort,mode:input.mode};
}
export function turnOverrides(settings){
 if(!settings)return {};
 return {model:settings.model,effort:settings.effort,collaborationMode:{mode:settings.mode,settings:{model:settings.model,reasoning_effort:settings.effort,developer_instructions:null}}};
}
