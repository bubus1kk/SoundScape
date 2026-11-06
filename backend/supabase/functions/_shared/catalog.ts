import { HttpError, select, rpc, isSafeExternalUrl } from './core.ts';
export type Track = {id:string;title:string;artist:string;durationMs:number;availability:'IN_APP'|'EXTERNAL_ONLY';provider?:string};
export async function trackViews(ids:string[]):Promise<Track[]> {
 if(!ids.length)return [];
 const [ts,as,ta,sources]=await Promise.all([
  select('tracks','select=id,title,duration_ms&id=in.('+ids.join(',')+')',null,true),
  select('artists','select=id,name',null,true),
  select('track_artists','select=track_id,artist_id,credit_order&track_id=in.('+ids.join(',')+')',null,true),
  select('track_sources','select=track_id,provider,can_stream,external_url,verified_at&track_id=in.('+ids.join(',')+')',null,true)
 ]);
 const artists=new Map(as.map(a=>[a.id,a.name]));
 return ts.flatMap(t=>{
  const candidate=sources.filter(s=>s.track_id===t.id&&s.verified_at&&(s.can_stream||isSafeExternalUrl(s.external_url)));
  if(!candidate.length)return [];
  const inApp=candidate.some(s=>s.can_stream);
  const provider=candidate.find(s=>s.can_stream)||candidate[0];
  return [{id:t.id,title:t.title,artist:ta.filter(a=>a.track_id===t.id).sort((a,b)=>a.credit_order-b.credit_order).map(a=>artists.get(a.artist_id)).filter(Boolean).join(', ')||'Unknown artist',durationMs:t.duration_ms,availability:inApp?'IN_APP':'EXTERNAL_ONLY',provider:provider.provider} as Track];
 });
}
export async function localTracks(query:string,genreIds:number[],moodIds:number[],onlyInApp:boolean,limit=100):Promise<Track[]> {
 // Fetch bounded candidate set, then filter on verified sources; no raw SQL from a caller.
 const ts=await select('tracks','select=id,title&title=ilike.*'+encodeURIComponent(query.replace(/[%*_(),]/g,' '))+'*&limit='+limit,null,true);
 let trackIds=ts.map(t=>t.id).filter((x:string)=>/^[\w-]{36}$/.test(x));
 if(!trackIds.length)return [];
 if(genreIds.length){const items=await select('track_genres','select=track_id,genre_id&track_id=in.('+trackIds.join(',')+')',null,true);
  trackIds=trackIds.filter((id:string)=>items.some((it:any)=>it.track_id===id&&genreIds.includes(it.genre_id)));}
 if(moodIds.length){const items=await select('track_moods','select=track_id,mood_id&track_id=in.('+trackIds.join(',')+')',null,true);
  trackIds=trackIds.filter((id:string)=>items.some((it:any)=>it.track_id===id&&moodIds.includes(it.mood_id)));}
 const found=await trackViews(trackIds);
 return found.filter(t=>!onlyInApp||t.availability==='IN_APP');
}
export type AudiusTrack={id:string;title:string;artist:string;durationMs:number;permalink:string};
export async function audiusSearch(query:string,limit=20,offset=0):Promise<AudiusTrack[]> {
 const u=new URL('https://api.audius.co/v1/tracks/search');
 u.searchParams.set('query',query);u.searchParams.set('limit',String(Math.min(30,limit)));u.searchParams.set('offset',String(Math.min(1000,offset)));
 const key=Deno.env.get('AUDIUS_API_KEY');
 const headers:Record<string,string>={'accept':'application/json'};
 if(key)headers['x-api-key']=key;
 const res=await fetch(u,{headers,signal:AbortSignal.timeout(7000)})
  .catch(()=>{throw new HttpError(503,'PROVIDER_UNAVAILABLE','Audius unavailable');});
 if(!res.ok)throw new HttpError(res.status===429?429:502,res.status===429?'RATE_LIMITED':'PROVIDER_ERROR','Audius unavailable',res.status===429?60:undefined);
 const payload=await res.json().catch(()=>({}));
 const data=Array.isArray(payload.data)?payload.data:[];
 return data.flatMap((x:any)=>{
  const artist=x?.user?.name;
  const url=typeof x?.permalink==='string' ? (x.permalink.startsWith('/')?'https://audius.co'+x.permalink:x.permalink):null;
  const duration=Math.round(Number(x.duration)*1000);
  if(typeof x.id!=='string'||typeof x.title!=='string'||typeof artist!=='string'||!Number.isInteger(duration)||duration<1||duration>7200000||!isSafeExternalUrl(url)||x.is_delete||x.is_unlisted||x.is_streamable===false&&x.is_downloadable===false)return [];
  return [{id:x.id,title:x.title,artist,durationMs:duration,permalink:url}];
 });
}
export async function importAudius(items:AudiusTrack[]):Promise<Track[]> {
 const ids:string[]=[];
 for(const t of items){
   try{const id=await rpc('edge_ingest_audius',{p_provider_id:t.id,p_title:t.title,p_artist:t.artist,p_duration_ms:t.durationMs,p_permalink:t.permalink});if(typeof id==='string')ids.push(id)}
   catch(e){if(e instanceof HttpError)throw e;}
 }
 return trackViews(ids);
}
export async function findCandidates(query:string,onlyInApp:boolean,genreIds:number[],moodIds:number[],count:number):Promise<Track[]> {
 const local=await localTracks(query,genreIds,moodIds,onlyInApp,Math.min(100,Math.max(count*3,50)));
 if(local.length>=count)return local;
 // External catalog candidates only enter with confirmed canonical Audius permalink.
 // Genres/moods are not guessed from provider metadata: when filters are present use normalized DB only.
 if(genreIds.length||moodIds.length)return local;
 const remote=await audiusSearch(query,Math.min(30,count*2));
 const imported=await importAudius(remote);
 const all=new Map([...local,...imported].filter(t=>!onlyInApp||t.availability==='IN_APP').map(t=>[t.id,t]));
 return [...all.values()];
}
// MusicBrainz is metadata-only: an MBID alone never constitutes a stream/external playable link.
export async function musicBrainzRecording(query:string):Promise<{id:string;title:string}[]> {
 const ua=Deno.env.get('MUSICBRAINZ_USER_AGENT');
 if(!ua)return []; // do not send anonymous User-Agent
 const u=new URL('https://musicbrainz.org/ws/2/recording/');u.searchParams.set('query',query);u.searchParams.set('fmt','json');u.searchParams.set('limit','5');
 const r=await fetch(u,{headers:{'user-agent':ua,accept:'application/json'},signal:AbortSignal.timeout(6000)}).catch(()=>null);
 if(!r?.ok)return [];
 const j=await r.json().catch(()=>({}));return (Array.isArray(j.recordings)?j.recordings:[]).filter((x:any)=>typeof x.id==='string'&&typeof x.title==='string').map((x:any)=>({id:x.id,title:x.title}));
}
