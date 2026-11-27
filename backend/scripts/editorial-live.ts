import { audiusList, audiusMetadata, audiusTrack, checkAudiusStream, withAudiusProbe } from '../supabase/functions/_shared/audius.ts';
import type { AudiusTrack } from '../supabase/functions/_shared/audius.ts';
import { databaseUuidRe, HttpError, release, reserve, secretKey, sha256, supabaseUrl, uuidRe } from '../supabase/functions/_shared/core.ts';
import { gigaCompletionResponse, gigaOAuth, openGigaClient } from '../supabase/functions/_shared/gigachat_client.ts';
import { decodeCompletion } from '../supabase/functions/_shared/gigachat_response.ts';
import { newRequestContext } from '../supabase/functions/_shared/observability.ts';
import { providerHttpFailure } from '../supabase/functions/_shared/provider_errors.ts';
import { resolveTrack } from '../supabase/functions/v1-resolve-track/index.ts';
import { completionConcept, isolatedEditorialRpc, type FixtureTarget } from './editorial.ts';
import { editorialCities, editorialModel, editorialPrompt, type CityCode } from './editorial-cities.ts';
import { resolve } from 'node:path';

type OriginalSource = {provider:string; provider_track_id:string; external_url:string|null; verified_at:string|null; can_stream:boolean};
type Original = {id:string; title:string; artist:string; durationMs:number; isrc?:string; recordingMbid?:string; sources:OriginalSource[]};
type Inventory = {city:CityCode; placeId:string; playlistId:string; title:string; description:string; coverPath:string; tracks:Original[]};
export type VerifiedCandidate = AudiusTrack & {verifiedAt:string; canStream:true; canonicalTrackId?:string};
type Slot = {keepExternal:string} | {audius:string};
type Plan = {version:1; city:CityCode; placeId:string; playlistId:string; expectedRevision:number;
  original:Inventory; slots:Slot[]; verified:VerifiedCandidate[]; decision: {id:string; action:string; reason:string}[]};
type Budget = {approvalId:string; audius:number; oauth:number; completion:number; tokens:number};
type Event = {stage:'search'|'metadata'|'stream'|'oauth'|'completion'; at:number; tokens:number};
type Ledger = {
  record:(stage:Event['stage'],tokens?:number)=>Promise<void>;
  lastAudiusAt:number;
  usage:(decoded:ReturnType<typeof decodeCompletion>)=>Promise<void>;
  prove:(candidate:VerifiedCandidate)=>Promise<void>;
  hasProof:(candidate:VerifiedCandidate)=>Promise<boolean>;
};
type Config = {url:string; marker:string; inventory:string; plan:string; checkpoint:string; ledger:string;
  keepExternal:string[]; budget:Budget; previewCoverPath?:string; discoveryQueries?:string[]; orderedSelection?:string[];
  maxCandidateDurationMs?:number};
const providerId = /^(?!BE03Fixture-)[A-Za-z0-9-]{1,120}$/;
const permalink = /^https:\/\/(?:www\.)?audius\.co\/[A-Za-z0-9_-]+\/[A-Za-z0-9_-]+$/;
const validDate = (value:string,now=Date.now()) => Number.isFinite(Date.parse(value)) &&
  Date.parse(value)<=now && Date.parse(value)>now-3600_000;
const normalized = (value:string) => value.normalize('NFC').trim().toLowerCase();
const wait = (ms:number) => new Promise<void>(resolve=>setTimeout(resolve,ms));
const editorialTrackCount = (count:number) => count>=12 && count<=15;

export function editorialReceiptDigest(candidate:VerifiedCandidate) {
  const c=candidate;
  return sha256(JSON.stringify([c.id,c.title,c.artist,c.artistId,c.durationMs,c.permalink,
    c.genre,c.mood,c.isrc,c.recordingMbid,c.verifiedAt,c.canStream,c.canonicalTrackId]));
}

function decodeEditorialCompletion(raw: any) {
  const message=raw?.choices?.[0]?.message;
  const fenced=typeof message?.content==='string'
    ? /^\s*```(?:json)?\s*([\s\S]+?)\s*```\s*$/i.exec(message.content) : null;
  if (fenced) message.content=fenced[1];
  return decodeCompletion(raw);
}

