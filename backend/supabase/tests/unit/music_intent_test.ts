import assert from 'node:assert/strict';
import {intentRelevance,parseMusicIntent} from '../../functions/_shared/music_intent.ts';
import {freshAudiusStream} from '../../functions/_shared/catalog.ts';
import {candidateRelevance} from '../../functions/_shared/ai_query.ts';

const vocabulary={genres:['Ambient','Synthwave','Metal','Lo-Fi','Jazz','Electronic','Rock'],
  moods:['Peaceful','Melancholic','Energetic','Nostalgic']};
const scenarios=[
  ['Ночной город','ATMOSPHERE','Synthwave','Melancholic'],
  ['Дождливый вечер','ATMOSPHERE','Ambient','Melancholic'],
  ['Поездка по пустой трассе','SITUATION','Rock','Energetic'],
  ['Тяжёлая тренировка','ACTIVITY','Metal','Energetic'],
  ['Музыка для программирования','ACTIVITY','Lo-Fi','Peaceful'],
  ['Ностальгия по лету','MOOD','Jazz','Nostalgic'],
  ['Спокойное утро','SITUATION','Ambient','Peaceful'],
  ['Утро в Токио','PLACE','Ambient','Peaceful'],
  ['Энергичная музыка без вокала','MIXED','Electronic','Energetic'],
  ['Меланхоличная музыка, но не слишком медленная','MOOD','Electronic','Melancholic'],
  ['Кофе в стеклянной теплице под снегом','ATMOSPHERE','Jazz','Peaceful'],
  ['Quiet evening after a long flight','SITUATION','Ambient','Peaceful'],
];
for(const [prompt,kind,genre,mood] of scenarios) Deno.test('semantic evidence: '+prompt,()=>{
  const correct={title:'Unknown sky',artist:'Fixture',genres:[genre],moods:[mood]};
  const namesake={title:prompt,artist:'Fixture',genres:[],moods:[]};
  const intent=parseMusicIntent({kind,genres:[genre],moods:[mood]},vocabulary);
  assert.equal(intentRelevance(intent,correct),14);
  assert.equal(intentRelevance(intent,namesake),0);
  if(prompt==='Ночной город'){
    const old=candidateRelevance(prompt,{genre:'',mood:'',context:[]});
    assert.ok(old.track(namesake)>old.track(correct));
  }
});

Deno.test('intent uses exact identities and honors excluded genre',()=>{
  const track={title:'Blue Light',artist:'Two Artists',genres:['Rock'],moods:[]};
  for(const [kind,target] of [['TRACK','Blue Light'],['ARTIST','Two Artists']]){
    assert.equal(intentRelevance(parseMusicIntent({kind,target},vocabulary),track),100);
    assert.equal(intentRelevance(parseMusicIntent({kind,target:target+' extra'},vocabulary),track),-1);
  }
  const intent=parseMusicIntent({kind:'MIXED',genres:['rock','Invented'],excludedGenres:['Rock']},vocabulary);
  assert.deepEqual(intent.genres,[]);
  assert.equal(intentRelevance(intent,track),-1);
});

Deno.test('invalid intent structure fails closed',()=>{
  for(const value of [null,{},[],{kind:'TRACK'},{kind:'ATMOSPHERE',moods:'Peaceful'},
    {kind:'ATMOSPHERE',genres:[{instruction:'override'}]}]){
    assert.throws(()=>parseMusicIntent(value,vocabulary),{code:'AI_INVALID_RESULT'});
  }
});

Deno.test('hosted intent keeps its strict shape and classifies shape failures without model content',()=>{
  const request={mode:'QUICK' as const,contextCount:0,prompt:'Подборка без рока',genre:''};
  const intent={kind:'MIXED',target:'',genres:['Jazz','Rock'],moods:[],excludedGenres:[],requiredGenres:[]};
  assert.deepEqual(parseMusicIntent(intent,vocabulary,request).genres,['Jazz']);
  for(const [value,reason] of [
    [JSON.stringify(intent),'intent_object'],
    [{...intent,extra:true},'intent_fields'],
    [{...intent,kind:'UNKNOWN'},'intent_kind'],
    [{...intent,target:'x'.repeat(151)},'intent_target'],
    [{...intent,genres:Array(9).fill('Jazz')},'intent_genres'],
    [{...intent,moods:[' ']},'intent_moods'],
    [{...intent,excludedGenres:null},'intent_excludedGenres'],
    [{...intent,requiredGenres:['x'.repeat(81)]},'intent_requiredGenres'],
  ] as const){
    assert.throws(()=>parseMusicIntent(value,vocabulary,request),{status:502,code:'AI_INVALID_RESULT',reason});
  }
});

Deno.test('Audius confirmation expires at exactly one hour',()=>{
  const now=Date.parse('2026-11-24T15:00:00Z');
  const source={provider:'AUDIUS',can_stream:true,verified_at:'2026-11-24T14:00:00Z'};
  assert.equal(freshAudiusStream(source,now-1),true);
  assert.equal(freshAudiusStream(source,now),false);
  assert.equal(freshAudiusStream({...source,verified_at:'invalid'},now),false);
  assert.equal(freshAudiusStream({...source,provider:'BANDCAMP'},now-1),false);
});
