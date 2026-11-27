import { editorialReceiptDigest, validateLivePlan } from './editorial-live.ts';
import { buildRestoreSql } from './editorial-snapshot.ts';
import { sha256 } from '../supabase/functions/_shared/core.ts';

const files=['catalog.json','credits.json','london-plan.json','london-resolver.json',
  'provider-ledger.jsonl','tokyo-plan.json','tokyo-resolver.json'] as const;
const cities=['london','tokyo'] as const;

function check(condition:unknown,message:string):asserts condition {
  if (!condition) throw Error(message);
}

export function matchingEditorialCredit(credit:{provider:string;providerTrackId:string;uploadAccount:string;
  providerTitle:string;recordingArtist:string;recordingTitle:string;label:string;evidence:string},
  raw:{id:string;artist:string;title:string}|undefined) {
  return Boolean(credit.provider==='AUDIUS' && raw && raw.id===credit.providerTrackId &&
    raw.artist===credit.uploadAccount && raw.title===credit.providerTitle &&
    credit.recordingArtist.trim() && credit.recordingTitle.trim() &&
    credit.label.trim() && /^https:\/\/[a-z0-9.-]+\/[A-Za-z0-9/_-]+$/.test(credit.evidence));
}

async function readArchive(directory:string) {
  const values=await Promise.all(files.map(async name=>[name,(await Deno.readTextFile(directory+'/'+name)).replaceAll('\r\n','\n')] as const));
  const contents=Object.fromEntries(values);
  const hashes=Object.fromEntries(await Promise.all(values.map(async ([name,value])=>[name,await sha256(value)])));
  return {contents,hashes};
}

async function validateArchive(contents:Awaited<ReturnType<typeof readArchive>>['contents']) {
  const catalog=JSON.parse(contents['catalog.json']);
  const ledger=contents['provider-ledger.jsonl'].trim().split('\n').map(line=>JSON.parse(line));
  const [budget,...events]=ledger;
  const providerCount=(...stages:string[])=>events.filter(row=>stages.includes(row.stage)).length;
  const audius=providerCount('search','metadata','stream'), oauth=providerCount('oauth'), completion=providerCount('completion');
  check(audius===498 && oauth===4 && completion===4 && audius<=budget.audius &&
    oauth<=budget.oauth && completion<=budget.completion,'Archive ledger does not match the closed pilot');
  check(catalog.drafts.length===2 && catalog.playlists.length===2 && catalog.places.length===2,'Invalid pilot roster');
  const identities=new Map<string,string>(catalog.audius_artist_identity.map(
    (row:{provider_artist_id:string;artist_id:string})=>[row.provider_artist_id,row.artist_id]));
  const primaryCredits=new Map<string,string>(catalog.track_artists
    .filter((row:{credit_order:number})=>row.credit_order===1)
    .map((row:{track_id:string;artist_id:string})=>[row.track_id,row.artist_id]));
  const results=[];
  const recordedSources=[];
  const proofs=new Set(events.filter(row=>row.stage==='verified').map(row=>row.digest));
  for (const city of cities) {
    const plan=validateLivePlan(JSON.parse(contents[`${city}-plan.json`]),Date.now(),true);
    const {checks}=JSON.parse(contents[`${city}-resolver.json`]);
    const draft=catalog.drafts.find((row:{city_code:string})=>row.city_code===city);
    const playlist=catalog.playlists.find((row:{id:string})=>row.id===plan.playlistId);
    check(draft?.playlist_id===plan.playlistId && draft.place_id===plan.placeId && draft.track_ids.length===13 &&
      new Set(draft.track_ids).size===13 && draft.revision===3 && draft.title===plan.original.title &&
      draft.cover_path===plan.original.coverPath && playlist?.cover_path===draft.cover_path &&
      playlist.place_id===draft.place_id && playlist.visibility==='PRIVATE' && playlist.published_at===null &&
      !('owner_id' in playlist) && !('approved_by' in draft),'Invalid archived draft for '+city);
    const sourceByTrack=new Map(catalog.track_sources.map((row:any)=>[row.track_id,row])) as
      Map<string,{provider:string;provider_track_id:string;external_url:string;verified_at:string;can_stream:boolean;can_cache:boolean;can_download:boolean}>;
    check(sourceByTrack.size===catalog.track_sources.length,'Ambiguous source mapping in pilot archive');
    const selectedChecks=[checks[1],checks[0],...checks.slice(2)];
    check(selectedChecks.length===13,'Missing resolver checks');
    let inApp=0;
    for (const [position,slot] of [plan.slots[1],plan.slots[0],...plan.slots.slice(2)].entries()) {
      const id=draft.track_ids[position];
      const result=selectedChecks[position];
      const source=sourceByTrack.get(id);
      check(id===result.trackId && source && result.provider===source.provider &&
        !source.can_cache && !source.can_download && !result.cacheAllowed && !result.downloadAllowed,
        'Archived resolver or source mismatch');
      if ('audius' in slot) {
        const metadata=plan.verified.find(row=>row.id===slot.audius);
        check(metadata && metadata.artistId && identities.has(metadata.artistId) &&
          identities.get(metadata.artistId)===primaryCredits.get(id) &&
          proofs.has(await editorialReceiptDigest(metadata)) &&
          source.provider==='AUDIUS' && source.can_stream && result.availability==='IN_APP' &&
          source.provider_track_id===metadata.id && source.external_url===metadata.permalink &&
          Date.parse(source.verified_at)>=Date.parse(metadata.verifiedAt),'Missing historical Audius evidence');
        recordedSources.push(metadata);
        inApp++;
      } else {
        const metadata=plan.original.tracks.find(row=>row.id===slot.keepExternal);
        const exactSource=metadata?.sources.find(row=>row.provider===source.provider &&
          row.provider_track_id===source.provider_track_id && row.external_url===source.external_url);
        check(position>0 && id===slot.keepExternal && source.provider==='APPLE_MUSIC' &&
          exactSource && !source.can_stream && result.availability==='EXTERNAL_ONLY','Unexpected Apple Music source');
      }
    }
    check(inApp===10,'Expected 10 historical IN_APP sources per city');
    results.push({city,placeId:draft.place_id,playlistId:draft.playlist_id,revision:draft.revision,
      productionCoverPath:draft.cover_path,trackIds:draft.track_ids,inAppAtPilot:inApp,externalOnlyAtPilot:13-inApp});
  }
  for (const credit of JSON.parse(contents['credits.json'])) {
    const raw=recordedSources.find(row=>row.id===credit.providerTrackId);
    check(matchingEditorialCredit(credit,raw),
      'Credits require the exact unaltered provider record and independent release evidence');
  }
  return {catalog,manifest:{version:1,evidence:'HISTORICAL_PILOT',cities:results,usage:{audius,oauth,completion}}};
}