export function sameRecording(candidate:AudiusTrack, original:Original) {
  if (original.sources.some(s=>s.provider==='AUDIUS' && s.provider_track_id===candidate.id)) return true;
  const idMatches = (candidate.isrc && original.isrc && candidate.isrc===original.isrc.toUpperCase()) ||
    (candidate.recordingMbid && original.recordingMbid && candidate.recordingMbid===original.recordingMbid.toLowerCase());
  return Boolean(idMatches) && normalized(candidate.title)===normalized(original.title) &&
    normalized(candidate.artist)===normalized(original.artist) &&
    Math.abs(candidate.durationMs-original.durationMs)<=2000;
}

export function exactExternalForEditorial(original:Original) {
  return original.sources.some(s=>{
    if (s.can_stream!==false || !s.verified_at || !Number.isFinite(Date.parse(s.verified_at)) ||
        Date.parse(s.verified_at)>Date.now() || !s.external_url) return false;
    if (s.provider==='APPLE_MUSIC') {
      const track = s.external_url.match(/^https:\/\/music\.apple\.com\/[a-z]{2}\/song\/[\w.%\-]+\/(\d+)$/) ??
        s.external_url.match(/^https:\/\/music\.apple\.com\/[a-z]{2}\/album\/[\w.%\-]+\/\d+\?i=(\d+)(?:&uo=\d+)?$/);
      return track?.[1]===s.provider_track_id;
    }
    if (s.provider==='BANDCAMP') return /^https:\/\/[a-z0-9-]+\.bandcamp\.com\/track\/[a-zA-Z0-9_-]+$/.test(s.external_url);
    return s.provider==='AUDIUS' && permalink.test(s.external_url) &&
      !s.external_url.split('/').slice(-2).some(p=>/^(search|explore|trending|playlists|albums|tracks|settings|feed|reposts|favorites)$/i.test(p));
  });
}

function validInventory(value:Inventory) {
  if (!value || !editorialCities.some(c=>c.code===value.city) || !uuidRe.test(value.placeId) ||
      !uuidRe.test(value.playlistId) || !value.title?.trim() || !Array.isArray(value.tracks) ||
      !editorialTrackCount(value.tracks.length) || new Set(value.tracks.map(t=>t.id)).size!==value.tracks.length ||
      value.tracks.some(t=>!databaseUuidRe.test(t.id) || !t.title?.trim() || !Array.isArray(t.sources))) {
    throw Error('Expected existing city, official playlist and 12–15 distinct original tracks');
  }
  return value;
}

export function validateLivePlan(plan:Plan, now=Date.now(), allowStale=false) {
  validInventory(plan.original);
  if (plan.version!==1 || plan.original.city!==plan.city || plan.original.placeId!==plan.placeId ||
      plan.original.playlistId!==plan.playlistId || !Number.isSafeInteger(plan.expectedRevision) ||
      plan.expectedRevision<1 || !Array.isArray(plan.slots) || !editorialTrackCount(plan.slots.length) ||
      !Array.isArray(plan.verified) || plan.verified.length>45) throw Error('Invalid editorial plan');
  const verified = new Map(plan.verified.map(t=>[t.id,t]));
  if (verified.size!==plan.verified.length) throw Error('Duplicate verification evidence');
  const ids = new Set<string>();
  let external = 0;
  for (const slot of plan.slots) {
    let id: string;
    if ('keepExternal' in slot) {
      const old=plan.original.tracks.find(t=>t.id===slot.keepExternal);
      if (!old || !exactExternalForEditorial(old)) throw Error('External selection lacks exact existing evidence');
      id=old.id;
      external++;
    } else {
      const track=verified.get(slot.audius);
      if (!track || track.canStream!==true || !providerId.test(track.id) || !permalink.test(track.permalink) ||
          !Number.isFinite(Date.parse(track.verifiedAt)) || Date.parse(track.verifiedAt)>now ||
          (!allowStale && !validDate(track.verifiedAt,now))) throw Error('Fresh successful Audius verification required');
      const original=plan.original.tracks.find(t=>t.id===track.canonicalTrackId);
      if (track.canonicalTrackId && (!original || !sameRecording(track,original))) {
        throw Error('Canonical track identity is unconfirmed');
      }
      id=track.canonicalTrackId ?? track.id;
    }
    if (ids.has(id)) throw Error('Duplicate editorial slot');
    ids.add(id);
  }
  if (external!==2 && external!==3) throw Error('Expected two or three retained external recordings');
  return plan;
}

