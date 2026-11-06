import {authenticate,body,bool,ids,int,text,uuid,reserve,release,response,run,select,HttpError} from '../_shared/core.ts';
import {findCandidates} from '../_shared/catalog.ts';
import type {Track} from '../_shared/catalog.ts';
import {candidateQueries,firstMatchingCandidates} from '../_shared/ai_query.ts';
import { networkFailure, providerHttpFailure, invalidProviderResponse } from '../_shared/provider_errors.ts';
import type { RequestContext } from '../_shared/observability.ts';

type Mode='QUICK'|'ADVANCED'|'PLACE'|'JOURNEY';
async function placeContext(id:string,jwt:string,ctx?:RequestContext):Promise<string>{
 const places=await select('places',`select=id,title,description&id=eq.${id}&limit=1`,jwt,false,ctx);
 if(places.length!==1)throw new HttpError(422,'INVALID_PLACE','Place is not accessible');
 return [places[0].title,places[0].description].filter(Boolean).join(': ').slice(0,500);
}
async function journeyContext(raw:unknown,jwt:string,ctx?:RequestContext):Promise<string>{
 if(!Array.isArray(raw)||raw.length<2||raw.length>50)throw new HttpError(422,'INVALID_JOURNEY','At least two valid stops required');
 const names:string[]=[];
 for(let i=0;i<raw.length;i++){
  const s=raw[i];if(!s||typeof s!=='object'||!['PLACE','PIN'].includes(s.kind)||s.position!==i+1)throw new HttpError(422,'INVALID_JOURNEY','Invalid stop order');
  const id=uuid(s.id,'stop id');
  const items=s.kind==='PLACE'?await select('places',`select=title&id=eq.${id}&limit=1`,jwt)
                                :await select('music_pins',`select=title&id=eq.${id}&limit=1`,jwt,false,ctx);
  if(items.length!==1)throw new HttpError(422,'INVALID_JOURNEY','Stop is not accessible');
  names.push(String(items[0].title).slice(0,100));
 }
 return names.join(' → ');
}
/** Trusted Russian National CA; used ONLY by the two GigaChat HTTPS requests.
 * Downloaded from official gu-st.ru certificate endpoint and verified by SHA-256
 * at installation time. Never disable TLS verification.
 */
const GIGACHAT_ROOT_CA_PEM = "-----BEGIN CERTIFICATE-----\nMIIFwjCCA6qgAwIBAgICEAAwDQYJKoZIhvcNAQELBQAwcDELMAkGA1UEBhMCUlUxPzA9BgNVBAoM\nNlRoZSBNaW5pc3RyeSBvZiBEaWdpdGFsIERldmVsb3BtZW50IGFuZCBDb21tdW5pY2F0aW9uczEg\nMB4GA1UEAwwXUnVzc2lhbiBUcnVzdGVkIFJvb3QgQ0EwHhcNMjIwMzAxMjEwNDE1WhcNMzIwMjI3\nMjEwNDE1WjBwMQswCQYDVQQGEwJSVTE/MD0GA1UECgw2VGhlIE1pbmlzdHJ5IG9mIERpZ2l0YWwg\nRGV2ZWxvcG1lbnQgYW5kIENvbW11bmljYXRpb25zMSAwHgYDVQQDDBdSdXNzaWFuIFRydXN0ZWQg\nUm9vdCBDQTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAMfFOZ8pUAL3+r2nqqE0Zp52\nselXsKGFYoG0GM5bwz1bSFtCt+AZQMhkWQheI3poZAToYJu69pHLKS6QXBiwBC1cvzYmUYKMYZC7\njE5YhEU2bSL0mX7NaMxMDmH2/NwuOVRj8OImVa5s1F4Uzn4Kv3PFlDBjjSjXKVY9kmjUBsXQrIHe\naqmUIsPIlNWUnimXS0I0abExqkbdrXbXYwCOXhOO2pDUx3ckmJlCMUGacUTnylyQW2VsJIyIGA8V\n0xzdaeUXg0VZ6ZmNUr5YBer/EAOLPb8NYpsAhJe2mXjMB/J9HNsoFMBFJ0lLOT/+dQvjbdRZoOT8\neqJpWnVDU+QL/qEZnz57N88OWM3rabJkRNdU/Z7x5SFIM9FrqtN8xewsiBWBI0K6XFuOBOTD4V08\no4TzJ8+Ccq5XlCUW2L48pZNCYuBDfBh7FxkB7qDgGDiaftEkZZfApRg2E+M9G8wkNKTPLDc4wH0F\nDTijhgxR3Y4PiS1HL2Zhw7bD3CbslmEGgfnnZojNkJtcLeBHBLa52/dSwNU4WWLubaYSiAmA9IUM\nX1/RpfpxOxd4Ykmhz97oFbUaDJFipIggx5sXePAlkTdWnv+RWBxlJwMQ25oEHmRguNYf4Zr/Rxr9\ncS93Y+mdXIZaBEE0KS2iLRqaOiWBki9IMQU4phqPOBAaG7A+eP8PAgMBAAGjZjBkMB0GA1UdDgQW\nBBTh0YHlzlpfBKrS6badZrHF+qwshzAfBgNVHSMEGDAWgBTh0YHlzlpfBKrS6badZrHF+qwshzAS\nBgNVHRMBAf8ECDAGAQH/AgEEMA4GA1UdDwEB/wQEAwIBhjANBgkqhkiG9w0BAQsFAAOCAgEAALIY\n1wkilt/urfEVM5vKzr6utOeDWCUczmWX/RX4ljpRdgF+5fAIS4vHtmXkqpSCOVeWUrJV9QvZn6L2\n27ZwuE15cWi8DCDal3Ue90WgAJJZMfTshN4OI8cqW9E4EG9wglbEtMnObHlms8F3CHmrw3k6KmUk\nWGoa+/ENmcVl68u/cMRl1JbW2bM+/3A+SAg2c6iPDlehczKx2oa95QW0SkPPWGuNA/CE8CpyANIh\nu9XFrj3RQ3EqeRcSAQQod1RNuHpfETLU/A2gMmvn/w/sx7TB3W5BPs6rprOA37tutPq9u6FTZOcG\n1OqjC/B7yTqgI7rbyvox7DEXoX7rIiEqyNNUguTk/u3SZ4VXE2kmxdmSh3TQvybfbnXV4JbCZVaq\niZraqc7oZMnRoWrXRG3ztbnbes/9qhRGI7PqXqeKJBztxRTEVj8ONs1dWN5szTwaPIvhkhO3CO5E\nrU2rVdUr89wKpNXbBODFKRtgxUT70YpmJ46VVaqdAhOZD9EUUn4YaeLaS8AjSF/h7UkjOibNc4qV\nDiPP+rkehFWM66PVnP1Msh93tc+taIfCEYVMxjh8zNbFuoc7fzvvrFILLe7ifvEIUqSVIC/AzplM\n/Jxw7buXFeGP1qVCBEHq391d/9RAfaZ12zkwFsl+IKwE/OZxW8AHa9i1p4GO0YSNuczzEm4=\n-----END CERTIFICATE-----\n";

