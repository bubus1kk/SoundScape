import {authenticate,body,text,uuid,sha256,rpc,reserve,response,run,HttpError} from '../_shared/core.ts';
export default {fetch:run(async(req)=>{
 const session=await authenticate(req,true),p=await body(req);
 await reserve('MANAGE_SHARE',session.userId);
 const action=text(p.action,10,'action');
 if(action==='REVOKE'){
  const ok=await rpc('edge_revoke_share',{p_user:session.userId,p_share:uuid(p.shareId,'shareId')});
  if(!ok)throw new HttpError(404,'NOT_FOUND','Share not found');return response({revoked:true});
 }
 if(action!=='CREATE')throw new HttpError(400,'VALIDATION_ERROR','Invalid action');
 const type=text(p.objectType,15,'objectType'),id=uuid(p.objectId,'objectId');
 if(!['PLAYLIST','MUSIC_PIN','TRAVEL_EVENT','JOURNEY'].includes(type))throw new HttpError(400,'VALIDATION_ERROR','Invalid objectType');
 let expiresAt:string|null=null;
 if(p.expiresAt!==undefined&&p.expiresAt!==null){if(typeof p.expiresAt!=='string'||!Number.isFinite(Date.parse(p.expiresAt)))throw new HttpError(400,'VALIDATION_ERROR','Invalid expiresAt');expiresAt=new Date(p.expiresAt).toISOString();}
 const bytes=crypto.getRandomValues(new Uint8Array(32));
 const token=btoa(String.fromCharCode(...bytes)).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
 const shareId=await rpc('edge_create_share',{p_user:session.userId,p_type:type,p_id:id,p_hash:await sha256(token),p_expires:expiresAt});
 // Raw token returned ONCE, never stored in database, console, or logs.
 return response({shareId,token,objectType:type,objectId:id,expiresAt},201);
})};
