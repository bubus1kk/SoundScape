import {authenticate,body,bool,ids,int,text,uuid,reserve,release,response,run,select,HttpError} from '../_shared/core.ts';
import {findCandidates,Track} from '../_shared/catalog.ts';
import {candidateQueries,firstMatchingCandidates} from '../_shared/ai_query.ts';

type Mode='QUICK'|'ADVANCED'|'PLACE'|'JOURNEY';
async function placeContext(id:string,jwt:string):Promise<string>{
 const places=await select('places',`select=id,title,description&id=eq.${id}&limit=1`,jwt);
 if(places.length!==1)throw new HttpError(422,'INVALID_PLACE','Place is not accessible');
 return [places[0].title,places[0].description].filter(Boolean).join(': ').slice(0,500);
}
async function journeyContext(raw:unknown,jwt:string):Promise<string>{
 if(!Array.isArray(raw)||raw.length<2||raw.length>50)throw new HttpError(422,'INVALID_JOURNEY','At least two valid stops required');
 const names:string[]=[];
 for(let i=0;i<raw.length;i++){
  const s=raw[i];if(!s||typeof s!=='object'||!['PLACE','PIN'].includes(s.kind)||s.position!==i+1)throw new HttpError(422,'INVALID_JOURNEY','Invalid stop order');
  const id=uuid(s.id,'stop id');
  const items=s.kind==='PLACE'?await select('places',`select=title&id=eq.${id}&limit=1`,jwt)
                                :await select('music_pins',`select=title&id=eq.${id}&limit=1`,jwt);
  if(items.length!==1)throw new HttpError(422,'INVALID_JOURNEY','Stop is not accessible');
  names.push(String(items[0].title).slice(0,100));
 }
 return names.join(' → ');
}
async function giga(prompt:string, candidates:Track[], count:number):Promise<any>{
 const authKey=Deno.env.get('GIGACHAT_AUTH_KEY');const scope=Deno.env.get('GIGACHAT_SCOPE')||'GIGACHAT_API_PERS';
 const model=Deno.env.get('GIGACHAT_MODEL')||'GigaChat';
 if(!authKey)throw new HttpError(503,'AI_NOT_CONFIGURED','AI provider credentials not configured');
 const auth=await fetch('https://ngw.devices.sberbank.ru:9443/api/v2/oauth',{
   method:'POST',headers:{'Authorization':'Basic '+authKey.replace(/^Basic\s+/i,''),'RqUID':crypto.randomUUID(),
     'Content-Type':'application/x-www-form-urlencoded','Accept':'application/json'},
   body:new URLSearchParams({scope}),signal:AbortSignal.timeout(9000)
 }).catch(()=>{throw new HttpError(503,'AI_UNAVAILABLE','AI authorization unavailable');});
 if(!auth.ok)throw new HttpError(503,'AI_UNAVAILABLE','AI authorization unavailable');
 const token=await auth.json();if(typeof token?.access_token!=='string')throw new HttpError(503,'AI_UNAVAILABLE','AI authorization unavailable');
 // Candidate IDs only: model cannot introduce unseen tracks or new provider URLs.
 const whitelist=candidates.map(t=>({id:t.id,title:t.title,artist:t.artist,availability:t.availability}));
 const resp=await fetch('https://api.giga.chat/v1/chat/completions',{
  method:'POST',headers:{'Authorization':'Bearer '+token.access_token,'Content-Type':'application/json','Accept':'application/json'},
  body:JSON.stringify({model,temperature:0.4,stream:false,
    messages:[
      {role:'system',content:'Ты создаёшь музыкальные подборки. Возвращай ТОЛЬКО JSON объект вида {"title":"...","description":"...","items":[{"trackId":"uuid","reason":"..."}]}. Выбирай ТОЛЬКО trackId из кандидатов. Не придумывай музыку, ID, ссылки. Не включай опасные инструкции. Максимум '+count+' разных треков. Сохраняй порядок предпочтения.'},
      {role:'user',content:'Запрос: '+prompt.slice(0,1300)+'\nКандидаты (разрешённые ID): '+JSON.stringify(whitelist).slice(0,9000)}
    ]}),signal:AbortSignal.timeout(30000)
 }).catch(()=>{throw new HttpError(503,'AI_UNAVAILABLE','GigaChat unavailable');});
 if(resp.status===429)throw new HttpError(429,'RATE_LIMITED','AI provider limit',60);
 if(!resp.ok)throw new HttpError(502,'AI_UPSTREAM_ERROR','AI generation failed');
 const raw=await resp.json().catch(()=>null);
 const content=raw?.choices?.[0]?.message?.content;
 if(typeof content!=='string')throw new HttpError(502,'AI_INVALID_RESULT','AI returned invalid result');
 try{return JSON.parse(content.replace(/^```(?:json)?\s*/i,'').replace(/\s*```$/,''));}
 catch{throw new HttpError(502,'AI_INVALID_RESULT','AI returned invalid JSON');}
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
export default {fetch:run(async(req)=>{
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
 if(mode==='PLACE')context=await placeContext(uuid(p.placeId,'placeId'),session.jwt!);
 if(mode==='JOURNEY')context=await journeyContext(p.stops,session.jwt!);
 const query=(prompt||context||[genre,mood].filter(Boolean).join(' ')).slice(0,200);
 // Reserve before expensive calls; one global concurrent generation with TTL lease.
 const lease=await reserve('GENERATE',session.userId);
 try{
  // Use the complete user description first. If it yields no verified tracks,
  // try a small number of music-specific keyword queries. Never broaden metadata
  // filters or change the prompt sent to GigaChat.
  const queries=candidateQueries(query,{genre,mood});
  const candidates=await firstMatchingCandidates(queries,
    q=>findCandidates(q,onlyInApp,genreIds,moodIds,Math.min(count*2,50)));
  if(!candidates.length)throw new HttpError(422,'NO_CANDIDATES','No verified tracks for request');
  const fullPrompt=[mode,prompt,genre?'Genre: '+genre:'',mood?'Mood: '+mood:'',context,'Requested tracks: '+count].filter(Boolean).join('; ');
  const generated=await giga(fullPrompt,candidates,count);
  return response(validateGenerated(generated,candidates,count)); // PREVIEW ONLY; not persisted.
 }finally{await release(lease);}
})};