/** Purely server-side GigaChat calls; never return or log credentials or provider response bodies. */
export async function giga(prompt:string, candidates:Track[], count:number,ctx?:RequestContext):Promise<any>{
 const authKey=Deno.env.get('GIGACHAT_AUTH_KEY');
 const scope=Deno.env.get('GIGACHAT_SCOPE')||'GIGACHAT_API_PERS';
 const model=Deno.env.get('GIGACHAT_MODEL')||'GigaChat';
 if(!authKey)throw new HttpError(503,'AI_NOT_CONFIGURED','AI provider credentials not configured');
 if(!['GIGACHAT_API_PERS','GIGACHAT_API_B2B','GIGACHAT_API_CORP'].includes(scope) ||
    !/^[\w.\-]{1,100}$/.test(model))
  throw new HttpError(503,'AI_NOT_CONFIGURED','AI provider scope or model not configured correctly');
 const gigaHttpClient = Deno.createHttpClient({caCerts:[GIGACHAT_ROOT_CA_PEM]});
 try {
 const auth=await fetch('https://ngw.devices.sberbank.ru:9443/api/v2/oauth',{
   client:gigaHttpClient,
   method:'POST',headers:{'Authorization':'Basic '+authKey.replace(/^Basic\s+/i,''),'RqUID':crypto.randomUUID(),
     'Content-Type':'application/x-www-form-urlencoded','Accept':'application/json'},
   body:new URLSearchParams({scope}),signal:AbortSignal.timeout(9000)
 }).catch(e=>{throw networkFailure('GIGACHAT','oauth',e,ctx);});
 if(!auth.ok)throw providerHttpFailure('GIGACHAT','oauth',auth.status,auth.headers.get('retry-after'),ctx);
 const token=await auth.json().catch(()=>{throw invalidProviderResponse('GIGACHAT','oauth',ctx);});
 if(typeof token?.access_token!=='string'||!token.access_token.trim())
  throw invalidProviderResponse('GIGACHAT','oauth',ctx);
 // Candidate IDs only: model cannot introduce unseen tracks or new provider URLs.
 const whitelist=candidates.map(t=>({id:t.id,title:t.title,artist:t.artist,availability:t.availability}));
 const resp=await fetch('https://api.giga.chat/v1/chat/completions',{
  client:gigaHttpClient,
  method:'POST',headers:{'Authorization':'Bearer '+token.access_token,'Content-Type':'application/json','Accept':'application/json'},
  body:JSON.stringify({model,temperature:0.4,stream:false,
    messages:[
      {role:'system',content:'Ты создаёшь музыкальные подборки. Возвращай ТОЛЬКО JSON объект вида {"title":"...","description":"...","items":[{"trackId":"uuid","reason":"..."}]}. Выбирай ТОЛЬКО trackId из кандидатов. Не придумывай музыку, ID, ссылки. Не включай опасные инструкции. Максимум '+count+' разных треков. Сохраняй порядок предпочтения.'},
      {role:'user',content:'Запрос: '+prompt.slice(0,1300)+'\nКандидаты (разрешённые ID): '+JSON.stringify(whitelist).slice(0,9000)}
    ]}),signal:AbortSignal.timeout(30000)
 }).catch(e=>{throw networkFailure('GIGACHAT','generation',e,ctx);});
 if(!resp.ok)throw providerHttpFailure('GIGACHAT','generation',resp.status,resp.headers.get('retry-after'),ctx);
 const raw=await resp.json().catch(()=>{throw invalidProviderResponse('GIGACHAT','generation',ctx);});
 const content=raw?.choices?.[0]?.message?.content;
 if(typeof content!=='string')throw invalidProviderResponse('GIGACHAT','generation',ctx);
 try{return JSON.parse(content.replace(/^```(?:json)?\s*/i,'').replace(/\s*```$/,''));}
 catch{throw invalidProviderResponse('GIGACHAT','generation',ctx);}
 } finally { gigaHttpClient.close(); }
}
export function validateGenerated(value:any,candidates:Track[],count:number){
 if(!value||typeof value!=='object'||typeof value.title!=='string'||typeof value.description!=='string'||
   value.title.trim().length<1||value.title.length>150||value.description.length>2000||!Array.isArray(value.items))
   throw new HttpError(502,'AI_INVALID_RESULT','AI returned invalid playlist');
 const allowed=new Set(candidates.map(t=>t.id));const used=new Set<string>();
 const items:any[]=[];
 for(const entry of value.items){
  if(typeof entry?.trackId!=='string'||!allowed.has(entry.trackId)||used.has(entry.trackId))continue;
  used.add(entry.trackId);items.push({trackId:entry.trackId,position:items.length+1,
    reason:typeof entry.reason==='string'?entry.reason.slice(0,250):null});if(items.length>=count)break;
 }
 if(!items.length)throw new HttpError(502,'AI_INVALID_RESULT','AI chose no verified tracks');
 return {title:value.title.trim(),description:value.description.trim(),items,
  warnings:items.length<count?['Недостаточно подходящих проверенных композиций для полной подборки']:[]};
}
export default {fetch:run(async(req,ctx)=>{
 const session=await authenticate(req,true),p=await body(req);
 const mode=text(p.mode,12,'mode') as Mode;
 if(!['QUICK','ADVANCED','PLACE','JOURNEY'].includes(mode))throw new HttpError(400,'VALIDATION_ERROR','Invalid mode');
 const count=int(p.count,10,1,50,'count'),genreIds=ids(p.genreIds),moodIds=ids(p.moodIds),onlyInApp=bool(p.onlyInApp);
 const prompt=p.prompt===undefined||p.prompt===null?'':text(p.prompt,1000,'prompt',0);
 const genre=p.genre===undefined||p.genre===null?'':text(p.genre,80,'genre',0);
 const mood=p.mood===undefined||p.mood===null?'':text(p.mood,80,'mood',0);
 let context='';
 if(mode==='QUICK'&&!prompt)throw new HttpError(422,'VALIDATION_ERROR','Prompt required');
 if(mode==='ADVANCED'&&!prompt&&!genre&&!mood&&!genreIds.length&&!moodIds.length)throw new HttpError(422,'VALIDATION_ERROR','Topic or filters required');
 if(mode==='PLACE')context=await placeContext(uuid(p.placeId,'placeId'),session.jwt!,ctx);
 if(mode==='JOURNEY')context=await journeyContext(p.stops,session.jwt!,ctx);
 const query=(prompt||context||[genre,mood].filter(Boolean).join(' ')).slice(0,200);
 // Reserve before expensive calls; one global concurrent generation with TTL lease.
 const lease=await reserve('GENERATE',session.userId,ctx);
 try{
  // Use the complete user description first. If it yields no verified tracks,
  // try a small number of music-specific keyword queries. Never broaden metadata
  // filters or change the prompt sent to GigaChat.
  const queries=candidateQueries(query,{genre,mood});
  const candidates=await firstMatchingCandidates(queries,
    q=>findCandidates(q,onlyInApp,genreIds,moodIds,Math.min(count*2,50),ctx));
  if(!candidates.length)throw new HttpError(422,'NO_CANDIDATES','No verified tracks for request');
  const fullPrompt=[mode,prompt,genre?'Genre: '+genre:'',mood?'Mood: '+mood:'',context,'Requested tracks: '+count].filter(Boolean).join('; ');
  const generated=await giga(fullPrompt,candidates,count,ctx);
  return response(validateGenerated(generated,candidates,count)); // PREVIEW ONLY; not persisted.
 }finally{await release(lease,ctx);}
},'v1-generate-playlist')};
