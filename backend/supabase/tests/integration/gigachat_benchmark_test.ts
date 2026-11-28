import assert from 'node:assert/strict';
import type { GenerationSearch, Track } from '../../functions/_shared/catalog.ts';
import type { GenerationMode } from '../../functions/_shared/music_intent.ts';
import { analyzeIntent, generatePreview, giga } from '../../functions/v1-generate-playlist/index.ts';

const tracks:Track[]=[
  {id:'a674baad-455f-4d8e-a0f8-e9ae8cbf3441',title:'Night Lights',artist:'Fixture Artist',durationMs:95000,
    genres:['Jazz'],moods:['Peaceful'],availability:'IN_APP'},
  {id:'a674baad-455f-4d8e-a0f8-e9ae8cbf3442',title:'Neon',artist:'Another Fixture',durationMs:83000,
    genres:['Synthwave'],moods:['Atmospheric'],availability:'IN_APP'},
  {id:'a674baad-455f-4d8e-a0f8-e9ae8cbf3443',title:'Rainy Evening',artist:'Fixture Artist',durationMs:91000,
    genres:['Rock'],moods:['Peaceful'],availability:'EXTERNAL_ONLY'},
];

const request:GenerationSearch={prompt:'',genre:'',mood:'',context:[],pinnedTrackIds:[],
  onlyInApp:false,genreIds:[],moodIds:[]};
const emptyIntent={target:'',genres:[],moods:[],requiredGenres:[],excludedGenres:[]};
const cases=[
  {mode:'QUICK',prompt:'Ночной город',reply:{...emptyIntent,kind:'PLACE',genres:['Synthwave']},
    expectedKind:'ATMOSPHERE',selected:tracks[1]},
  {mode:'QUICK',prompt:'Тихий саундтрек для прогулки по пустым улицам после дождя, с лёгкой меланхолией, без рока',
    reply:{...emptyIntent,kind:'MOOD',genres:['Jazz'],moods:['Peaceful']},expectedKind:'MOOD',selected:tracks[0]},
  {mode:'ADVANCED',prompt:'Спокойный атмосферный джаз',genreIds:[1],onlyInApp:true,
    reply:{...emptyIntent,kind:'ATMOSPHERE',genres:['Jazz'],moods:['Peaceful']},expectedKind:'ATMOSPHERE',selected:tracks[0]},
  {mode:'QUICK',prompt:'Подборка песен исполнителя Fixture Artist',
    reply:{...emptyIntent,kind:'ARTIST',target:'Fixture Artist'},expectedKind:'ARTIST',selected:tracks[0]},
] as const;

Deno.test('controlled Lite and Pro benchmark sends identical prompts, schema and frozen catalog data',async()=>{
  const fetchBefore=globalThis.fetch;
  const keys=['GIGACHAT_AUTH_KEY','GIGACHAT_MODEL','GIGACHAT_SCOPE'];
  const envBefore=keys.map(key=>Deno.env.get(key));
  const requestsByModel:unknown[][]=[];
  const read:Parameters<typeof analyzeIntent>[4]=async table=>table==='genres'
    ? [{id:1,name:'Jazz'},{id:2,name:'Synthwave'},{id:3,name:'Rock'}]
    : [{id:1,name:'Peaceful'},{id:2,name:'Atmospheric'}];
  try{
    Deno.env.set('GIGACHAT_AUTH_KEY','benchmark-fixture');
    Deno.env.set('GIGACHAT_SCOPE','GIGACHAT_API_PERS');
    for(const model of ['GigaChat-2','GigaChat-2-Pro']){
      Deno.env.set('GIGACHAT_MODEL',model);
      const payloads:unknown[]=[];
      let oauthCalls=0;
      let active=0;
      for(const scenario of cases){
        globalThis.fetch=async (input,init)=>{
          const url=new URL(String(input));
          if(url.hostname==='ngw.devices.sberbank.ru'){
            oauthCalls++;
            return Response.json({access_token:'benchmark-oauth-fixture'});
          }
          assert.equal(url.origin,'https://api.giga.chat');
          assert.equal(++active,1);
          await Promise.resolve();
          active--;
          const {model:requestedModel,...payload}=JSON.parse(String(init?.body));
          assert.equal(requestedModel,model);
          assert.equal(payload.response_format.type,'json_schema');
          assert.equal(payload.response_format.strict,true);
          payloads.push(payload);
          const info=JSON.parse(payload.messages[1].content);
          const result=info.vocabulary?scenario.reply:{
            title:'Пример подборки',description:'Подборка по запросу',items:[
              info.candidates.find((track:Track)=>track.title===scenario.selected.title)?.index,
            ],
          };
          if(scenario.prompt.endsWith('без рока')&&!info.vocabulary)
            assert.ok(info.candidates.every((candidate:Track)=>!candidate.genres?.includes('Rock')));
          return Response.json({model,choices:[{finish_reason:'stop',message:{content:JSON.stringify(result)}}],
            usage:{prompt_tokens:100,completion_tokens:45,total_tokens:145}});
        };
        const mode:GenerationMode=scenario.mode;
        const input={...request,prompt:scenario.prompt,genreIds:'genreIds' in scenario?[...scenario.genreIds]:[],
          onlyInApp:'onlyInApp' in scenario&&scenario.onlyInApp};
        const session={deadline:AbortSignal.timeout(3000)};
        const analysis=await analyzeIntent(mode,input,session,undefined,read);
        assert.equal(analysis.intent.kind,scenario.expectedKind);
        const preview=await generatePreview(mode,{...input,...analysis},1,undefined,async analyzed=>
          tracks.filter(track=>!analyzed.genreIds.length||track.genres?.includes('Jazz')),
          (prompt,candidates,count)=>giga(prompt,candidates,count,undefined,session));
        assert.equal(preview.items[0].trackId,scenario.selected.id);
        assert.equal(preview.items[0].availability,'IN_APP');
        assert.ok(preview.items[0].reason);
      }
      assert.equal(oauthCalls,4);
      assert.equal(payloads.length,8);
      requestsByModel.push(payloads);
    }
    assert.deepEqual(requestsByModel[0],requestsByModel[1]);
  }finally{
    globalThis.fetch=fetchBefore;
    keys.forEach((key,index)=>{
      const value=envBefore[index];
      if(value===undefined)Deno.env.delete(key);
      else Deno.env.set(key,value);
    });
  }
});

