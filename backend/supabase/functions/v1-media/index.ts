import {authenticate,body,text,uuid,reserve,rpc,sha256,run,HttpError,supabaseUrl,privilegedHeaders,response} from '../_shared/core.ts';
import {ownedPaths, removeMedia} from '../_shared/media_cleanup.ts';
function matchesImage(type:string, bytes:Uint8Array):boolean {
 if(type==='image/jpeg')return bytes[0]===0xff&&bytes[1]===0xd8&&bytes[2]===0xff;
 if(type==='image/png')return [137,80,78,71,13,10,26,10].every((value,index)=>bytes[index]===value);
 return type==='image/webp'&&bytes.length>=12&&
   String.fromCharCode(...bytes.subarray(0,4))==='RIFF'&&String.fromCharCode(...bytes.subarray(8,12))==='WEBP';
}
export default {fetch:run(async(req,ctx)=>{
 const session=await authenticate(req),p=await body(req);
 if (p.action === 'CLEANUP') {
   if (!session.userId) throw new HttpError(401,'AUTH_REQUIRED','Sign in required');
   await reserve('MEDIA',session.userId,ctx);
   const paths=ownedPaths(await rpc('edge_claim_media_cleanup',{p_user:session.userId},ctx),session.userId,20);
   await removeMedia(paths);
   await rpc('edge_complete_media_cleanup',{p_user:session.userId,p_paths:paths},ctx);
   return response({processed:paths.length});
 }
 const kind=text(p.objectType,12,'objectType');if(!['PIN','EVENT','PLAYLIST','PLACE','PROFILE'].includes(kind))throw new HttpError(400,'VALIDATION_ERROR','Invalid objectType');
 const id=uuid(p.objectId,'objectId'),photoId=p.photoId===undefined?null:uuid(p.photoId,'photoId');
 if((kind==='PIN'||kind==='EVENT')&&!photoId)throw new HttpError(400,'VALIDATION_ERROR','photoId required');
 if((kind==='PLAYLIST'||kind==='PLACE'||kind==='PROFILE')&&photoId)throw new HttpError(400,'VALIDATION_ERROR','photoId not allowed');
 const token=req.headers.get('x-soundscape-share-token');
 if(token&&(token.length<32||token.length>256))throw new HttpError(400,'VALIDATION_ERROR','Invalid share token');
 await reserve('MEDIA',session.userId,ctx);
 const asset=await rpc('edge_media_path',{p_user:session.userId,p_kind:kind,p_object:id,p_photo:photoId,p_token_hash:token?await sha256(token):null},ctx);
 if(!asset?.path)throw new HttpError(404,'NOT_FOUND','Media not found');
 const res=await fetch(supabaseUrl()+'/storage/v1/object/authenticated/soundscape-media/'+asset.path.split('/').map(encodeURIComponent).join('/'),{
   headers:privilegedHeaders(),signal:AbortSignal.timeout(10000)
 }).catch(()=>{throw new HttpError(503,'STORAGE_UNAVAILABLE','Media unavailable');});
 if(!res.ok)throw new HttpError(res.status===404?404:503,'STORAGE_UNAVAILABLE','Media unavailable');
 const type=(res.headers.get('content-type')||'').split(';')[0].toLowerCase();
 if(!['image/jpeg','image/png','image/webp'].includes(type))throw new HttpError(502,'INVALID_MEDIA','Unsupported media type');
 const bytes=await res.arrayBuffer();if(bytes.byteLength>10485760)throw new HttpError(502,'INVALID_MEDIA','Media too large');
 if(!matchesImage(type,new Uint8Array(bytes)))throw new HttpError(502,'INVALID_MEDIA','Image data does not match its type');
 return new Response(bytes,{status:200,headers:{'content-type':type,'content-length':String(bytes.byteLength),
   'cache-control':'no-store, max-age=0','pragma':'no-cache','x-content-type-options':'nosniff',
   'content-security-policy':"default-src 'none'; sandbox"}});
},'v1-media')};
