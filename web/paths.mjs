// Each deployment owns its asset, API, WebSocket and worker scope.
export function clientBase(moduleUrl){return new URL('.',moduleUrl);}
export function clientURL(base,path){return new URL(String(path).replace(/^\//,''),base).href;}
