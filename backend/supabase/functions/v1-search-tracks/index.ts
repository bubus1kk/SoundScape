import {authenticate,body,bool,ids,int,text,reserve,response,run,HttpError} from '../_shared/core.ts';
import {findCandidates,localTracks} from '../_shared/catalog.ts';
export default { fetch:run(async(req)=>{
 const session=await authenticate(req);
 const p=await body(req);
 const query=text(p.query,200,'query');
 const genreIds=ids(p.genreIds), moodIds=ids(p.moodIds);
 const onlyInApp=bool(p.onlyInApp);
 const limit=int(p.limit,20,1,50,'limit'),offset=int(p.offset,0,0,1000,'offset');
 await reserve('SEARCH_TRACKS',session.userId);
 const cached=await localTracks(query,genreIds,moodIds,onlyInApp,100);
 // If search has no genre/mood filters, Audius may add verified real catalog entries.
 let found=cached;
 if(offset===0 && !genreIds.length&&!moodIds.length && cached.length<limit){
  try{found=await findCandidates(query,onlyInApp,genreIds,moodIds,limit+1);}
  catch(e){if(!cached.length)throw e;}
 }
 const unique=[...new Map(found.map(t=>[t.id,t])).values()];
 return response({items:unique.slice(offset,offset+limit),limit,offset,hasMore:unique.length>offset+limit});
})};
