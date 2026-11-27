import { editorialCities } from './editorial-cities.ts';
import { isolatedEditorialRpc } from './editorial.ts';
import { matchesImage } from '../supabase/functions/_shared/image.ts';
import { uuidRe } from '../supabase/functions/_shared/core.ts';

const tables=['places','genres','moods','tags','artists','tracks','track_artists','track_sources',
  'track_genres','track_moods','playlists','playlist_tracks','playlist_genres','playlist_tags'] as const;
const coverPattern=/^editorial\/capitals\/v1\/[a-z0-9-]+\.png$/;
const sourceUrl='https://spgawlqdkzoxgrncunxg.supabase.co';
type Row=Record<string,any>;
type Bundle=Record<typeof tables[number],Row[]> & {covers:{name:string;size:number;mimetype:string}[]};

async function readBundle(directory:string):Promise<Bundle> {
  const entries=await Promise.all([...tables,'covers'].map(async table=>{
    const rows=JSON.parse(await Deno.readTextFile(directory+'/'+table+'.json'));
    if (!Array.isArray(rows)) throw Error('Invalid bundle table');
    return [table,rows];
  }));
  const bundle=Object.fromEntries(entries) as Bundle;
  if (bundle.places.length!==20 || bundle.covers.length!==20 || bundle.playlists.length!==20 ||
      bundle.playlists.some(p=>!p.is_editorial || p.visibility!=='PRIVATE' || p.published_at!==null ||
        'owner_id' in p || !bundle.covers.some(c=>c.name===p.cover_path) ||
        bundle.playlist_tracks.filter(t=>t.playlist_id===p.id).length!==13) ||
      bundle.covers.some(c=>!coverPattern.test(c.name)||c.mimetype!=='image/png'||!Number.isInteger(c.size)||
        c.size<=0||c.size>10*1024*1024)) throw Error('Invalid legacy editorial bundle');
  return bundle;
}

export function buildRestoreSql(bundle:Bundle, marker:string) {
  if (!uuidRe.test(marker)) throw Error('Isolated marker UUID required');
  const checks=[
    "SELECT 1 FROM internal.editorial_fixture_target WHERE marker='"+marker+"'::uuid",
  ];
  const guard="DO $$ BEGIN IF NOT EXISTS("+checks[0]+") THEN RAISE EXCEPTION 'BE-03 isolated restore disabled'; END IF; END $$;";
  const blocks=bundle.playlists.map(p=>p.id);
  if (![2,20].includes(blocks.length) || new Set(blocks).size!==blocks.length) throw Error('Invalid legacy roster');
  return ['BEGIN;',guard,...tables.map(table=>{
    const hex=Array.from(new TextEncoder().encode(JSON.stringify(bundle[table])),
      byte=>byte.toString(16).padStart(2,'0')).join('');
    const payload="convert_from(decode('"+hex+"','hex'),'UTF8')";
    // Explicit conflict check prevents an existing conflicting row from looking like a successful replay.
    return `INSERT INTO public.${table} SELECT * FROM jsonb_populate_recordset(NULL::public.${table},${payload}::jsonb)
      ON CONFLICT DO NOTHING;
    DO $restore$ BEGIN
      IF EXISTS(SELECT * FROM jsonb_populate_recordset(NULL::public.${table},${payload}::jsonb)
                EXCEPT SELECT * FROM public.${table}) THEN RAISE EXCEPTION 'Editorial restore conflict in ${table}'; END IF;
    END $restore$;`;
  }), ...['genres','moods','tags'].map(table=>
    `SELECT setval(pg_get_serial_sequence('public.${table}','id'),
      coalesce((SELECT max(id) FROM public.${table}),1),EXISTS(SELECT FROM public.${table}));`),
  'COMMIT;'].join('\n');
}