function checkBudget(budget:Budget) {
  if (!/^[A-Za-z0-9_-]{6,80}$/.test(budget?.approvalId) ||
      ([['audius',10000],['oauth',40],['completion',100],['tokens',300000]] as const).some(([key,max])=>
        !Number.isSafeInteger(budget[key]) || budget[key]<1 || budget[key]>max)) {
    throw Error('An explicit approved provider budget is required');
  }
}

async function withLedger<T>(path:string,budget:Budget,action:(ledger:Ledger)=>Promise<T>) {
  checkBudget(budget);
  const file=await Deno.open(path,{read:true,write:true,create:true,append:true});
  try {
    await file.lock(true);
    const size=(await file.stat()).size;
    if (size>4_000_000) throw Error('Ledger size exceeded');
    const bytes=new Uint8Array(size);
    for (let offset=0;offset<size;) {
      const len=await file.read(bytes.subarray(offset));
      if (!len) throw Error('Incomplete ledger read');
      offset+=len;
    }
    const entries=new TextDecoder().decode(bytes).split('\n').filter(Boolean).map(line=>JSON.parse(line));
    const existing=entries.shift();
    if (existing && JSON.stringify(existing)!==JSON.stringify(budget)) throw Error('Ledger approval or cap changed');
    const append=async (entry:unknown)=>{
      const pending=new TextEncoder().encode(JSON.stringify(entry)+'\n');
      for (let written=0;written<pending.length;) written+=await file.write(pending.subarray(written));
      await file.syncData();
    };
    if (!existing) await append(budget);
    const events=entries.filter(entry=>['search','metadata','stream','oauth','completion'].includes(entry.stage)) as Event[];
    const proofs=new Set<string>(entries.filter(entry=>entry.stage==='verified').map(entry=>entry.digest));
    let extraTokens=entries.filter(entry=>entry.stage==='usage').reduce((n,entry)=>n+entry.extraTokens,0);
    const record=async (stage:Event['stage'],tokens=0)=>{
      const group=(s:Event['stage'])=>s==='completion'||s==='oauth' ? s : 'audius';
      const key=group(stage);
      if (events.filter(event=>group(event.stage)===key).length>=budget[key] ||
          events.reduce((sum,event)=>sum+event.tokens,extraTokens)+tokens>budget.tokens) {
        throw Error('Approved provider budget exhausted');
      }
      const event={stage,at:Date.now(),tokens};
      await append(event);
      events.push(event);
    };
    const lastAudiusAt=events.findLast(event=>['search','metadata','stream'].includes(event.stage))?.at??0;
    return await action({record,lastAudiusAt,
      async usage({observation}) {
        const reserved=events.findLast(event=>event.stage==='completion')?.tokens??0;
        const actual=observation.totalTokens??0;
        const extra=Math.max(0,actual-reserved);
        await append({stage:'usage',at:Date.now(),promptTokens:observation.promptTokens,
          completionTokens:observation.completionTokens,totalTokens:observation.totalTokens,extraTokens:extra});
        extraTokens+=extra;
      },
      async prove(candidate) {
        const hash=await editorialReceiptDigest(candidate);
        await append({stage:'verified',at:Date.now(),digest:hash});
        proofs.add(hash);
      },
      async hasProof(candidate) { return proofs.has(await editorialReceiptDigest(candidate)); },
    });
  } finally { file.close(); }
}

function target(config:Config):FixtureTarget {
  return {url:config.url,marker:config.marker,apiKey:Deno.env.get('BE03_LOCAL_ANON_KEY')??'',
    serviceKey:Deno.env.get('BE03_LOCAL_SERVICE_KEY')??'',editorToken:Deno.env.get('BE03_LOCAL_EDITOR_TOKEN')??''};
}

async function authorize(config:Config) {
  const db=target(config);
  const rpc=isolatedEditorialRpc(db);
  if (!await rpc('editorial_live_target',{p_target:db.marker},true)) throw Error('Isolated target is not armed');
  return {db,rpc};
}