Deno.test('GigaChat Pro intent and selection preserve the four supported modes', async () => {
  const originalFetch=globalThis.fetch;
  const keys=['GIGACHAT_AUTH_KEY','GIGACHAT_MODEL','GIGACHAT_SCOPE'];
  const originalEnv=keys.map(key=>Deno.env.get(key));
  const modes:GenerationMode[]=['QUICK','ADVANCED','PLACE','JOURNEY'];
  const read:Parameters<typeof analyzeIntent>[4]=async table=>table==='genres'
    ? [{id:1,name:'Jazz'},{id:2,name:'Rock'}] : [{id:1,name:'Peaceful'}];
  try {
    Deno.env.set('GIGACHAT_AUTH_KEY','pro-mode-fixture');
    Deno.env.set('GIGACHAT_SCOPE','GIGACHAT_API_PERS');
    Deno.env.set('GIGACHAT_MODEL','GigaChat-2-Pro');
    for(const mode of modes) {
      const context=mode==='PLACE'?[{title:'Париж'}]:mode==='JOURNEY'?[{title:'Париж'},{title:'Берлин'}]:[];
      const prompt='Спокойная прогулка, только джаз, без рока';
      const input={...request,prompt,context};
      const calls:string[]=[];
      globalThis.fetch=(url,init)=>{
        const host=new URL(String(url)).hostname;
        if(host==='ngw.devices.sberbank.ru') {
          calls.push('oauth');
          return Promise.resolve(Response.json({access_token:'pro-mode-fixture'}));
        }
        assert.equal(host,'api.giga.chat');
        const payload=JSON.parse(String(init?.body));
        assert.equal(payload.model,'GigaChat-2-Pro');
        assert.equal(payload.response_format.strict,true);
        const body=JSON.parse(payload.messages[1].content);
        assert.equal(body.mode,mode);
        assert.deepEqual(body.context.map((stop:{title:string})=>stop.title),context.map(stop=>stop.title));
        const stage=body.vocabulary?'intent':'selection';
        calls.push(stage);
        if(stage==='intent') {
          assert.deepEqual(payload.response_format.schema.required,
            ['kind','genres','moods','excludedGenres','requiredGenres','target']);
          assert.match(payload.response_format.schema.properties.genres.description,/8/);
          assert.match(payload.response_format.schema.properties.target.description,/150/);
        } else {
          assert.deepEqual(body.candidates.map((candidate:Track)=>candidate.title),[tracks[0].title]);
        }
        const result=stage==='intent'
          ? {...emptyIntent,kind:context.length?mode:'MOOD',genres:['Jazz'],moods:['Peaceful']}
          : {title:'Подборка',description:'Джаз для прогулки',items:[1]};
        return Promise.resolve(Response.json({choices:[{finish_reason:'stop',message:{content:JSON.stringify(result)}}]}));
      };
      const session={deadline:AbortSignal.timeout(3000)};
      const analyzed=await analyzeIntent(mode,input,session,undefined,read);
      assert.deepEqual(analyzed.intent.requiredGenres,['Jazz']);
      assert.deepEqual(analyzed.intent.excludedGenres,['Rock']);
      const preview=await generatePreview(mode,{...input,...analyzed},1,undefined,async()=>tracks,
        (payload,candidates,count)=>giga(payload,candidates,count,undefined,session));
      assert.equal(preview.items[0].trackId,tracks[0].id);
      assert.deepEqual(calls,['oauth','intent','selection']);
    }
  } finally {
    globalThis.fetch=originalFetch;
    for(const [index,key] of keys.entries()) {
      const value=originalEnv[index];
      if(value===undefined)Deno.env.delete(key);
      else Deno.env.set(key,value);
    }
  }
});
