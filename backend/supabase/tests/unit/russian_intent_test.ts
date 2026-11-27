import assert from 'node:assert/strict';
import { intentRelevance, parseMusicIntent } from '../../functions/_shared/music_intent.ts';

const vocabulary={genres:['Synthwave','Dark Jazz','Jazz','Rock','Electronic','Lo-Fi','Ambient','Hip-Hop/Rap'],
  moods:['Peaceful','Melancholic','Energetic','Atmospheric']};
const modelReply={kind:'MOOD',genres:['Jazz'],moods:['Peaceful'],requiredGenres:[],excludedGenres:[],target:''};

for (const [prompt,kind,genre] of [
  ['Ночной город','PLACE','Synthwave'],
  ['Париж ночью','PLACE','Dark Jazz'],
  ['Атмосфера ночных улиц','PLACE','Ambient'],
  ['Музыка для поездки по городу','JOURNEY','Lo-Fi'],
  ['Музыка как в Cyberpunk 2077','ATMOSPHERE','Electronic'],
  ['Прогулка по пустой набережной','ACTIVITY','Jazz'],
]) Deno.test('client mode and geographic context: '+prompt,()=>{
  for(const mode of ['QUICK','ADVANCED'] as const){
    const intent=parseMusicIntent({...modelReply,kind,genres:[genre]},vocabulary,{mode,contextCount:0,prompt,genre:''});
    assert.equal(intent.kind,kind==='PLACE'||kind==='JOURNEY'?'ATMOSPHERE':kind);
    assert.deepEqual(intent.genres,[genre]);
  }
});

Deno.test('only validated geographic context allows PLACE or JOURNEY intent',()=>{
  for(const [mode,contextCount] of [['PLACE',1],['JOURNEY',2]] as const){
    const request={mode,contextCount,prompt:'Музыка в дорогу',genre:''};
    assert.equal(parseMusicIntent({...modelReply,kind:mode},vocabulary,request).kind,mode);
    assert.equal(parseMusicIntent({...modelReply,kind:mode},vocabulary,{...request,contextCount:0}).kind,'ATMOSPHERE');
  }
});

for (const [prompt,excluded,unmatched] of [
  ['Тихая прогулка, без рока','Rock','Rock'],
  ['только не рэп','Hip-Hop/Rap','Hip Hop'],
  ['не добавляй металл','metal','Metal'],
  ['без жанра «shoegaze»','shoegaze','Shoegaze'],
  ['исключи hip-hop','Hip-Hop/Rap','Rap'],
  ['Rainy evening, without rock и hip-hop','Rock','Hip-Hop/Rap'],
]) Deno.test('model omission cannot remove explicit exclusion: '+prompt,()=>{
  const intent=parseMusicIntent(modelReply,vocabulary,{mode:'QUICK',contextCount:0,prompt,genre:''});
  assert.ok(intent.excludedGenres.includes(excluded));
  assert.equal(intentRelevance(intent,{title:'Track',artist:'Artist',genres:[unmatched],moods:['Peaceful']}),-1);
  assert.equal(intentRelevance(intent,{title:'Track',artist:'Artist',genres:[],moods:['Peaceful']}),-1);
  assert.ok(intentRelevance(intent,{title:'Track',artist:'Artist',genres:['Jazz'],moods:['Peaceful']})>0);
});

