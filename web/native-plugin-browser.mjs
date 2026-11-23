const node=(tag,text)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;return n;};
export function pluginBrowserControls(panel,view){
 const origin=panel.origin,serial=panel.serial,current=()=>panel.current(origin)&&panel.serial===serial;
 const load=(mode,query='',cursor,refresh=false)=>{if(current())return panel.load('plugins',cursor,{view:mode,query,refresh});};
 const button=(label,fn)=>{const n=node('button',label);n.onclick=fn;return n;};
 const controls=node('section');controls.dataset.pluginBrowser='';
 controls.append(button('Installed plugins',()=>load('installed')));
 if(view.browseAvailable===true)controls.append(button('Browse catalog',()=>load('browse')));
 const form=node('form'),search=node('input');search.type='search';search.maxLength=120;search.value=view.query||'';search.setAttribute('aria-label','Search plugins');
 const submit=node('button','Search catalog');submit.type='submit';form.append(search,submit);form.onsubmit=event=>{event.preventDefault();void load(view.browseAvailable===true?'browse':'installed',search.value);};controls.append(form);
 const total=view.total??view.items?.length??0,start=total?(view.offset||0)+1:0,end=(view.offset||0)+(view.items?.length||0);
 controls.append(node('p',`${view.pluginView==='browse'?'Catalog':'Installed plugins'} · ${start}–${end} of ${total}`));
 panel.pluginFooter.replaceChildren();
 if(view.previousCursor)panel.pluginFooter.append(button('Previous plugin page',()=>load(view.pluginView,view.query,view.previousCursor)));
 if(view.nextCursor)panel.pluginFooter.append(button('Next plugin page',()=>load(view.pluginView,view.query,view.nextCursor)));
 controls.append(button('Reload Plugins',()=>load(view.pluginView||'installed',view.query||'',undefined,true)));
 return controls;
}
