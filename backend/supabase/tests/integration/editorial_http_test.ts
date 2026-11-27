import { applyEditorialFixture, prepareEditorialManifest } from '../../../scripts/editorial.ts';
import { editorialCapture } from '../fixtures/editorial_capture.ts';

function env(key: string) {
  return Deno.permissions.querySync({ name:'env', variable:key }).state === 'granted' ? Deno.env.get(key) : undefined;
}
const port = env('BE02_LOCAL_PORT');
const apiKey = env('BE02_LOCAL_ANON_KEY');
const serviceKey = env('BE02_LOCAL_SERVICE_KEY');
const marker = env('BE03_FIXTURE_TARGET');
const enabled = /^\d{4,5}$/.test(port ?? '') && !!apiKey && !!serviceKey && !!marker;
const png = Uint8Array.from(atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg=='), c => c.charCodeAt(0));
const base = 'http://127.0.0.1:' + port;

function equal(actual: unknown, expected: unknown, label: string) {
  if (actual !== expected) throw Error(label + ': expected ' + expected + ', got ' + actual);
}

async function request(path: string, status: number | number[], options: {
  method?: string; token?: string; service?: boolean; data?: unknown; image?: Uint8Array<ArrayBuffer>;
  headers?: Record<string,string>;
} = {}) {
  const token = options.service ? serviceKey : options.token;
  const response = await fetch(base + path,{
    method:options.method ?? 'POST', redirect:'error', signal:AbortSignal.timeout(20_000),
    headers:{ apikey:(options.service ? serviceKey : apiKey)!, 'content-type':options.image ? 'image/png' : 'application/json',
      ...(token ? {authorization:'Bearer ' + token} : {}), ...options.headers },
    body:options.image ?? (options.data === undefined ? undefined : JSON.stringify(options.data)),
  });
  const accepted = Array.isArray(status) ? status : [status];
  if (!accepted.includes(response.status)) {
    await response.body?.cancel();
    throw Error((options.method ?? 'POST') + ' ' + path.split('?')[0] +
      ': expected HTTP ' + accepted.join('/') + ', got ' + response.status);
  }
  if (response.headers.get('content-type')?.includes('application/json')) return await response.json();
  const bytes = await response.arrayBuffer();
  return { bytes: bytes.byteLength };
}
const rpc = (name: string, data: unknown, status = 200, token?: string) =>
  request('/rest/v1/rpc/' + name,status,{ data,token });

async function account(role: 'USER'|'ADMIN'|'EDITOR', owner = false) {
  const auth = {email:'be03-qa-' + crypto.randomUUID() + '@example.invalid', password:crypto.randomUUID()};
  const user = await request('/auth/v1/admin/users',200,{service:true,
    data:{...auth,email_confirm:true,app_metadata:owner ? {soundscape_editorial_owner:true} : {}}});
  if (role !== 'USER') {
    await request('/rest/v1/profiles?id=eq.' + user.id,204,{method:'PATCH',service:true,data:{role_code:role}});
  }
  const session = await request('/auth/v1/token?grant_type=password',200,{data:auth});
  return {id:user.id,token:session.access_token as string};
}

Deno.test({name:'BE-03 controlled Tokyo and London import, editorial approval, guest and map',ignore:!enabled,async fn() {
  const editor = (await account('EDITOR')).token;
  const publisher = await account('ADMIN',true);
  const owner = publisher.token;
  const alternateOwner = (await account('ADMIN',true)).token;
  const admin = (await account('ADMIN')).token;
  const ordinary = (await account('USER')).token;
  const tokenless = await rpc('save_editorial_draft',{},401);
  equal(tokenless.message,'AUTH_REQUIRED','Guest create rejected');
  const cities = await rpc('list_editorial_cities',{},200,editor);
  equal(cities.length,20,'Approved city count');
  const genreId = 32199;
  const tagId = 32199;
  for (const table of ['genres','tags']) {
    await request('/rest/v1/' + table,201,{service:true,
      headers:{Prefer:'resolution=merge-duplicates'}, data:{id:table==='genres' ? genreId : tagId,name:'BE-03 QA '+table}});
  }
  for (const [code,title,country,coords] of [
    ['tokyo','Токио','JP',[139.69,35.69]],
    ['london','Лондон','GB',[-0.13,51.51]],
  ] as const) {
    const currentCity = cities.find((item: {code:string}) => item.code===code);
    if (currentCity.binding !== 'MISSING' && currentCity.binding !== 'BOUND') throw Error('City is ambiguous');
    const placeId = currentCity.placeId ?? crypto.randomUUID();
    if (currentCity.binding === 'MISSING') {
      await request('/rest/v1/places',201,{service:true,data:{
        id:placeId,title,country_code:country,geom:'SRID=4326;POINT(' + coords.join(' ') + ')',is_published:true}});
    }
    const upload = '/functions/v1/v1-editorial-cover';
    await request(upload,403,{token:ordinary,image:png});
    await request(upload,422,{token:editor,image:new Uint8Array([1,2,3])});
    await request('/storage/v1/object/soundscape-media/editorial/covers/bypass.png',[400,403],
      {token:editor,image:png});
    const {coverPath} = await request(upload,201,{token:editor,image:png});
    const originalPlaylist = crypto.randomUUID();
    const originalTitle = code==='tokyo' ? 'Токио: между последним поездом и рассветом' : 'Лондон: ритмы на обратном пути';
    const originalDescription = '[BE-03-FIX QA] Описание исторической подборки';
    if (currentCity.officialPlaylistId) throw Error('Run the HTTP pilot against a disposable clean fixture database');
    const old = Array.from({length:13},(_,i)=>({
      id:i===11 ? (code==='tokyo'?'91000000-aaaa-c000-7000-000000000001':'91000000-aaaa-c000-7000-000000000002')
        : crypto.randomUUID(),
      title:'[BE-03-FIX QA] Track '+i,duration_ms:180000,
    }));
    await request('/rest/v1/tracks',201,{service:true,data:old});
    await request('/rest/v1/track_sources',201,{service:true,data:old.map((track,i)=>{
      const providerId=String(100000000+country.charCodeAt(0)*100+i);
      return {track_id:track.id,provider:'APPLE_MUSIC',provider_track_id:providerId,
        external_url:'https://music.apple.com/us/album/be03-qa/100000000?i='+providerId+'&uo=4',
        verified_at:new Date().toISOString()};
    })});
    await request('/rest/v1/playlists',201,{service:true,data:{id:originalPlaylist,owner_id:publisher.id,
      place_id:placeId,title:originalTitle,description:originalDescription,cover_path:coverPath,
      is_editorial:true,visibility:'PRIVATE'}});
    await request('/rest/v1/playlist_tracks',201,{service:true,data:old.map((track,i)=>
      ({playlist_id:originalPlaylist,track_id:track.id,position:i+1}))});
    for (const [table,field,id] of [['playlist_genres','genre_id',genreId],['playlist_tags','tag_id',tagId]] as const) {
      await request('/rest/v1/'+table,201,{service:true,data:{playlist_id:originalPlaylist,[field]:id}});
    }
    const adopted=await rpc('save_editorial_draft',{placeId,expectedRevision:0},200,editor);
    equal(adopted.playlistId,originalPlaylist,'Adoption preserves private historical UUID');
    equal(adopted.title,originalTitle,'Adoption preserves title');
    equal(adopted.description,originalDescription,'Adoption preserves description');
    equal(adopted.coverPath,coverPath,'Adoption preserves cover');
    equal(adopted.trackIds.join(),old.map(track=>track.id).join(),'Adoption preserves ordered tracks');
    equal(adopted.approvedRevision,null,'Adoption does not approve');
    await request('/functions/v1/v1-media',404,{data:{objectType:'PLAYLIST',objectId:originalPlaylist}});
    await request('/functions/v1/v1-media',404,{data:{objectType:'PLAYLIST',objectId:originalPlaylist},token:ordinary});
    await request('/functions/v1/v1-media',200,{data:{objectType:'PLAYLIST',objectId:originalPlaylist},token:editor});
    const capture = editorialCapture(code,Date.now(),coverPath,genreId,tagId,13);
    capture.draft.expectedRevision = adopted.revision;
    const manifest = prepareEditorialManifest(capture);
    const target = {url:base,marker:marker!,apiKey:apiKey!,serviceKey:serviceKey!,editorToken:editor};
    if (code==='tokyo') {
      await request('/rest/v1/rpc/editorial_import_fixture_batch',403,{service:true,
        data:{p_target:crypto.randomUUID(),p_items:[]}});
    }
    const first = await applyEditorialFixture(manifest,target);
    const replay = await applyEditorialFixture(manifest,target);
    const initial = first.draft.revision;
    equal(first.importedPositions,13,'Imported track positions');
    equal(first.validation.readyForApproval,true,'Draft readiness');
    equal(replay.draft.revision,initial,'Repeated import revision');
    equal(replay.draft.trackIds.join(),first.draft.trackIds.join(),'Repeated import keeps SoundScape UUIDs');
    const mapRequest = { minLon:coords[0]-1,maxLon:coords[0]+1,minLat:coords[1]-1,maxLat:coords[1]+1,limit:100 };
    const mapCity = async () => (await rpc('get_visible_places',mapRequest))
      .items.find((item: {id:string}) => item.id===placeId);
    equal((await mapCity()).playlistId,null,'Unpublished city has no public playlist');
    await rpc('get_editorial_draft',{placeId},401);
    await rpc('save_editorial_draft',{placeId,title:'access denied',expectedRevision:initial},403,ordinary);
    await rpc('approve_editorial_draft',{placeId,expectedRevision:initial},403,admin);
    await rpc('publish_editorial_draft',{placeId,expectedRevision:initial},403,editor);
    const titleBefore = originalTitle;
    const selected=[...first.draft.trackIds.slice(0,11),old[11].id,old[12].id];
    const firstEdit = await rpc('save_editorial_draft',{placeId,expectedRevision:initial,trackIds:selected},200,editor);
    const readiness=await rpc('check_editorial_draft',{placeId},200,editor);
    equal(readiness.inAppCount,11,'Verified in-app count');
    equal(readiness.externalOnlyCount,2,'Retained external count');
    await rpc('approve_editorial_draft',{placeId,expectedRevision:firstEdit.revision},200,owner);
    const publication = await rpc('publish_editorial_draft',{placeId,expectedRevision:firstEdit.revision},200,owner);
    const playlistId = publication.playlistId;
    equal((await mapCity()).playlistId,playlistId,'Guest map shows linked playlist');
    const guestPlaylist = () => rpc('get_playlist',{playlistId});
    equal(playlistId,originalPlaylist,'First publication retains the legacy UUID');
    equal((await guestPlaylist()).items.length,13,'Guest sees all positions');
    equal((await guestPlaylist()).items[11].track.availability,'EXTERNAL_ONLY','External track stays external');
    const image = await request('/functions/v1/v1-media',200,{data:{objectType:'PLAYLIST',objectId:playlistId}});
    equal(image.bytes,png.length,'Guest can read the linked artwork');
    equal((await mapCity()).coverPath,coverPath,'Map uses the official cover');
    equal((await request('/functions/v1/v1-media',200,{data:{objectType:'PLACE',objectId:placeId}})).bytes,
      png.length,'Map object delivers the same file');
    equal((await request('/functions/v1/v1-resolve-track',200,{data:{trackId:old[11].id}})).availability,
      'EXTERNAL_ONLY','External source resolves without contacting Audius');
    await rpc('save_playlist',{playlistId,title:'Forbidden mutation',visibility:'PUBLIC',trackIds:selected,
      expectedRevision:(await guestPlaylist()).revision},404,ordinary);
    const titleAfter = '[BE-03 QA] ' + title + ' вторая версия';
    const replacement = (await request(upload,201,{token:editor,image:png})).coverPath;
    const next = await rpc('save_editorial_draft',{placeId,expectedRevision:firstEdit.revision,title:titleAfter,
      coverPath:replacement,trackIds:selected.toReversed()},200,editor);
    equal((await mapCity()).coverPath,coverPath,'The former cover still serves guests during editing');
    const searchDraftTitle = () => request('/functions/v1/v1-search',200,
      {data:{query:title+' вторая версия',categories:['PLAYLIST']}});
    equal((await searchDraftTitle()).items.length,0,'Guest search excludes the draft');
    await rpc('approve_editorial_draft',{placeId,expectedRevision:firstEdit.revision},409,owner);
    await rpc('publish_editorial_draft',{placeId,expectedRevision:next.revision},409,owner);
    equal((await guestPlaylist()).title,titleBefore,'Guest still sees previous title');
    equal((await guestPlaylist()).items[0].track.id,first.draft.trackIds[0],'Guest still sees previous order');
    await rpc('approve_editorial_draft',{placeId,expectedRevision:next.revision},200,owner);
    equal((await rpc('publish_editorial_draft',{placeId,expectedRevision:next.revision},200,owner)).playlistId,
      playlistId,'Second version preserves the playlist UUID');
    equal((await guestPlaylist()).title,titleAfter,'Guest sees second version');
    equal((await mapCity()).coverPath,replacement,'Cover changes atomically with publication');
    await request('/storage/v1/object/soundscape-media',200,{method:'DELETE',token:editor,data:{prefixes:[coverPath]}});
    await request('/storage/v1/object/authenticated/soundscape-media/'+coverPath,[400,404],{method:'GET',token:editor});
    equal((await guestPlaylist()).items[0].track.id,selected[12],'Guest sees the new order');
    equal((await searchDraftTitle()).items[0]?.id,playlistId,'Guest search sees the new publication');
    if (code==='london') {
      equal((await request('/functions/v1/v1-delete-account',200,{data:{confirm:true},token:owner})).status,'DELETED',
        'Deleted publisher account');
      equal((await guestPlaylist()).id,originalPlaylist,'Publication survives publisher deletion');
      equal((await request('/functions/v1/v1-media',200,{data:{objectType:'PLACE',objectId:placeId}})).bytes,png.length,
        'Official artwork survives publisher deletion');
    }
    await rpc('unpublish_editorial_playlist',{placeId,expectedRevision:next.revision},200,alternateOwner);
    await rpc('get_playlist',{playlistId},404);
    equal((await mapCity()).playlistId,null,'Unpublished playlist disappears from map');
  }
}});

Deno.test({name:'BE-03-FIX JWT roles, private Storage, and editor infrastructure grants',ignore:!enabled,async fn() {
  const userA=await account('USER');
  const userB=await account('USER');
  const editor=await account('EDITOR');
  const target=crypto.randomUUID();
  await request('/auth/v1/user',200,{method:'PUT',token:userA.token,data:{
    data:{role:'ADMIN',role_code:'ADMIN',soundscape_editorial_owner:true},
  }});
  equal((await rpc('get_my_profile',{},200,userA.token)).role,'USER','User metadata cannot grant an application role');
  await rpc('list_editorial_cities',{},403,userA.token);
  equal((await rpc('list_editorial_cities',{},200,editor.token)).length,20,'Editor can read the city roster');
  await request('/rest/v1/editorial_drafts',[404,403],{method:'GET',token:editor.token});
  await request('/rest/v1/playlists?id=eq.'+target,403,{method:'PATCH',token:userA.token,data:{is_editorial:true}});
  await request('/rest/v1/rpc/publish_editorial_draft',403,{service:true,
    data:{placeId:target,expectedRevision:1}});
  await request('/rest/v1/rpc/editorial_live_target',403,{token:editor.token,data:{p_target:target}});
  const path='users/'+userA.id+'/'+crypto.randomUUID()+'.png';
  await request('/storage/v1/object/soundscape-media/'+path,200,{token:userA.token,image:png});
  await request('/storage/v1/object/authenticated/soundscape-media/'+path,200,{method:'GET',token:userA.token});
  await request('/storage/v1/object/authenticated/soundscape-media/'+path,[400,403,404],{method:'GET',token:userB.token});
}});
