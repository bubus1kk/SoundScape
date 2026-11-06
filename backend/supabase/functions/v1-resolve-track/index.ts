import {authenticate,body,reserve,response,run,select,uuid,rpc,HttpError} from '../_shared/core.ts';
import { networkFailure, providerHttpFailure, providerLog, invalidProviderResponse } from '../_shared/provider_errors.ts';
import type { RequestContext } from '../_shared/observability.ts';
function secureStreamUrl(value:unknown):value is string {
 if(typeof value!=='string')return false;
 try {const url=new URL(value);return url.protocol==='https:' && !url.username && !url.password;}
 catch{return false;}
}
/** Null means an explicit 404/410 from Audius, not a network/timeout failure. */
export async function resolveAudius(id:string,ctx?:RequestContext):Promise<string|null>{
 const u=new URL(`https://api.audius.co/v1/tracks/${encodeURIComponent(id)}/stream`);
 u.searchParams.set('redirect','false');
 const headers:Record<string,string>={Accept:'application/json'};
 const key=Deno.env.get('AUDIUS_API_KEY');if(key)headers['x-api-key']=key;
 const r=await fetch(u,{headers,redirect:'manual',signal:AbortSignal.timeout(7000)})
   .catch(e=>{throw networkFailure('AUDIUS','stream',e,ctx);});
 if([302,303,307,308].includes(r.status)){
  const loc=r.headers.get('location');
  if(secureStreamUrl(loc) && (!key || !loc.includes(key)))return loc;
  throw invalidProviderResponse('AUDIUS','stream',ctx);
 }
 if(r.status===404||r.status===410){
  providerLog('AUDIUS','stream','SOURCE_NOT_FOUND',r.status,ctx);
  return null;
 }
 if(!r.ok)throw providerHttpFailure('AUDIUS','stream',r.status,r.headers.get('retry-after'),ctx);
 const contentType=r.headers.get('content-type')||'';
 if(/audio\//i.test(contentType)){
  await r.body?.cancel();
  // Direct Audius URL is only reusable without an authenticated x-api-key.
  if(!key)return u.toString();
  providerLog('AUDIUS','stream','AUTHENTICATED_AUDIO_URL',undefined,ctx);
  throw new HttpError(502,'PROVIDER_INVALID_RESPONSE','Audius did not provide a public stream URL');
 }
 if(!/json/i.test(contentType))throw invalidProviderResponse('AUDIUS','stream',ctx);
 const d=await r.json().catch(()=>{throw invalidProviderResponse('AUDIUS','stream',ctx);});
 const url=d?.data?.url||d?.data?.stream_url||d?.stream_url||d?.url;
 if(!secureStreamUrl(url) || (key && url.includes(key)))throw invalidProviderResponse('AUDIUS','stream',ctx);
 return url;
}
export default {fetch:run(async(req,ctx)=>{
 const session=await authenticate(req),p=await body(req),trackId=uuid(p.trackId,'trackId');
 await reserve('RESOLVE',session.userId,ctx);
 const tracks=await select('tracks',`select=id&id=eq.${trackId}&limit=1`,null,true,ctx);
 if(!tracks.length)throw new HttpError(404,'NOT_FOUND','Track not found');
 const sources=await select('track_sources',`select=provider,provider_track_id,external_url,can_stream,verified_at&track_id=eq.${trackId}`,null,true,ctx);
 const valid=sources.filter(s=>s.verified_at);
 if(!valid.length)throw new HttpError(404,'NOT_FOUND','No verified source');
 const audius=valid.find(s=>s.provider==='AUDIUS'&&typeof s.provider_track_id==='string');
 if(audius){
  const url=await resolveAudius(audius.provider_track_id,ctx);
  if(url){await rpc('edge_mark_streamable',{p_track:trackId,p_provider_id:audius.provider_track_id,p_allowed:true},ctx);
   return response({trackId,availability:'IN_APP',provider:'AUDIUS',streamUrl:url,streamExpiresAt:null,externalUrl:null,cacheAllowed:false,downloadAllowed:false});}
  // A 404/410 means no stream in this operation. Do NOT persist can_stream=false:
  // provider failures, geo restrictions and stale provider state are not catalog facts.
 }
 const external=valid.find(s=>typeof s.external_url==='string'&&s.external_url.startsWith('https://'));
 if(external)return response({trackId,availability:'EXTERNAL_ONLY',provider:external.provider,streamUrl:null,streamExpiresAt:null,
     externalUrl:external.external_url,cacheAllowed:false,downloadAllowed:false});
 throw new HttpError(404,'TRACK_UNAVAILABLE','Track has no currently available playable source');
},'v1-resolve-track')};
