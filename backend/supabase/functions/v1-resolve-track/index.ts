import {authenticate,body,reserve,response,run,select,uuid,adminRest,rpc,HttpError} from '../_shared/core.ts';
export async function resolveAudius(id:string):Promise<string|null>{
 const u=new URL(`https://api.audius.co/v1/tracks/${encodeURIComponent(id)}/stream`);
 u.searchParams.set('redirect','false');
 const headers:Record<string,string>={Accept:'application/json'};
 const key=Deno.env.get('AUDIUS_API_KEY');if(key)headers['x-api-key']=key;
 const r=await fetch(u,{headers,redirect:'manual',signal:AbortSignal.timeout(7000)}).catch(()=>null);
 if(!r?.ok){ if(r?.status===302||r?.status===307){const loc=r.headers.get('location');
   return loc&&loc.startsWith('https://')?loc:null;} return null; }
 const contentType=r.headers.get('content-type')||'';
 if(/audio\//i.test(contentType)){await r.body?.cancel();return key?null:u.toString();} // no secret-bearing audio URL
 if(!contentType.includes('json'))return null;
 const d=await r.json().catch(()=>null);
 const url=d?.data?.url||d?.data?.stream_url||d?.stream_url||d?.url;
 return typeof url==='string'&&url.startsWith('https://')?url:null;
}
export default {fetch:run(async(req)=>{
 const session=await authenticate(req),p=await body(req),trackId=uuid(p.trackId,'trackId');
 await reserve('RESOLVE',session.userId);
 const tracks=await select('tracks',`select=id&id=eq.${trackId}&limit=1`,null,true);
 if(!tracks.length)throw new HttpError(404,'NOT_FOUND','Track not found');
 const sources=await select('track_sources',`select=provider,provider_track_id,external_url,can_stream,verified_at&track_id=eq.${trackId}`,null,true);
 const valid=sources.filter(s=>s.verified_at);
 if(!valid.length)throw new HttpError(404,'NOT_FOUND','No verified source');
 const audius=valid.find(s=>s.provider==='AUDIUS'&&typeof s.provider_track_id==='string');
 if(audius){
  const url=await resolveAudius(audius.provider_track_id);
  if(url){await rpc('edge_mark_streamable',{p_track:trackId,p_provider_id:audius.provider_track_id,p_allowed:true});
   return response({trackId,availability:'IN_APP',provider:'AUDIUS',streamUrl:url,streamExpiresAt:null,externalUrl:null,cacheAllowed:false,downloadAllowed:false});}
  // Do not claim playback when live URL cannot be resolved.
  await rpc('edge_mark_streamable',{p_track:trackId,p_provider_id:audius.provider_track_id,p_allowed:false});
 }
 const external=valid.find(s=>typeof s.external_url==='string'&&s.external_url.startsWith('https://'));
 if(external)return response({trackId,availability:'EXTERNAL_ONLY',provider:external.provider,streamUrl:null,streamExpiresAt:null,
     externalUrl:external.external_url,cacheAllowed:false,downloadAllowed:false});
 throw new HttpError(503,'PROVIDER_UNAVAILABLE','No usable source at this time');
})};
