// Both languages participate in intrinsic layout; only one is readable/focusable.
export function phraseSlots(pair,phase,render,{interactive=true}={}){
 const cells=[];const markup=pair.slots.map((unit,i)=>unit.eligible?'\uE000'+i+'\uE001':unit.target).join('');
 const root=render(markup),walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT),texts=[];
 while(walker.nextNode())texts.push(walker.currentNode);
 for(const text of texts){
  if(!/\uE000\d+\uE001/u.test(text.data)||text.parentElement?.closest('pre,code'))continue;
  const fragment=document.createDocumentFragment();let at=0;
  for(const match of text.data.matchAll(/\uE000(\d+)\uE001/gu)){
   fragment.append(document.createTextNode(text.data.slice(at,match.index)));at=match.index+match[0].length;
   const index=Number(match[1]),unit=pair.slots[index],box=document.createElement('span'),canInteract=interactive&&!text.parentElement?.closest('a,button,summary,label');box.className='immersion-phrase';if(canInteract){box.tabIndex=0;box.setAttribute('role','button');box.title='Switch this phrase';}else box.classList.add('phrase-label');
   const forms=['en','it'].map(language=>{const span=document.createElement('span');span.className='phrase-form';span.lang=language;span.textContent=language==='it'?unit.target:unit.meaning;box.append(span);return span;});
   let manual=null;
   const show=language=>{box.dataset.language=language;for(const span of forms){const visible=span.lang===language;span.style.visibility=visible?'visible':'hidden';span.setAttribute('aria-hidden',String(!visible));}box.setAttribute('aria-label',(language==='it'?unit.target:unit.meaning)+' · Switch language');};
   const setPhase=next=>{manual=null;const assignment=pair.assignment(next);show(assignment[pair.slots.slice(0,index).filter(u=>u.eligible).length]);};
   const toggle=()=>{manual=box.dataset.language==='it'?'en':'it';show(manual);};
   if(canInteract)box.onclick=e=>{e.stopPropagation();toggle();};if(canInteract)box.onkeydown=e=>{if(['Enter',' '].includes(e.key)){e.preventDefault();e.stopPropagation();toggle();}};
   setPhase(phase);cells.push({box,setPhase});fragment.append(box);
  }
  fragment.append(document.createTextNode(text.data.slice(at)));text.replaceWith(fragment);
 }
 // Never render private alignment markers if a malformed plan crosses Markdown structure.
 const remaining=document.createTreeWalker(root,NodeFilter.SHOW_TEXT);while(remaining.nextNode())remaining.currentNode.data=remaining.currentNode.data.replace(/\uE000(\d+)\uE001/gu,(_,i)=>pair.slots[Number(i)]?.meaning||'');
 return {root,setPhase(next){for(const cell of cells)cell.setPhase(next);},count:cells.length};
}