async function reviewInventory(config:Config) {
  const original=validInventory(JSON.parse(await Deno.readTextFile(config.inventory)));
  if (!Array.isArray(config.keepExternal)) throw Error('keepExternal must contain selected SoundScape UUIDs');
  const external=original.tracks.filter(exactExternalForEditorial);
  const retained=external.filter(t=>config.keepExternal.includes(t.id));
  const ready=[2,3].includes(retained.length) && retained.length===config.keepExternal.length;
  return {original,external,retained,ready};
}

function audiusContext(db:FixtureTarget,record:Ledger['record'],lastAudiusAt:number) {
  if (supabaseUrl()!==db.url || secretKey()!==db.serviceKey || !Deno.env.get('AUDIUS_API_KEY')) {
    throw Error('Use the armed local project for the existing Audius limiter and an explicitly allocated API key');
  }
  let last=lastAudiusAt;
  return {...newRequestContext('editorial-live'),
    async paceAudiusRequest() { await wait(Math.max(0,550-(Date.now()-last))); },
    async beforeAudiusRequest(stage:'search'|'metadata'|'stream') {
      await record(stage);
      last=Date.now();
    }};
}

async function collect(config:Config, discoveryOnly=false) {
  checkBudget(config.budget);
  const paths=[config.inventory,config.plan,config.checkpoint,config.ledger].map(path=>resolve(path).toLowerCase());
  if (new Set(paths).size!==paths.length) throw Error('Use separate inventory, plan, checkpoint and ledger files');
  const existing=await Deno.stat(config.plan).catch(error=>{
    if (error instanceof Deno.errors.NotFound) return null;
    throw error;
  });
  if (existing && !discoveryOnly) throw Error('Plan already exists. Validate it or refresh its selected sources.');
  const {original,retained,ready}=await reviewInventory(config);
  if (!ready) {
    throw Error('Choose two or three distinct existing exact external recordings');
  }
  const {db,rpc}=await authorize(config);
  const profile=await rpc('get_my_profile',{});
  const cities=await rpc('list_editorial_cities',{});
  const bound=cities.find((row:{code:string})=>row.code===original.city);
  if (bound?.officialPlaylistId!==original.playlistId || bound?.placeId!==original.placeId ||
      !Number.isSafeInteger(bound.draftRevision)) throw Error('Initialize and verify the existing official draft first');
  const key=Deno.env.get('GIGACHAT_AUTH_KEY');
  const scope=Deno.env.get('GIGACHAT_SCOPE')??'GIGACHAT_API_PERS';
  if (!discoveryOnly && (!key || !['GIGACHAT_API_PERS','GIGACHAT_API_B2B','GIGACHAT_API_CORP'].includes(scope))) {
    throw Error('GigaChat credentials are unavailable');
  }
  return await withLedger(config.ledger,config.budget,async ({record,lastAudiusAt,usage,prove,hasProof})=>{
    const ctx=audiusContext(db,record,lastAudiusAt);
    const client=openGigaClient();
    let token:{token:string;expiresAt:number}|undefined;
    const complete=async (input:unknown,tokenReservation:number)=>{
      const lease=await reserve('GENERATE',profile.id,ctx);
      try {
        if (!token || !Number.isFinite(token.expiresAt) || token.expiresAt<Date.now()+30_000) {
          await record('oauth');
          token=await gigaOAuth(client,key!,scope,AbortSignal.timeout(7000),ctx);
        }
        await record('completion',tokenReservation);
        const response=await gigaCompletionResponse(client,token.token,input,AbortSignal.timeout(25000),20000);
        if (!response.ok) {
          await response.body?.cancel();
          throw providerHttpFailure('GIGACHAT','generation',response.status,response.headers.get('retry-after'),ctx);
        }
        const raw=await response.json();
        const decoded=decodeEditorialCompletion(raw);
        await usage(decoded);
        console.log(JSON.stringify({event:'editorial_model',...decoded.observation,parserFailure:decoded.parserFailure}));
        return raw;
      } finally { await release(lease,ctx); }
    };
    const verified=new Map<string,VerifiedCandidate>();
    try {
      const saved=JSON.parse(await Deno.readTextFile(config.checkpoint).catch(error=>{
        if (error instanceof Deno.errors.NotFound) return 'null';
        throw error;
      }));
      if (saved && (saved.playlistId!==original.playlistId || saved.approvalId!==config.budget.approvalId)) {
        throw Error('Checkpoint does not match the approved city');
      }
      for (const item of saved?.verified??[]) {
        if (providerId.test(item.id)&&validDate(item.verifiedAt)&&await hasProof(item)) verified.set(item.id,item);
      }
      const persist=async ()=>{
        const temp=config.checkpoint+'.'+crypto.randomUUID()+'.tmp';
        await Deno.writeTextFile(temp,JSON.stringify({playlistId:original.playlistId,
          approvalId:config.budget.approvalId,verified:[...verified.values()]}),{createNew:true});
        await Deno.rename(temp,config.checkpoint);
      };
      const city=editorialCities.find(item=>item.code===original.city)!;
      let concept:ReturnType<typeof completionConcept>={atmosphere:city.atmosphere,mood:city.mood,
        directions:[...city.directions],queries:[...city.queries]};
      if (discoveryOnly && !config.discoveryQueries?.length) throw Error('Discovery requires explicit search queries');
      if (!config.discoveryQueries) {
        const prompt=editorialPrompt(original.city);
        const {response_format: _format,...conceptRequest}=prompt;
        prompt.messages.push({role:'user',content:JSON.stringify({title:original.title,description:original.description})});
        concept=completionConcept(await complete(conceptRequest,1900));
      } else {
        if (config.discoveryQueries.length>24 || config.discoveryQueries.some(query=>!query.trim() || query.length>160)) {
          throw Error('Invalid editorial discovery queries');
        }
        concept.queries=config.discoveryQueries;
      }
      const pool=new Map<string,AudiusTrack>();
      const search=async (query:string,limit:string)=>{
        try {
          return await audiusList('/search',{query,limit},ctx);
        } catch (error) {
          if (!(error instanceof HttpError) || error.status!==503) throw error;
          await wait(1000);
          return await audiusList('/search',{query,limit},ctx).catch(error=>{
            if (error instanceof HttpError && error.status===503) return [];
            throw error;
          });
        }
      };
      const unretained=original.tracks.filter(t=>!config.keepExternal.includes(t.id));
      for (const old of discoveryOnly?[]:unretained) {
        const results=await search((old.artist+' '+old.title).slice(0,200),'4');
        for (const row of results.slice(0,4)) {
          const item=audiusMetadata(row);
          if ('track' in item && sameRecording(item.track,old) && !item.restricted) pool.set(item.track.id,item.track);
        }
        for (const source of old.sources) if (source.provider==='AUDIUS' && providerId.test(source.provider_track_id)) {
          const metadata=await audiusTrack(source.provider_track_id,ctx);
          if ('track' in metadata && !metadata.restricted) pool.set(metadata.track.id,metadata.track);
        }
      }
      const groups=[];
      for (const query of concept.queries) {
        const results=await search(query,'15');
        groups.push(results.slice(0,15));
      }
      for (let rank=0;rank<15;rank++) {
        for (const group of groups) {
          const row=group[rank];
          if (!row) continue;
          const item=audiusMetadata(row);
          if ('track' in item && !item.restricted &&
              item.track.durationMs<=(config.maxCandidateDurationMs??Infinity)) pool.set(item.track.id,item.track);
        }
      }
      const rejected:{id:string;action:string;reason:string}[]=[];
      for (const candidate of [...pool.values()].slice(0,30)) {
        if (verified.has(candidate.id)) continue;
        if (!permalink.test(candidate.permalink)) {
          rejected.push({id:candidate.id,action:'rejected',reason:'unsupported_exact_permalink'});
          continue;
        }
        const proof=await withAudiusProbe(candidate.id,async ()=>{
          const metadata=await audiusTrack(candidate.id,ctx);
          if ('reason' in metadata) return {reason:metadata.reason} as const;
          if (!permalink.test(metadata.track.permalink)) return {reason:'unsupported_exact_permalink'} as const;
          const stream=await checkAudiusStream(metadata.track,metadata.restricted,ctx);
          if ('reason' in stream) return stream;
          const originalMatch=unretained.filter(old=>sameRecording(metadata.track,old));
          if (originalMatch.length>1) return {reason:'ambiguous_recording'} as const;
          return {...metadata.track,verifiedAt:new Date().toISOString(),canStream:true as const,
            canonicalTrackId:originalMatch[0]?.id};
        },ctx).catch(error=>{
          if (error instanceof HttpError && [502,503].includes(error.status)) return {reason:error.code};
          throw error;
        });
        if ('reason' in proof) { rejected.push({id:candidate.id,action:'rejected',reason:proof.reason??'unverified'}); continue; }
        await prove(proof);
        verified.set(proof.id,proof);
        await persist();
      }
      const unique=new Set<string>();
      const candidates=[...verified.values()].filter(t=>{
        const id=t.canonicalTrackId??t.id;
        if (unique.has(id) || config.keepExternal.includes(id)) return false;
        unique.add(id);
        return true;
      });
      const needed=original.tracks.length-retained.length;
      if (discoveryOnly) return {status:'DISCOVERED',verified:candidates.length,needed,rejected};
      if (candidates.length<needed) return {status:'INSUFFICIENT_VERIFIED_TRACKS',verified:candidates.length,needed,rejected};
      const originalPositions=original.tracks.map(t=>t.id);
      const preserve=candidates.filter(t=>t.canonicalTrackId)
        .sort((a,b)=>originalPositions.indexOf(a.canonicalTrackId!)-originalPositions.indexOf(b.canonicalTrackId!));
      const poolForModel=candidates.filter(t=>!t.canonicalTrackId).slice(0,25);
      const missing=needed-preserve.length;
      let additions:VerifiedCandidate[]=[];
      if (missing>0) {
        const decoded=decodeEditorialCompletion(await complete({model:editorialModel,max_tokens:300,
          messages:[
            {role:'system',content:'Выбери ровно заданное число существующих треков для подборки. Выбирай разных исполнителей. Ответ только JSON с массивом indices из 1-based индексов кандидатов.'},
            {role:'user',content:JSON.stringify({title:original.title,concept,count:missing,candidates:poolForModel.map((t,i)=>({index:i+1,title:t.title.slice(0,90),artist:t.artist.slice(0,60),genre:t.genre}))})},
          ]},4200));
        const indices=(decoded.value as {indices?:number[]}|undefined)?.indices;
        if (decoded.parserFailure || !decoded.observation.actualModel?.startsWith(editorialModel) ||
            !Array.isArray(indices) || indices.length!==missing || new Set(indices).size!==indices.length ||
            indices.some(i=>!Number.isInteger(i)||i<1||i>poolForModel.length)) throw Error('Invalid GigaChat selection');
        additions=indices.map(i=>poolForModel[i-1]);
      }
      const slots:Slot[]=[];
      const decisions:Plan['decision']=[...rejected];
      for (const old of original.tracks) {
        if (config.keepExternal.includes(old.id)) {
          slots.push({keepExternal:old.id});
          decisions.push({id:old.id,action:'retained-external',reason:'exact_existing_source'});
          continue;
        }
        const matched=preserve.find(t=>t.canonicalTrackId===old.id);
        const replacement=matched??additions.shift();
        if (!replacement) throw Error('Missing replacement');
        slots.push({audius:replacement.id});
        if (!matched) decisions.push({id:old.id,action:'removed-from-playlist',reason:'no_selected_fresh_in_app_source'});
        decisions.push({id:replacement.id,action:matched?'same-recording':'added',reason:matched?'verified_recording_identity':'verified_atmospheric_candidate'});
      }
      const plan=validateLivePlan({version:1,city:original.city,placeId:original.placeId,playlistId:original.playlistId,
        expectedRevision:bound.draftRevision,original,slots,verified:candidates.slice(0,45),decision:decisions});
      await Deno.writeTextFile(config.plan,JSON.stringify(plan,null,2),{createNew:true});
      return {status:'READY_FOR_EDITOR',plan:config.plan,positions:slots.length,inApp:needed,external:retained.length};
    } finally { client.close(); }
  });
}

