import assert from 'node:assert/strict';
import { intentRelevance, parseMusicIntent } from '../../functions/_shared/music_intent.ts';

const vocabulary={genres:['Synthwave','Dark Jazz','Jazz','Rock','Electronic','Lo-Fi','Ambient','Hip-Hop/Rap'],
  moods:['Peaceful','Melancholic','Energetic','Atmospheric']};
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
