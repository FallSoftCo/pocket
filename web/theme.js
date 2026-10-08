// Runs in the head before styles paint. Theme belongs to this browser, independent
// of pairing, and follows live OS changes only while System is selected.
(() => {
 const script=document.currentScript;
 if(script?.src){const base=new URL('.',script.src).pathname;if(base!=='/'&&location.pathname===base.slice(0,-1))history.replaceState(history.state,'',base+location.search+location.hash);}
 const key='nextcomp.web.theme',valid=new Set(['system','light','dark']);
 const media=window.matchMedia?.('(prefers-color-scheme: dark)');
 let preference='system';
 try{const saved=JSON.parse(localStorage.getItem(key));if(valid.has(saved))preference=saved;}catch{}
 function apply(){const dark=preference==='dark'||(preference==='system'&&media?.matches);const theme=dark?'dark':'light';document.documentElement.dataset.theme=theme;document.documentElement.dataset.themePreference=preference;document.documentElement.style.colorScheme=theme;document.querySelector('meta[name="theme-color"]')?.setAttribute('content',dark?'#11191b':'#f6f5f0');for(const selector of document.querySelectorAll('[data-theme-selector]'))selector.value=preference;}
 function select(value){if(!valid.has(value))return;preference=value;try{localStorage.setItem(key,JSON.stringify(value));}catch{}apply();}
 apply();media?.addEventListener?.('change',apply);
 window.addEventListener('storage',event=>{if(event.key!==key)return;try{const value=JSON.parse(event.newValue);preference=valid.has(value)?value:'system';}catch{preference='system';}apply();});
 document.addEventListener('DOMContentLoaded',()=>{for(const selector of document.querySelectorAll('[data-theme-selector]')){selector.value=preference;selector.addEventListener('change',()=>select(selector.value));}});
 window.NextCompTheme={select,apply,get preference(){return preference;}};
})();