export async function applyLivePlan(plan:Plan,config:Config) {
  validateLivePlan(plan);
  checkBudget(config.budget);
  const {db,rpc}=await authorize(config);
  const draft=await rpc('get_editorial_draft',{placeId:plan.placeId});
  if (config.previewCoverPath &&
      !/^editorial\/covers\/[0-9a-f-]{36}\.png$/.test(config.previewCoverPath)) throw Error('Invalid staging cover path');
  if (draft.playlistId!==plan.playlistId || draft.title!==plan.original.title ||
      draft.description!==(plan.original.description??'') ||
      (draft.coverPath!==plan.original.coverPath && draft.coverPath!==config.previewCoverPath) ||
      ![plan.expectedRevision,plan.expectedRevision+1].includes(draft.revision)) throw Error('Official draft or its revision changed');
  const byProvider=new Map(plan.verified.map(t=>[t.id,t]));
  const tracks=plan.slots.flatMap(slot=>'audius' in slot ? [byProvider.get(slot.audius)!] : []);
  await withLedger(config.ledger,config.budget,async ({hasProof})=>{
    for (const track of tracks) if (!await hasProof(track)) throw Error('Plan has no matching stream receipt in its approved ledger');
  });
  const imported=await rpc('editorial_import_verified_batch',{p_target:db.marker,p_items:tracks},true);
  const ids=new Map<string,string>(imported.map((row:{provider_track_id:string;id:string})=>[row.provider_track_id,row.id]));
  const trackIds=plan.slots.map(slot=>'keepExternal' in slot ? slot.keepExternal : ids.get(slot.audius));
  if (trackIds.some(id=>!id||!databaseUuidRe.test(id)) || new Set(trackIds).size!==trackIds.length) throw Error('Invalid canonical UUID mapping');
  const updated=await rpc('save_editorial_draft',{placeId:plan.placeId,expectedRevision:plan.expectedRevision,
    trackIds,coverPath:config.previewCoverPath});
  return {draft:updated,preflight:await rpc('check_editorial_draft',{placeId:plan.placeId}),decisions:plan.decision};
}

