import {authenticate,body,bool,ids,int,text,reserve,response,run,select,rpc,HttpError} from '../_shared/core.ts';
import {searchCatalog} from '../_shared/catalog.ts';
const CATS=['TRACK','ARTIST','PLAYLIST','PLACE','USER'];
export default {fetch:run(async(req,ctx)=>{
 const session=await authenticate(req),p=await body(req);
 const query=text(p.query,200,'query'),limit=int(p.limit,20,1,50,'limit'),offset=int(p.offset,0,0,1000,'offset');
 const genreIds=ids(p.genreIds),moodIds=ids(p.moodIds),onlyInApp=bool(p.onlyInApp);
 let categories=CATS;
 if(p.categories!==undefined){if(!Array.isArray(p.categories)||!p.categories.length||p.categories.length>5
   ||new Set(p.categories).size!==p.categories.length||p.categories.some((x:any)=>!CATS.includes(x)))
   throw new HttpError(400,'VALIDATION_ERROR','Invalid categories');categories=p.categories;}
 await reserve('SEARCH',session.userId,ctx);
 const fragment='*'+encodeURIComponent(query.replace(/[%*_(),]/g,' '))+'*';
 const otherItems:any[]=[];
 let tracks:({category:string;id:string;title:string;subtitle:string;availability:string}|null)[]=[];
 let trackError: HttpError | undefined;
 // RLS-scoped queries for personal data; never search UNLISTED/PRIVATE of other users.
 if(categories.includes('TRACK')){
  const ts=await searchCatalog(query,{genreIds,moodIds,onlyInApp},0,offset+limit+1,ctx,offset).catch(error=>{
   if(!(error instanceof HttpError) || !['RATE_LIMITED','PROVIDER_UNAVAILABLE','PROVIDER_ERROR','PROVIDER_INVALID_RESPONSE'].includes(error.code))throw error;
   trackError=error;
   return [];
  });
  tracks=ts.map(t=>t&&({category:'TRACK',id:t.id,title:t.title,subtitle:t.artist,availability:t.availability}));
 }
 if(categories.includes('ARTIST')){
  // Genre and mood filters do not apply to artist identity (documented contract).
  const aa=await select('artists',`select=id,name,track_artists!inner()&name=ilike.${fragment}&limit=100`,session.jwt,false,ctx);
  otherItems.push(...aa.map(a=>({category:'ARTIST',id:a.id,title:a.name,subtitle:null})));
 }
 if(categories.includes('PLAYLIST')){
  const fields=['id,title,description'];
  if(genreIds.length)fields.push('playlist_genres(genre_id)');
  if(moodIds.length||onlyInApp)fields.push(moodIds.length
    ?'playlist_tracks(track_id,tracks(track_moods(mood_id)))':'playlist_tracks(track_id)');
  const playlists=await select('playlists',`select=${fields.join(',')}&title=ilike.${fragment}&limit=100`,session.jwt,false,ctx) as {
    id:string;title:string;description:string|null;playlist_genres?:{genre_id:number}[];
    playlist_tracks?:{track_id:string;tracks?:{track_moods?:{mood_id:number}[]}|null}[];
  }[];
  const trackIds=onlyInApp?[...new Set(playlists.flatMap(p=>p.playlist_tracks?.map(link=>link.track_id)??[]))]:[];
  const playable=new Set<string>();
  for(let index=0;index<trackIds.length;index+=10000){
   const rows=await rpc('edge_fresh_audius_track_ids',{p_ids:trackIds.slice(index,index+10000)},ctx);
   for(const row of rows)playable.add(row.track_id);
  }
  for(const playlist of playlists){
   if(genreIds.length&&!playlist.playlist_genres?.some(genre=>genreIds.includes(genre.genre_id)))continue;
   const tracks=playlist.playlist_tracks??[];
   if(moodIds.length&&!tracks.some(link=>link.tracks?.track_moods?.some(mood=>moodIds.includes(mood.mood_id))))continue;
   if(onlyInApp&&(!tracks.length||!tracks.every(link=>playable.has(link.track_id))))continue;
   otherItems.push({category:'PLAYLIST',id:playlist.id,title:playlist.title,subtitle:playlist.description||null});
  }
 }
 if(categories.includes('PLACE')){
  const places=await select('places',`select=id,title,country_code&title=ilike.${fragment}&limit=100`,session.jwt,false,ctx);
  otherItems.push(...places.map(p=>({category:'PLACE',id:p.id,title:p.title,subtitle:p.country_code})));
 }
 if(categories.includes('USER')){
  const profiles=await select('profiles',`select=id,username&username=ilike.${fragment}&limit=100`,session.jwt,false,ctx);
  otherItems.push(...profiles.map(p=>({category:'USER',id:p.id,title:p.username,subtitle:null})));
 }
 const categoryIndex=(c:string)=>CATS.indexOf(c);
 otherItems.sort((a,b)=>categoryIndex(a.category)-categoryIndex(b.category)||
   a.title.localeCompare(b.title)||a.id.localeCompare(b.id));
 const items=[...tracks,...otherItems];
 if(!items.length && trackError)throw trackError;
 return response({items:items.slice(offset,offset+limit).filter(item=>item!==null),limit,offset,
  hasMore:items.length>offset+limit && offset+limit<=1000});
},'v1-search')};
