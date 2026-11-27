// Local only. Provision a fresh disposable database budget of 6/second and at least 20/month.
import assert from 'node:assert/strict';
import { audiusList, withAudiusProbe } from '../supabase/functions/_shared/audius.ts';
import { searchCatalog } from '../supabase/functions/_shared/catalog.ts';
import { adminRest, HttpError, rpc, sha256 } from '../supabase/functions/_shared/core.ts';
import { newRequestContext } from '../supabase/functions/_shared/observability.ts';

const env='SUPABASE_URL,SUPABASE_PUBLISHABLE_KEYS,SUPABASE_PUBLISHABLE_KEY,SUPABASE_ANON_KEY,'+
  'SUPABASE_SECRET_KEYS,SUPABASE_SECRET_KEY,SUPABASE_SERVICE_ROLE_KEY,AUDIUS_API_KEY,'+
  'AUDIUS_OPERATION_PARALLEL,AUDIUS_OPERATION_UPDATES';
const url=new URL(Deno.env.get('SUPABASE_URL')??'invalid:');
if(url.protocol!=='http:'||url.hostname!=='127.0.0.1'||!url.port)
  throw new Error('Use a dedicated loopback Supabase endpoint');
const pause=(ms:number)=>new Promise(resolve=>setTimeout(resolve,Math.max(0,ms)));
const filters={genreIds:[],moodIds:[],onlyInApp:true};

if(Deno.args[0]==='worker'){
  const [,scenario,start,fixture]=Deno.args;
  const real=globalThis.fetch;
  let sent=0;
  globalThis.fetch=async(input,init)=>{
    const target=new URL(input instanceof Request?input.url:String(input));
    if(target.origin===url.origin)return await real(input,init);
    assert.equal(target.origin,'https://api.audius.co');
    sent++;
    if(scenario==='snapshot'){
      if(target.pathname.endsWith('/stream'))return new Response('ID3-fixture-bytes',{
        status:206,headers:{'content-type':'audio/mpeg','content-range':'bytes 0-16/100'},
      });
      assert.equal(target.pathname,'/v1/tracks/'+fixture);
      await pause(1000);
      return Response.json({data:{id:fixture,title:fixture,user:{name:'Worker fixture'},duration:90,
        permalink:'/fixture/'+fixture,is_streamable:true}});
    }
    if(scenario==='lease')await pause(1300);
    if(scenario==='cooldown')return new Response(null,{status:429,headers:{'retry-after':'12'}});
    return Response.json({data:[]});
  };
  const ctx=newRequestContext('audius-worker-test');
  const query=()=>audiusList('/search',{query:'fixture'},ctx);
  await pause(Number(start)-Date.now());
  try{
    let result;
    if(scenario==='snapshot')result=await searchCatalog(fixture,filters,0,2,ctx);
    else if(scenario==='lease')await withAudiusProbe('SharedWorkerFixture',query,ctx);
    else await query();
    console.log(JSON.stringify({pid:Deno.pid,sent,status:200,
      ...(result?{trackId:result[0]?.id??null,slots:result.length}:{})}));
  }catch(err){
    if(!(err instanceof HttpError))throw err;
    console.log(JSON.stringify({pid:Deno.pid,sent,status:err.status,retry:err.retryAfter}));
  }
} else {
  async function workers(scenario:string,count:number,fixture=''){
    const start=Date.now()+3500;
    const outputs=await Promise.all(Array.from({length:count},()=>new Deno.Command(Deno.execPath(),{
      args:['run','--cached-only','--no-prompt','--allow-env='+env,'--allow-net='+url.host,
        import.meta.filename!,'worker',scenario,String(start),fixture],
      stdout:'piped',stderr:'piped',
    }).output()));
    const decoder=new TextDecoder();
    const results=outputs.map(result=>{
      assert.equal(result.code,0,decoder.decode(result.stderr));
      return JSON.parse(decoder.decode(result.stdout));
    });
    assert.equal(new Set(results.map(result=>result.pid)).size,count);
    console.log(JSON.stringify({scenario,results}));
    return results;
  }
  const leased=await workers('lease',2);
  assert.equal(leased.filter(result=>result.sent===1&&result.status===200).length,1);
  assert.equal(leased.filter(result=>result.sent===0&&result.status===429).length,1);

  const fixture='WorkerSnapshot'+crypto.randomUUID().replaceAll('-','').slice(0,10);
  const [track]=await rpc('edge_import_audius_batch',{p_items:[{id:fixture,title:fixture,artist:'Worker fixture',
    durationMs:90000,permalink:'https://audius.co/fixture/'+fixture,canStream:true}]});
  const hash=await sha256(JSON.stringify([2,fixture,[],[],true]));
  await rpc('edge_catalog_search_slots',{p_hash:hash,p_offset:0,p_limit:2,p_refresh_from:0,p_probe_limit:0,
    p_items:[{id:track.id,title:fixture,artist:'Worker fixture',durationMs:90000,availability:'IN_APP'}]});
  const updated=await adminRest('track_sources?track_id=eq.'+track.id,{method:'PATCH',
    body:JSON.stringify({verified_at:new Date(Date.now()-7200000).toISOString()})});
  assert.equal(updated.status,204);
  const snapshots=await workers('snapshot',2,fixture);
  assert.ok(snapshots.every(result=>result.status===200&&result.slots===1));
  assert.equal(snapshots.reduce((sum,result)=>sum+result.sent,0),2,'one worker sends metadata and audio probe');
  assert.ok(snapshots.some(result=>result.trackId===track.id));
  assert.equal((await searchCatalog(fixture,filters,0,2))[0]?.id,track.id);

  const rate=await workers('rate',8);
  assert.equal(rate.filter(result=>result.sent===1&&result.status===200).length,6);
  assert.equal(rate.filter(result=>result.sent===0&&result.status===429).length,2);
  const [cooldown]=await workers('cooldown',1);
  assert.deepEqual([cooldown.status,cooldown.sent,cooldown.retry],[429,1,12]);
  const [blocked]=await workers('after-cooldown',1);
  assert.equal(blocked.status,429);
  assert.equal(blocked.sent,0);
  assert.ok(blocked.retry>0&&blocked.retry<=12);
}