async function verifyLiveDraft(plan:Plan,config:Config) {
  validateLivePlan(plan,Date.now(),true);
  checkBudget(config.budget);
  const {db,rpc}=await authorize(config);
  const draft=await rpc('get_editorial_draft',{placeId:plan.placeId});
  if (draft.playlistId!==plan.playlistId || draft.trackIds.length!==plan.slots.length ||
      draft.revision!==plan.expectedRevision+1) throw Error('Expected the applied staging draft');
  const checks=await withLedger(config.ledger,config.budget,async ({record,lastAudiusAt})=>{
    const ctx=audiusContext(db,record,lastAudiusAt);
    const results=[];
    for (const [index,slot] of plan.slots.entries()) {
      const trackId=draft.trackIds[index];
      const external='keepExternal' in slot;
      if (external && trackId!==slot.keepExternal) throw Error('Unexpected retained track at '+(index+1));
      const source=await resolveTrack(trackId,ctx).catch(async error=>{
        if (!(error instanceof HttpError) || error.status!==503) throw error;
        await wait(1000);
        return await resolveTrack(trackId,ctx);
      });
      if (source.availability!==(external?'EXTERNAL_ONLY':'IN_APP') || source.cacheAllowed || source.downloadAllowed ||
          (!external && source.provider!=='AUDIUS')) throw Error('Unexpected resolver result at '+(index+1));
      results.push({trackId,availability:source.availability,provider:source.provider,
        cacheAllowed:source.cacheAllowed,downloadAllowed:source.downloadAllowed});
    }
    return results;
  });
  return {checks,preflight:await rpc('check_editorial_draft',{placeId:plan.placeId})};
}

