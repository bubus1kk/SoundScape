import {authenticate,body,text,sha256,rpc,reserve,response,run,HttpError} from '../_shared/core.ts';
export default {fetch:run(async(req)=>{
 const session=await authenticate(req),p=await body(req);
 const token=text(p.token,256,'token',32);
 await reserve('OPEN_SHARE',session.userId);
 const obj=await rpc('edge_open_share',{p_hash:await sha256(token)});
 if(!obj)throw new HttpError(404,'NOT_FOUND','Shared object not found');
 return response(obj);
})};
