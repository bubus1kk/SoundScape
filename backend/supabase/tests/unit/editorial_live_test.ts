import assert from 'node:assert/strict';
import { exactExternalForEditorial, sameRecording, validateLivePlan } from '../../../scripts/editorial-live.ts';
import { databaseUuid, uuid } from '../../functions/_shared/core.ts';
import { buildRestoreSql, pilotBundle } from '../../../scripts/editorial-snapshot.ts';
import { matchingEditorialCredit } from '../../../scripts/editorial-archive.ts';

Deno.test('BE-03 archive distinguishes confirmed recording credits from the uploader',()=>{
  const raw={id:'Ax9d0',artist:'Critical Music',title:'Coco Bryce - U I Luv'};
  const credit={provider:'AUDIUS',providerTrackId:raw.id,uploadAccount:raw.artist,providerTitle:raw.title,
    recordingArtist:'Coco Bryce',recordingTitle:'U I Luv',label:'Critical Music',
    evidence:'https://criticalmusic.bandcamp.com/track/u-i-luv'};
  assert.equal(matchingEditorialCredit(credit,raw),true);
  assert.equal(matchingEditorialCredit({...credit,evidence:''},raw),false);
  assert.equal(matchingEditorialCredit({...credit,providerTrackId:'Other'},raw),false);
  assert.deepEqual(raw,{id:'Ax9d0',artist:'Critical Music',title:'Coco Bryce - U I Luv'});
});

Deno.test('BE-03 live pilot restore includes only Tokyo and London and their dependent rows',()=>{
  const places=[
    {id:'p1',country_code:'JP',title:'Токио'}, {id:'p2',country_code:'GB',title:'Лондон'},
    {id:'p3',country_code:'FR',title:'Париж'},
  ];
  const bundle={places,playlists:places.map((p,i)=>({id:'pl'+i,place_id:p.id,cover_path:'cover'+i})),
    playlist_tracks:[{playlist_id:'pl0',track_id:'t1'},{playlist_id:'pl1',track_id:'t2'},{playlist_id:'pl2',track_id:'t3'}],
    covers:[0,1,2].map(i=>({name:'cover'+i,size:10,mimetype:'image/png'})),
    tracks:[{id:'t1'},{id:'t2'},{id:'t3'}],track_sources:[{track_id:'t2'},{track_id:'t3'}],
    track_artists:[{track_id:'t1',artist_id:'a1'},{track_id:'t3',artist_id:'a3'}],
    artists:[{id:'a1'},{id:'a3'}],genres:[{id:1},{id:3}],moods:[],tags:[],
    playlist_genres:[{playlist_id:'pl0',genre_id:1},{playlist_id:'pl2',genre_id:3}],
    playlist_tags:[],track_moods:[],track_genres:[]};
  const pilot=pilotBundle(bundle);
  assert.deepEqual(pilot.places.map(p=>p.id),['p1','p2']);
  assert.deepEqual(pilot.tracks.map(t=>t.id),['t1','t2']);
  assert.deepEqual(pilot.artists.map(a=>a.id),['a1']);
  assert.deepEqual(pilot.genres.map(g=>g.id),[1]);
  assert.deepEqual(pilot.covers.map(c=>c.name),['cover0','cover1']);
  assert.match(buildRestoreSql(pilot,crypto.randomUUID()),/isolated restore disabled/);
});

function appleSource(id='12345') {
  return {provider:'APPLE_MUSIC',provider_track_id:id,
    external_url:'https://music.apple.com/us/album/record/1234?i=12345&uo=4',
    verified_at:'2025-01-01T12:00:00.000Z',can_stream:false};
}

const original={id:crypto.randomUUID(),title:'Track',artist:'Artist',durationMs:180000,
  isrc:'USAAA2400001',sources:[appleSource()]};
const audius={id:'QaRecord',title:'Track',artist:'Artist',durationMs:180500,
  isrc:'USAAA2400001',permalink:'https://audius.co/example/track'};