async function refreshLivePlan(plan:Plan,config:Config) {
  validateLivePlan(plan,Date.now(),true);
  const {db}=await authorize(config);
  const selected=new Set(plan.slots.flatMap(slot=>'audius' in slot?[slot.audius]:[]));
  return await withLedger(config.ledger,config.budget,async ({record,lastAudiusAt,prove,hasProof})=>{
    const ctx=audiusContext(db,record,lastAudiusAt);
    for (const [index,old] of plan.verified.entries()) {
      if (!selected.has(old.id)) continue;
      if (!await hasProof(old)) throw Error('No matching previous stream receipt in the approved ledger');
      const refreshed=await withAudiusProbe(old.id,async ()=>{
        const current=await audiusTrack(old.id,ctx);
        if ('reason' in current || normalized(current.track.title)!==normalized(old.title) ||
            normalized(current.track.artist)!==normalized(old.artist) || current.track.durationMs!==old.durationMs ||
            current.track.permalink!==old.permalink) throw Error('Selected provider recording changed');
        const stream=await checkAudiusStream(current.track,current.restricted,ctx);
        if ('reason' in stream) throw Error('Selected recording no longer verifies: '+stream.reason);
        return {...old,verifiedAt:new Date().toISOString()};
      },ctx);
      await prove(refreshed);
      plan.verified[index]=refreshed;
      const temp=config.plan+'.'+crypto.randomUUID()+'.tmp';
      await Deno.writeTextFile(temp,JSON.stringify(plan,null,2),{createNew:true});
      await Deno.rename(temp,config.plan);
    }
    validateLivePlan(plan);
    return {status:'REFRESHED',positions:selected.size};
  });
}

