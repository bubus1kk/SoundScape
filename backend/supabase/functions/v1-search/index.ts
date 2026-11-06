import {authenticate,body,bool,ids,int,text,reserve,response,run,select,HttpError} from '../_shared/core.ts';
import {localTracks} from '../_shared/catalog.ts';
const CATS=['TRACK','ARTIST','PLAYLIST','PLACE','USER'];
export default {fetch:run(async(req)=>{
 const session=await authenticate(req),p=await body(req);
 const query=text(p.query,200,'query'),limit=int(p.limit,20,1,50,'limit'),offset=int(p.offset,0,0,1000,'offset');
 const genreIds=ids(p.genreIds),moodIds=ids(p.moodIds),onlyInApp=bool(p.onlyInApp);
 let categories=CATS;
 if(p.categories!==undefined){if(!Array.isArray(p.categories)||!p.categories.length||p.categories.length>5
   ||new Set(p.categories).size!==p.categories.length||p.categories.some((x:any)=>!CATS.includes(x)))
   throw new HttpError(400,'VALIDATION_ERROR','Invalid categories');categories=p.categories;}
 await reserve('SEARCH',session.userId);
 const fragment='*'+encodeURIComponent(query.replace(/[%*_(),]/g,' '))+'*';
 const items:any[]=[];
 // RLS-scoped queries for personal data; never search UNLISTED/PRIVATE of other users.
 if(categories.includes('TRACK')){
  const ts=await localTracks(query,genreIds,moodIds,onlyInApp,100);
  items.push(...ts.map(t=>({category:'TRACK',id:t.id,title:t.title,subtitle:t.artist,availability:t.availability})));
 }
 if(categories.includes('ARTIST')){
  // Genre and mood filters do not apply to artist identity (documented contract).
  const aa=await select('artists',`select=id,name&name=ilike.${fragment}&limit=100`,session.jwt);
  items.push(...aa.map(a=>({category:'ARTIST',id:a.id,title:a.name,subtitle:null})));
 }
 if(categories.includes('PLAYLIST')){
  const pp=await select('playlists',`select=id,title,description,visibility&title=ilike.${fragment}&limit=100`,session.jwt);
  // OnlyInApp requires every playlist track to be playable. Filter by track association server-side.
  for(const p of pp){
   if(genreIds.length){
     const rows=await select('playlist_genres',`select=genre_id&playlist_id=eq.${p.id}&limit=100`,session.jwt);
     if(!rows.some(r=>genreIds.includes(r.genre_id)))continue;
   }
   if(moodIds.length){
     const playlistTracks=await select('playlist_tracks',`select=track_id&playlist_id=eq.${p.id}&limit=100`,session.jwt);
     if(!playlistTracks.length)continue;
     const ids=playlistTracks.map((x:any)=>x.track_id).join(',');
     const rows=await select('track_moods',`select=mood_id&track_id=in.(${ids})&limit=100`,session.jwt);
     if(!rows.some(r=>moodIds.includes(r.mood_id)))continue;
   }
   if(onlyInApp){const pt=await select('playlist_tracks',`select=track_id&playlist_id=eq.${p.id}&limit=100`,session.jwt);
     const ts=await Promise.all(pt.map((t:any)=>select('track_sources',`select=can_stream,verified_at&track_id=eq.${t.track_id}&can_stream=eq.true&verified_at=not.is.null&limit=1`,null,true)));
     if(!pt.length||ts.some(arr=>!arr.length))continue;
   }
   items.push({category:'PLAYLIST',id:p.id,title:p.title,subtitle:p.description||null});
  }
 }
 if(categories.includes('PLACE')){
  const places=await select('places',`select=id,title,country_code&title=ilike.${fragment}&limit=100`,session.jwt);
  items.push(...places.map(p=>({category:'PLACE',id:p.id,title:p.title,subtitle:p.country_code})));
 }
 if(categories.includes('USER')){
  const profiles=await select('profiles',`select=id,username&username=ilike.${fragment}&limit=100`,session.jwt);
  items.push(...profiles.map(p=>({category:'USER',id:p.id,title:p.username,subtitle:null})));
 }
 const categoryIndex=(c:string)=>CATS.indexOf(c);
 items.sort((a,b)=>categoryIndex(a.category)-categoryIndex(b.category)||a.title.localeCompare(b.title)||a.id.localeCompare(b.id));
 return response({items:items.slice(offset,offset+limit),limit,offset,hasMore:items.length>offset+limit});
})};
