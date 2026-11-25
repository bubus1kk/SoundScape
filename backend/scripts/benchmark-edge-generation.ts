import assert from 'node:assert/strict';
import { findGenerationCandidates, type GenerationSearch } from '../supabase/functions/_shared/catalog.ts';
import { newRequestContext } from '../supabase/functions/_shared/observability.ts';
import { privilegedHeaders } from '../supabase/functions/_shared/core.ts';

const origin=new URL(Deno.env.get('SUPABASE_URL')??'invalid:');
assert.equal(origin.protocol,'http:');
assert.equal(origin.hostname,'127.0.0.1');
assert.ok(origin.port);
const [scale,samples='10']=Deno.args;
assert.ok(['300','1000','5000','10000','stale'].includes(scale));
const count=20;
const request:GenerationSearch={prompt:'Спокойный эмбиент',genre:'',mood:'',context:[],
  pinnedTrackIds:[],onlyInApp:true,genreIds:[],moodIds:[]};
const intent={kind:'MOOD' as const,genres:['Ambient'],moods:['Peaceful'],excludedGenres:[],target:''};
const realFetch=globalThis.fetch;
const reset=()=>({http:0,rows:0,bytes:0,providerCalls:0});
let metrics=reset();
const encoder=new TextEncoder();
globalThis.fetch=async(input,init)=>{
  const url=new URL(input instanceof Request?input.url:String(input));
  if(url.origin!==origin.origin){
    metrics.providerCalls++;
    if(url.hostname==='creatornode.audius.co')return new Response(encoder.encode('ID3-fixture-audio'),{
      status:206,headers:{'content-type':'audio/mpeg','content-range':'bytes 0-16/100'},
    });
    assert.equal(url.hostname,'api.audius.co');
    if(url.pathname.endsWith('/stream'))return new Response(null,{status:302,
      headers:{location:'https://creatornode.audius.co/benchmark-fixture'}});
    if(url.pathname.endsWith('/search'))return Response.json({data:[]});
    const id=url.pathname.split('/').at(-1);
    return Response.json({data:{id,title:'Ambient fixture',user:{name:'Fixture'},duration:90,
      permalink:'/fixture/'+id,is_streamable:true}});
  }
  metrics.http++;
  const response=await realFetch(input,init);
  const body=await response.text();
  metrics.bytes+=encoder.encode(body).byteLength;
  if(body&&response.ok){
    const value=JSON.parse(body);
    if(Array.isArray(value))metrics.rows+=value.length;
  }
  if(!response.ok)console.error('Local benchmark HTTP status',response.status,url.pathname);
  return new Response(response.status===204?null:body,{status:response.status,headers:response.headers});
};

async function measure(semantic:boolean) {
  if(scale==='stale'){
    const aged=await realFetch(origin.origin+'/rest/v1/track_sources?provider_track_id=eq.Be01Final5',{
      method:'PATCH',headers:{...privilegedHeaders(),'content-type':'application/json'},
      body:JSON.stringify({verified_at:new Date(Date.now()-7200000).toISOString()}),
    });
    assert.equal(aged.status,204);
  }
  metrics=reset();
  const start=performance.now();
  const result=await findGenerationCandidates(semantic?{...request,intent}:request,count,
    newRequestContext('edge-benchmark'));
  const ms=performance.now()-start;
  assert.equal(result.length,count);
  assert.ok(result.every(track=>track.availability==='IN_APP'&&track.genres?.includes('Ambient')&&
    track.moods?.includes('Peaceful')));
  return {...metrics,ms,relevant:result.length};
}
function percentile(points:number[],quantile:number){
  points.sort((left,right)=>left-right);
  return Number(points[Math.ceil(quantile*points.length)-1].toFixed(2));
}
const baseline:Awaited<ReturnType<typeof measure>>[]=[];
const bounded:typeof baseline=[];
for(let sample=-2;sample<Number(samples);sample++){
  const oldFirst=sample%2===0;
  const first=await measure(!oldFirst),second=await measure(oldFirst);
  if(sample>=0){
    baseline.push(oldFirst?first:second);
    bounded.push(oldFirst?second:first);
  }
}
function summary(runs:typeof baseline){
  const select=<K extends keyof typeof runs[number]>(key:K)=>runs.map(run=>run[key]);
  return {p50:percentile(select('ms'),0.50),p95:percentile(select('ms'),0.95),
    http:[Math.min(...select('http')),Math.max(...select('http'))],
    rows:[Math.min(...select('rows')),Math.max(...select('rows'))],
    bytes:[Math.min(...select('bytes')),Math.max(...select('bytes'))],
    providerCalls:select('providerCalls').reduce((sum,n)=>sum+n,0),relevant:select('relevant')[0]};
}
console.log(JSON.stringify({scale,samples:Number(samples),baseline:summary(baseline),bounded:summary(bounded)}));
