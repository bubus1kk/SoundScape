import {authenticate,body,text,sha256,rpc,reserve,response,run,HttpError,publishableKey,uuid} from '../_shared/core.ts';
import {parseSharePath,shareKinds,sharePage} from '../_shared/share_route.ts';
export default {fetch:run(async(req,ctx)=>{
 const path=new URL(req.url).pathname;
 if (req.method === 'GET') {
  const install = Deno.env.get('SOUNDSCAPE_INSTALL_URL');
  return sharePage(path,publishableKey(),crypto.randomUUID(),
    install?.startsWith('https://') ? install : undefined);
 }
 const session=await authenticate(req),p=await body(req);
 const hasTarget=p.objectId!==undefined||p.objectType!==undefined;
 const kind=hasTarget?text(p.objectType,15,'objectType'):null;
 const id=hasTarget?uuid(p.objectId,'objectId').toLowerCase():null;
 if (kind && !shareKinds.includes(kind)) throw new HttpError(400,'VALIDATION_ERROR','Invalid objectType');
 if (path.includes('/share/')) {
  const target=parseSharePath(path);
  if (target.objectType!==kind||target.objectId!==id) throw new HttpError(404,'NOT_FOUND','Shared object not found');
 }
 const token=p.token!==undefined?text(p.token,256,'token',32):null;
 if (!token && !hasTarget) throw new HttpError(400,'VALIDATION_ERROR','Share target required');
 await reserve('OPEN_SHARE',session.userId,ctx);
 const obj=token?await rpc('edge_open_share',{p_hash:await sha256(token)},ctx)
   :await rpc('edge_open_public',{p_kind:kind,p_id:id},ctx);
 if(!obj||(hasTarget&&(obj.objectType!==kind||obj.id!==id)))
   throw new HttpError(404,'NOT_FOUND','Shared object not found');
 return response(obj);
},'v1-open-share')};
