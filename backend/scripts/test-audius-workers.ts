// Local only. Provision a fresh disposable database budget of 6/second and at least 20/month.
import assert from 'node:assert/strict';
import { audiusList, withAudiusProbe } from '../supabase/functions/_shared/audius.ts';
import { HttpError } from '../supabase/functions/_shared/core.ts';
import { newRequestContext } from '../supabase/functions/_shared/observability.ts';

const env='SUPABASE_URL,SUPABASE_PUBLISHABLE_KEYS,SUPABASE_PUBLISHABLE_KEY,SUPABASE_ANON_KEY,'+
  'SUPABASE_SECRET_KEYS,SUPABASE_SECRET_KEY,SUPABASE_SERVICE_ROLE_KEY,AUDIUS_API_KEY';
const url=new URL(Deno.env.get('SUPABASE_URL')??'invalid:');
if(url.protocol!=='http:'||url.hostname!=='127.0.0.1'||!url.port)
  throw new Error('Use a dedicated loopback Supabase endpoint');
const pause=(ms:number)=>new Promise(resolve=>setTimeout(resolve,Math.max(0,ms)));

if(Deno.args[0]==='worker'){
  const [,scenario,start]=Deno.args;
  const real=globalThis.fetch;
  let sent=0;
  globalThis.fetch=async(input,init)=>{
    const target=new URL(input instanceof Request?input.url:String(input));
    if(target.origin===url.origin)return await real(input,init);
    assert.equal(target.origin,'https://api.audius.co');
    sent++;
    if(scenario==='lease')await pause(1300);
    if(scenario==='cooldown')return new Response(null,{status:429,headers:{'retry-after':'12'}});
    return Response.json({data:[]});
  };
  const ctx=newRequestContext('audius-worker-test');
  const query=()=>audiusList('/search',{query:'fixture'},ctx);
  await pause(Number(start)-Date.now());
  try{
    if(scenario==='lease')await withAudiusProbe('SharedWorkerFixture',query,ctx);
    else await query();
    console.log(JSON.stringify({pid:Deno.pid,sent,status:200}));
  }catch(err){
    if(!(err instanceof HttpError))throw err;
    console.log(JSON.stringify({pid:Deno.pid,sent,status:err.status,retry:err.retryAfter}));
  }
} else {
  async function workers(scenario:string,count:number){
    const start=Date.now()+3500;
    const outputs=await Promise.all(Array.from({length:count},()=>new Deno.Command(Deno.execPath(),{
      args:['run','--cached-only','--no-prompt','--allow-env='+env,'--allow-net='+url.host,
        import.meta.filename!,'worker',scenario,String(start)],
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