async function curateLivePlan(config:Config) {
  const {original,ready}=await reviewInventory(config);
  if (!ready || !config.orderedSelection || !editorialTrackCount(config.orderedSelection.length) ||
      config.keepExternal.some(id=>!config.orderedSelection!.includes(id))) {
    throw Error('Expected 12–15 ordered choices, including all retained external recordings');
  }
  const {rpc}=await authorize(config);
  const draft=await rpc('get_editorial_draft',{placeId:original.placeId});
  if (draft.playlistId!==original.playlistId || draft.approvedRevision || draft.publishedRevision) {
    throw Error('Expected an unapproved existing official draft');
  }
  const saved=JSON.parse(await Deno.readTextFile(config.checkpoint));
  if (saved.playlistId!==original.playlistId || saved.approvalId!==config.budget.approvalId ||
      !Array.isArray(saved.verified)) throw Error('Checkpoint does not match the approved city');
  const slots=config.orderedSelection.map(id=>config.keepExternal.includes(id)?{keepExternal:id}:{audius:id});
  const ids=new Set(slots.flatMap(slot=>'audius' in slot?[slot.audius]:[]));
  const verified:VerifiedCandidate[]=saved.verified.filter((item:VerifiedCandidate)=>ids.has(item.id));
  await withLedger(config.ledger,config.budget,async ({hasProof})=>{
    for (const track of verified) if (!await hasProof(track)) throw Error('Missing matching stream receipt');
  });
  const kept=new Set([...config.keepExternal,...verified.flatMap(track=>track.canonicalTrackId?[track.canonicalTrackId]:[])]);
  const decision=[
    ...original.tracks.filter(t=>!kept.has(t.id)).map(t=>({id:t.id,action:'removed-from-playlist',reason:'curated_replacement'})),
    ...config.keepExternal.map(id=>({id,action:'retained-external',reason:'exact_existing_source'})),
    ...verified.map(t=>({id:t.id,action:t.canonicalTrackId?'same-recording':'added',
      reason:t.canonicalTrackId?'verified_recording_identity':'curated_verified_atmospheric_candidate'})),
  ];
  const plan=validateLivePlan({version:1,city:original.city,placeId:original.placeId,playlistId:original.playlistId,
    expectedRevision:draft.revision,original,slots,verified,decision});
  const temp=config.plan+'.'+crypto.randomUUID()+'.tmp';
  await Deno.writeTextFile(temp,JSON.stringify(plan,null,2),{createNew:true});
  await Deno.rename(temp,config.plan);
  return {status:'READY_FOR_EDITOR',plan:config.plan,positions:slots.length,inApp:ids.size,external:config.keepExternal.length};
}

if (import.meta.main) {
  const [mode,input]=Deno.args;
  if (!input || Deno.args.length!==2 || !['--dry-run','--validate','--collect','--discover','--curate','--refresh','--apply','--verify'].includes(mode)) {
    throw Error('Usage: editorial-live.ts --validate PLAN.json | --dry-run, --collect, --discover, --curate, --refresh, --apply or --verify CONFIG.json');
  }
  const raw=JSON.parse(await Deno.readTextFile(input));
  const result=mode==='--dry-run' ? await reviewInventory(raw) :
    mode==='--validate' ? validateLivePlan(raw) : mode==='--collect' ? await collect(raw) :
    mode==='--discover' ? await collect(raw,true) :
    mode==='--curate' ? await curateLivePlan(raw) :
    mode==='--refresh' ? await refreshLivePlan(JSON.parse(await Deno.readTextFile(raw.plan)),raw) :
    mode==='--verify' ? await verifyLiveDraft(JSON.parse(await Deno.readTextFile(raw.plan)),raw) :
      await applyLivePlan(JSON.parse(await Deno.readTextFile(raw.plan)),raw);
  console.log(JSON.stringify(result,null,2));
}