export function pilotBundle(bundle:Bundle):Bundle {
  const places=bundle.places.filter(place=>['JP:Токио','GB:Лондон'].includes(place.country_code+':'+place.title));
  const placeIds=new Set(places.map(p=>p.id));
  const playlists=bundle.playlists.filter(p=>placeIds.has(p.place_id));
  if (places.length!==2 || playlists.length!==2) throw Error('Expected only the existing Tokyo and London playlists');
  const playlistIds=new Set(playlists.map(p=>p.id));
  const playlist_tracks=bundle.playlist_tracks.filter(t=>playlistIds.has(t.playlist_id));
  const playlist_genres=bundle.playlist_genres.filter(t=>playlistIds.has(t.playlist_id));
  const playlist_tags=bundle.playlist_tags.filter(t=>playlistIds.has(t.playlist_id));
  const trackIds=new Set(playlist_tracks.map(row=>row.track_id));
  const track_artists=bundle.track_artists.filter(t=>trackIds.has(t.track_id));
  const track_genres=bundle.track_genres.filter(t=>trackIds.has(t.track_id));
  const track_moods=bundle.track_moods.filter(t=>trackIds.has(t.track_id));
  const artistIds=new Set(track_artists.map(t=>t.artist_id));
  const genreIds=new Set([...playlist_genres,...track_genres].map(t=>t.genre_id));
  const moodIds=new Set(track_moods.map(t=>t.mood_id));
  const tagIds=new Set(playlist_tags.map(t=>t.tag_id));
  const coverPaths=new Set(playlists.map(p=>p.cover_path));
  return {places,playlists,playlist_tracks,playlist_genres,playlist_tags,track_artists,track_genres,track_moods,
    tracks:bundle.tracks.filter(t=>trackIds.has(t.id)),track_sources:bundle.track_sources.filter(t=>trackIds.has(t.track_id)),
    artists:bundle.artists.filter(a=>artistIds.has(a.id)),genres:bundle.genres.filter(g=>genreIds.has(g.id)),
    moods:bundle.moods.filter(m=>moodIds.has(m.id)),tags:bundle.tags.filter(t=>tagIds.has(t.id)),
    covers:bundle.covers.filter(c=>coverPaths.has(c.name))};
}

function inventory(bundle:Bundle,code:string) {
  const city=editorialCities.find(c=>c.code===code);
  if (!city) throw Error('Invalid city');
  const place=bundle.places.find(p=>p.country_code===city.country && p.title===city.city);
  const pl=bundle.playlists.find(p=>p.place_id===place?.id);
  if (!place||!pl) throw Error('Missing original city playlist');
  const tracks=bundle.playlist_tracks.filter(t=>t.playlist_id===pl.id).sort((a,b)=>a.position-b.position)
    .map(rel=>{
      const t=bundle.tracks.find(t=>t.id===rel.track_id)!;
      const artists=bundle.track_artists.filter(a=>a.track_id===t.id).sort((a,b)=>a.credit_order-b.credit_order);
      const sources=bundle.track_sources.filter(s=>s.track_id===t.id);
      return {id:t.id,title:t.title,artist:bundle.artists.find(a=>a.id===artists[0]?.artist_id)?.name??'',
        artists:artists.map(a=>bundle.artists.find(row=>row.id===a.artist_id)?.name),
        durationMs:t.duration_ms,isrc:t.isrc??undefined,recordingMbid:t.musicbrainz_recording_id??undefined,sources};
    });
  return {city:code,placeId:place.id,playlistId:pl.id,title:pl.title,description:pl.description,
    coverPath:pl.cover_path,revision:pl.revision,visibility:pl.visibility,publishedAt:pl.published_at,
    genreIds:bundle.playlist_genres.filter(g=>g.playlist_id===pl.id).map(g=>g.genre_id),
    tagIds:bundle.playlist_tags.filter(g=>g.playlist_id===pl.id).map(g=>g.tag_id),tracks};
}

