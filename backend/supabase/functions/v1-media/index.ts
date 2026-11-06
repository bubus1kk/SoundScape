import {authenticate,body,text,uuid,reserve,rpc,sha256,run,HttpError,supabaseUrl,secretKey} from '../_shared/core.ts';
export default {fetch:run(async(req)=>{
 const session=await authenticate(req),p=await body(req);
 const kind=text(p.objectType,12,'objectType');if(!['PIN','EVENT','PLAYLIST','PLACE'].includes(kind))throw new HttpError(400,'VALIDATION_ERROR','Invalid objectType');
 const id=uuid(p.objectId,'objectId'),photoId=p.photoId===undefined?null:uuid(p.photoId,'photoId');
 if((kind==='PIN'||kind==='EVENT')&&!photoId)throw new HttpError(400,'VALIDATION_ERROR','photoId required');
 if((kind==='PLAYLIST'||kind==='PLACE')&&photoId)throw new HttpError(400,'VALIDATION_ERROR','photoId not allowed');
 const token=req.headers.get('x-soundscape-share-token');
 if(token&&(token.length<32||token.length>256))throw new HttpError(400,'VALIDATION_ERROR','Invalid share token');
 await reserve('MEDIA',session.userId);
 const asset=await rpc('edge_media_path',{p_user:session.userId,p_kind:kind,p_object:id,p_photo:photoId,p_token_hash:token?await sha256(token):null});
 if(!asset?.path)throw new HttpError(404,'NOT_FOUND','Media not found');
 const res=await fetch(supabaseUrl()+'/storage/v1/object/authenticated/soundscape-media/'+asset.path.split('/').map(encodeURIComponent).join('/'),{
   headers:{apikey:secretKey()},signal:AbortSignal.timeout(10000)
 }).catch(()=>{throw new HttpError(503,'STORAGE_UNAVAILABLE','Media unavailable');});
 if(!res.ok)throw new HttpError(res.status===404?404:503,'STORAGE_UNAVAILABLE','Media unavailable');
 const type=(res.headers.get('content-type')||'').split(';')[0].toLowerCase();
 if(!['image/jpeg','image/png','image/webp'].includes(type))throw new HttpError(502,'INVALID_MEDIA','Unsupported media type');
 const bytes=await res.arrayBuffer();if(bytes.byteLength>10485760)throw new HttpError(502,'INVALID_MEDIA','Media too large');
 return new Response(bytes,{status:200,headers:{'content-type':type,'content-length':String(bytes.byteLength),
   'cache-control':'no-store, max-age=0','pragma':'no-cache','x-content-type-options':'nosniff',
   'content-security-policy':"default-src 'none'; sandbox"}});
})};