Deno.test('strict genres survive omission, reject conflicts and do not infer unverified musical attributes',()=>{
  const request={mode:'QUICK' as const,contextCount:0,prompt:'только джаз',genre:''};
  const intent=parseMusicIntent({...modelReply,genres:['Ambient']},vocabulary,request);
  assert.deepEqual(intent.requiredGenres,['Jazz']);
  assert.equal(intentRelevance(intent,{title:'Song',artist:'Artist',genres:['Ambient'],moods:['Peaceful']}),-1);
  assert.throws(()=>parseMusicIntent(modelReply,vocabulary,{...request,prompt:'только джаз, но без джаза'}),
    {code:'NO_CANDIDATES'});
  assert.throws(()=>parseMusicIntent(modelReply,vocabulary,{...request,prompt:'only metal'}),
    {code:'NO_CANDIDATES'});
  assert.throws(()=>parseMusicIntent({...modelReply,bpm:120},vocabulary,request),{code:'AI_INVALID_RESULT'});
  for(const prompt of ['Background jazz, no vocals','Только инструментальная музыка','Строго 120 BPM']) {
    assert.throws(()=>parseMusicIntent(modelReply,vocabulary,{...request,prompt}),{code:'NO_CANDIDATES'});
  }
  assert.doesNotThrow(()=>parseMusicIntent(modelReply,vocabulary,
    {...request,prompt:'Спокойная электроника без агрессивного звучания'}));
  assert.equal(parseMusicIntent({...modelReply,kind:'TRACK',target:'No Vocals'},vocabulary,
    {...request,prompt:'Найди композицию «No Vocals»'}).target,'No Vocals');
});
const cases=[
  ['Ночной город',{kind:'ATMOSPHERE',genres:['синтвейв'],moods:['атмосферное']}],
  ['Музыка для прогулки по городу поздней ночью',{kind:'ACTIVITY',genres:['электроника'],moods:['спокойная']}],
  ['Спокойная музыка для дождливого вечера',{kind:'SITUATION',genres:['эмбиент'],moods:['спокойная']}],
  ['Что-нибудь атмосферное и немного грустное',{kind:'MOOD',genres:['эмбиент'],moods:['грустная']}],
  ['Музыка для тренировки, только не рэп',{kind:'ACTIVITY',genres:['электроника'],moods:['энергичная'],excludedGenres:['рэп']}],
  ['Хочу спокойную электронику без агрессивного звучания',{kind:'MOOD',genres:['электроника'],moods:['спокойная']}],
  ['Музыка, под которую удобно программировать',{kind:'ACTIVITY',genres:['лоу-фай'],moods:['спокойная']}],
  ['Подбери что-нибудь в стиле synthwave и dark jazz',{kind:'GENRE',genres:['synthwave','dark jazz']}],
  ['Хочу что-нибудь похожее на музыку из Cyberpunk 2077',{kind:'ATMOSPHERE',genres:['синтвейв'],moods:['атмосферное']}],
  ['Подборка песен группы Кино',{kind:'ARTIST',target:'Кино'}],
  ['Найди музыку исполнителя The Weeknd',{kind:'ARTIST',target:'The Weeknd'}],
] as const;

for(const [prompt,reply] of cases) Deno.test('controlled Russian intent: '+prompt,()=>{
  const intent=parseMusicIntent(reply,vocabulary);
  const matched={title:'Original Title',artist:intent.target||'Original Artist',genres:intent.genres,moods:intent.moods};
  const unrelated={title:prompt,artist:'Other',genres:['Rock'],moods:[]};
  assert.ok(intentRelevance(intent,matched)>intentRelevance(intent,unrelated));
  assert.equal(intent.target,'target' in reply?reply.target:'');
});

for(const [ru,en,local,canonical] of [
  ['Ночной город','Night city','синтвейв','Synthwave'],
  ['Дождливый вечер','Rainy evening','эмбиент','Ambient'],
  ['Музыка для ночной поездки','Music for a night drive','электроника','Electronic'],
]) Deno.test('equivalent controlled intents: '+ru+' / '+en,()=>{
  assert.deepEqual(parseMusicIntent({kind:'ATMOSPHERE',genres:[local],moods:['атмосферное']},vocabulary),
    parseMusicIntent({kind:'ATMOSPHERE',genres:[canonical],moods:['Atmospheric']},vocabulary));
});

Deno.test('Russian exclusions and strict genres use canonical labels',()=>{
  const intent=parseMusicIntent({kind:'MIXED',genres:['джаз'],requiredGenres:['джаз'],excludedGenres:['рэп','рок']},vocabulary);
  assert.deepEqual(intent.requiredGenres,['Jazz']);
  assert.deepEqual(intent.excludedGenres,['Hip-Hop/Rap','Rock']);
  for(const genre of intent.excludedGenres)
    assert.equal(intentRelevance(intent,{title:'Match',artist:'Artist',genres:['Jazz',genre],moods:[]}),-1);
  const pace=parseMusicIntent({kind:'MOOD',moods:['грустная'],bpm:140,vocal:false},vocabulary);
  assert.deepEqual(pace.moods,['Melancholic']);
  assert.ok(!('bpm' in pace)&&!('vocal' in pace));
});
