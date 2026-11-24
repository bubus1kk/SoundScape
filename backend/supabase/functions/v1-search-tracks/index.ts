import {authenticate,body,bool,ids,int,text,reserve,response,run,HttpError} from '../_shared/core.ts';
import {searchCatalog} from '../_shared/catalog.ts';
export default { fetch:run(async(req,ctx)=>{
 const session=await authenticate(req);
 const p=await body(req);
 const query=text(p.query,200,'query');
 const genreIds=ids(p.genreIds), moodIds=ids(p.moodIds);
 const onlyInApp=bool(p.onlyInApp);
 const limit=int(p.limit,20,1,50,'limit'),offset=int(p.offset,0,0,1000,'offset');
 await reserve('SEARCH_TRACKS',session.userId,ctx);
 const tracks=await searchCatalog(query,{genreIds,moodIds,onlyInApp},offset,limit+1,ctx);
 return response({items:tracks.slice(0,limit),limit,offset,hasMore:tracks.length>limit && offset+limit<=1000});
},'v1-search-tracks')};