async function covers(bundle:Bundle,directory:string,mode:string,target?:string,marker?:string) {
  if (mode==='--upload-covers') {
    const fixture={url:target??'',marker:marker??'',serviceKey:Deno.env.get('BE03_LOCAL_SERVICE_KEY')??'',
      apiKey:Deno.env.get('BE03_LOCAL_ANON_KEY')??'',editorToken:Deno.env.get('BE03_LOCAL_EDITOR_TOKEN')??''};
    const rpc=isolatedEditorialRpc(fixture);
    // A marker lookup also permits a fixture target that is intentionally not armed for providers.
    if (!await rpc('editorial_snapshot_target',{p_target:fixture.marker},true)) throw Error('Isolated restore disabled');
  }
  const key=Deno.env.get(mode==='--download-covers'?'BE03_SOURCE_ANON_KEY':'BE03_LOCAL_SERVICE_KEY');
  const token=Deno.env.get(mode==='--download-covers'?'BE03_SOURCE_EDITOR_TOKEN':'BE03_LOCAL_SERVICE_KEY');
  if (!key||!token) throw Error('Storage credentials are unavailable');
  const local=directory+'/covers';
  if (mode==='--download-covers') await Deno.mkdir(local,{recursive:true});
  for (const cover of bundle.covers) {
    const file=local+'/'+cover.name.split('/').at(-1);
    const path='/storage/v1/object/';
    const download=mode==='--download-covers';
    const headers={apikey:key,Authorization:'Bearer '+token};
    const checked=(bytes:Uint8Array)=>bytes.length===cover.size && matchesImage(cover.mimetype,bytes);
    if (download) {
      const response=await fetch(sourceUrl+path+'authenticated/soundscape-media/'+cover.name,
        {headers,redirect:'error',signal:AbortSignal.timeout(15000)});
      if (!response.ok) throw Error('Production cover download HTTP '+response.status);
      const bytes=new Uint8Array(await response.arrayBuffer());
      if (!checked(bytes)) throw Error('Cover differs from inventory');
      await Deno.writeFile(file,bytes);
    } else {
      const bytes=await Deno.readFile(file);
      if (!checked(bytes)) throw Error('Cover differs from inventory');
      const result=await fetch(target+path+'soundscape-media/'+cover.name,{method:'POST',
        headers:{...headers,'content-type':cover.mimetype,'x-upsert':'false'},body:bytes,
        redirect:'error',signal:AbortSignal.timeout(15000)});
      if (!result.ok) {
        await result.body?.cancel();
        const stored=await fetch(target+path+'authenticated/soundscape-media/'+cover.name,{headers,redirect:'error',
          signal:AbortSignal.timeout(15000)});
        if (!stored.ok) throw Error('Staging cover upload failed');
        const existing=new Uint8Array(await stored.arrayBuffer());
        if (existing.length!==bytes.length || !existing.every((byte,index)=>bytes[index]===byte)) {
          throw Error('A different staged cover already uses the official path');
        }
      } else await result.body?.cancel();
    }
  }
}

if (import.meta.main) {
  const [mode,directory,arg,marker]=Deno.args;
  if (!directory) throw Error('Specify the ignored editorial bundle directory');
  const bundle=await readBundle(directory);
  if (mode==='--inventory') console.log(JSON.stringify(inventory(bundle,arg),null,2));
  else if (mode==='--inventory-all') console.log(JSON.stringify(editorialCities.map(city=>inventory(bundle,city.code)),null,2));
  else if (mode==='--restore-sql') console.log(buildRestoreSql(bundle,arg));
  else if (mode==='--restore-pilot-sql') console.log(buildRestoreSql(pilotBundle(bundle),arg));
  else if (mode==='--download-pilot-covers') await covers(pilotBundle(bundle),directory,'--download-covers');
  else if (mode==='--upload-pilot-covers') await covers(pilotBundle(bundle),directory,'--upload-covers',arg,marker);
  else if (mode==='--download-covers'||mode==='--upload-covers') await covers(bundle,directory,mode,arg,marker);
  else throw Error('Use an inventory, restore or covers mode with the local bundle directory');
}
