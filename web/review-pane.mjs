export function usefulNotes(notes,rows){
 const normalize=value=>String(value||'').replace(/\s+/g,' ').trim().toLowerCase();const userText=new Set(rows.filter(row=>row.kind==='user').map(row=>normalize(row.text))),seen=new Set();
 return notes.filter(note=>{const text=normalize(note.text),row=rows.find(row=>row.id===note.id||row.itemId===note.id||row.id.endsWith('/'+note.id));if(!text||userText.has(text)||row?.kind==='user'||seen.has(text))return false;seen.add(text);return true;});
}
export class ReviewPane {
 constructor({app,requests,toggle,create,button,open,reply,jump,remove}){
  Object.assign(this,{toggle,create,button,open,reply,jump,remove,notifications:[],seen:new Set()});
  this.panel=create('aside','review-pane');this.panel.id='review-pane';this.panel.hidden=true;this.panel.setAttribute('aria-label','Questions and saved results');
  const header=create('header','review-heading');this.heading=create('h2','','Needs you');header.append(this.heading,button('Close',()=>this.show(false)));this.panel.append(header);
  this.summary=create('p','review-summary');this.empty=create('p','muted','No unanswered questions.');this.other=create('section','review-other');this.notes=create('section','review-notes');this.notes.setAttribute('aria-label','Saved results');
  this.panel.append(this.summary,requests,this.empty,this.other,this.notes);app.append(this.panel);toggle.onclick=()=>this.show(this.panel.hidden);toggle.setAttribute('aria-controls',this.panel.id);
  document.addEventListener('keydown',event=>{if(event.key==='Escape'&&!this.panel.hidden){this.show(false);toggle.focus();}});
 }
 show(visible=true){this.panel.hidden=!visible;document.body.classList.toggle('review-open',visible);this.toggle.setAttribute('aria-expanded',String(visible));}
 update({scope,pending=[],notes=[],rows=[],notifications=this.notifications}){
  this.notifications=notifications;const other=notifications.filter(n=>n.thread_id!==scope&&['question','approval'].includes(n.kind));
  const count=pending.length+other.length;const saved=usefulNotes(notes,rows);this.toggle.textContent=count?'Needs you · '+count:saved.length?'Results · '+saved.length:'Questions';this.toggle.dataset.count=count||'';this.heading.textContent=count?'Needs you':'Questions and results';this.summary.textContent=count?count+' waiting for your decision':'No pending decisions';this.empty.hidden=count>0;
  const questionKeys=pending.map(p=>scope+'/'+p.id);if(questionKeys.some(key=>!this.seen.has(key))&&matchMedia('(min-width:1100px)').matches)this.show(true);for(const key of questionKeys)this.seen.add(key);
  this.other.replaceChildren();for(const n of other){const box=this.create('article','request-card');box.append(this.create('strong','',n.title||'Question from another session'),this.create('p','',n.body),this.button('Open and answer',async()=>{await this.open(n.thread_id);this.show(true);}));this.other.append(box);}
  this.notes.replaceChildren();if(saved.length)this.notes.append(this.create('h3','','Saved results'));
  for(const note of saved){const box=this.create('article','saved-result');box.append(this.create('p','',note.text));const actions=this.create('div','review-actions');actions.append(this.button('Reply',()=>{this.reply(note);if(!matchMedia('(min-width:1100px)').matches)this.show(false);}),this.button('In conversation',()=>{this.jump(note);if(!matchMedia('(min-width:1100px)').matches)this.show(false);}),this.button('Dismiss',()=>this.remove(note)));box.append(actions);this.notes.append(box);}
 }
}