if (import.meta.main) {
  const [mode,directory,marker]=Deno.args;
  check(directory && ['--manifest','--verify','--restore-sql'].includes(mode),'Specify archive path and mode');
  const {contents,hashes}=await readArchive(directory);
  const {catalog,manifest}=await validateArchive(contents);
  const expected=JSON.stringify({...manifest,sha256:hashes},null,2);
  if (mode==='--manifest') console.log(expected);
  else {
    check((await Deno.readTextFile(directory+'/manifest.json')).replaceAll('\r\n','\n').trim()===expected,
      'Archive checksum or manifest mismatch');
    if (mode==='--verify') console.log('Verified historical archive for 2 cities and 26 ordered positions');
    else {
      const seed=buildRestoreSql({...catalog,covers:[]},marker);
      const drafts=JSON.stringify(catalog.drafts).replaceAll("'","''");
      const artistIdentities=JSON.stringify(catalog.audius_artist_identity).replaceAll("'","''");
      console.log(seed.replace(/\bCOMMIT;$/,()=>String.raw`
DO $close$
BEGIN
  IF public.editorial_live_target('${marker}') THEN RAISE EXCEPTION 'Use offline staging'; END IF;
END
$close$;
INSERT INTO internal.audius_artist_identity
SELECT * FROM jsonb_populate_recordset(NULL::internal.audius_artist_identity,'${artistIdentities}'::jsonb)
ON CONFLICT DO NOTHING;
DO $close$
BEGIN
  IF EXISTS (SELECT * FROM jsonb_populate_recordset(NULL::internal.audius_artist_identity,'${artistIdentities}'::jsonb)
             EXCEPT SELECT * FROM internal.audius_artist_identity) THEN RAISE EXCEPTION 'Audius artist archive conflict'; END IF;
END
$close$;
INSERT INTO internal.editorial_drafts (city_code,place_id,playlist_id,revision,title,description,cover_path,genre_ids,tag_ids,track_ids)
SELECT city_code,place_id,playlist_id,revision,title,description,cover_path,genre_ids,tag_ids,track_ids
FROM jsonb_populate_recordset(NULL::internal.editorial_drafts,'${drafts}'::jsonb)
ON CONFLICT (place_id) DO NOTHING;
DO $close$
BEGIN
  IF EXISTS (SELECT * FROM jsonb_populate_recordset(NULL::internal.editorial_drafts,'${drafts}'::jsonb)
             EXCEPT SELECT * FROM internal.editorial_drafts) THEN RAISE EXCEPTION 'Editorial draft archive conflict'; END IF;
END
$close$;
COMMIT;`));
    }
  }
}
