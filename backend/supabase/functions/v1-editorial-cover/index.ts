import { authenticate, HttpError, privilegedHeaders, reserve, response, rpc, run, supabaseUrl } from '../_shared/core.ts';
import { imageExtensions, matchesImage, maxImageBytes } from '../_shared/image.ts';

async function readImage(req: Request): Promise<Uint8Array<ArrayBuffer>> {
  if (Number(req.headers.get('content-length')) > maxImageBytes) {
    throw new HttpError(413,'MEDIA_TOO_LARGE','Cover exceeds 10 MiB');
  }
  const reader = req.body?.getReader();
  if (!reader) throw new HttpError(422,'INVALID_MEDIA','Cover is empty');
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const {done,value} = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > maxImageBytes) {
        await reader.cancel();
        throw new HttpError(413,'MEDIA_TOO_LARGE','Cover exceeds 10 MiB');
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const image = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    image.set(chunk,offset);
    offset += chunk.byteLength;
  }
  return image;
}

export default {fetch:run(async (req,ctx) => {
  if (req.method !== 'POST') throw new HttpError(405,'METHOD_NOT_ALLOWED','Use POST');
  const session = await authenticate(req,true);
  if (await rpc('edge_can_upload_editorial_cover',{p_user:session.userId},ctx) !== true) {
    throw new HttpError(403,'EDITORIAL_EDITOR_REQUIRED','Editorial permission required');
  }
  const type = (req.headers.get('content-type') ?? '').split(';')[0].trim().toLowerCase();
  if (!Object.hasOwn(imageExtensions,type)) throw new HttpError(415,'INVALID_MEDIA','Use JPEG, PNG or WebP');
  await reserve('MEDIA',session.userId,ctx);
  const image = await readImage(req);
  if (!matchesImage(type,image)) throw new HttpError(422,'INVALID_MEDIA','Cover data does not match its type');
  const extension = imageExtensions[type as keyof typeof imageExtensions];
  const coverPath = 'editorial/covers/' + crypto.randomUUID() + '.' + extension;
  const stored = await fetch(supabaseUrl()+'/storage/v1/object/soundscape-media/'+coverPath,{
    method:'POST', headers:{...privilegedHeaders(),'content-type':type},body:image,
    redirect:'error',signal:AbortSignal.timeout(10_000),
  }).catch(() => {throw new HttpError(503,'STORAGE_UNAVAILABLE','Cover upload unavailable');});
  await stored.body?.cancel();
  if (!stored.ok) throw new HttpError(503,'STORAGE_UNAVAILABLE','Cover upload unavailable');
  return response({coverPath},201);
},'v1-editorial-cover')};