Deno.test('BE-03-FIX accepts historical catalog UUIDs while keeping the Auth UUID validator strict',()=>{
  const catalogId='91000000-aaaa-c000-7000-000000000001';
  assert.equal(databaseUuid(catalogId.toUpperCase(),'trackId'),catalogId);
  assert.throws(()=>databaseUuid(catalogId+',other','trackId'),/Invalid trackId/);
  assert.throws(()=>uuid(catalogId),/Invalid id/);
});

Deno.test('BE-03-FIX keeps a canonical UUID only with matching recording evidence',()=>{
  assert.equal(sameRecording(audius,original),true);
  assert.equal(sameRecording({...audius,isrc:undefined},original),false);
  assert.equal(sameRecording({...audius,title:'Track (live)'},original),false);
  assert.equal(sameRecording({...audius,artist:'Different artist'},original),false);
  assert.equal(sameRecording({...audius,durationMs:188000},original),false);
});

Deno.test('BE-03-FIX external proof validates the exact Apple Music catalog ID',()=>{
  assert.equal(exactExternalForEditorial(original),true);
  assert.equal(exactExternalForEditorial({...original,sources:[appleSource('98765')]}),false);
  assert.equal(exactExternalForEditorial({...original,sources:[{...appleSource(),external_url:'https://music.apple.com/us/search?term=track'}]}),false);
  assert.equal(exactExternalForEditorial({...original,sources:[{...appleSource(),verified_at:null}]}),false);
});

Deno.test('BE-03-FIX independently validates ratio, fresh timestamps and canonical uniqueness',()=>{
  const now=Date.now();
  const inventory={city:'tokyo' as const,placeId:crypto.randomUUID(),playlistId:crypto.randomUUID(),
    title:'Existing title',description:'Existing description',coverPath:'editorial/capitals/v1/tokyo-qa.png',
    tracks:Array.from({length:13},()=>({...original,id:crypto.randomUUID()}))};
  const verified=Array.from({length:11},(_,i)=>({...audius,id:'QaProvider-'+i,canStream:true as const,
    verifiedAt:new Date(now-1000).toISOString()}));
  const slots=[...verified.map(row=>({audius:row.id})),...inventory.tracks.slice(11).map(row=>({keepExternal:row.id}))];
  const plan={version:1 as const,city:inventory.city,placeId:inventory.placeId,playlistId:inventory.playlistId,
    expectedRevision:1,original:inventory,slots,verified,decision:[]};
  assert.equal(validateLivePlan(plan,now).slots.length,13);
  assert.equal(validateLivePlan({...plan,slots:slots.slice(1)},now).slots.length,12);
  assert.throws(()=>validateLivePlan({...plan,slots:slots.slice(2)},now),/Invalid editorial plan/);
  const extra=verified.slice(0,3).map(track=>({...track,id:track.id+'Extra'}));
  const expanded={...plan,verified:[...verified,...extra],slots:[...slots,...extra.map(t=>({audius:t.id}))]};
  assert.equal(validateLivePlan({...expanded,slots:expanded.slots.slice(0,-1)},now).slots.length,15);
  assert.throws(()=>validateLivePlan(expanded,now),/Invalid editorial plan/);
  assert.throws(()=>validateLivePlan({...plan,slots:[...slots.slice(0,-1),slots.at(-2)!]},now),/Duplicate/);
  assert.throws(()=>validateLivePlan({...plan,verified:verified.map(track=>({...track,verifiedAt:new Date(now-7200000).toISOString()}))},now),/Fresh/);
  assert.throws(()=>validateLivePlan({...plan,slots:[...slots.slice(0,-3),...inventory.tracks.slice(0,3).map(row=>({keepExternal:row.id}))],
    verified:verified.map((item,i)=>i===0?{...item,canonicalTrackId:inventory.tracks[0].id}:item)},now),/Duplicate/);
});
