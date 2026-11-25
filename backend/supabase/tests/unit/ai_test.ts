import assert from 'node:assert/strict';
import type { Track } from '../../functions/_shared/catalog.ts';
import { decodeCompletion, logGigaCompletion } from '../../functions/_shared/gigachat_response.ts';
import { newRequestContext } from '../../functions/_shared/observability.ts';
import { validateGenerated } from '../../functions/v1-generate-playlist/index.ts';

const tracks:Track[]= [
  {id:'123e4567-e89b-42d3-a456-426614174000',title:'One',artist:'A',durationMs:120000,availability:'EXTERNAL_ONLY'},
  {id:'123e4567-e89b-42d3-a456-426614174001',title:'Two',artist:'B',durationMs:90000,availability:'IN_APP',
    genres:['Jazz'],moods:['Peaceful']},
];
const selection=(items:unknown[])=>({title:' Chill ',description:' Verified ',items});

Deno.test('selection preserves indices, public IDs, order, and uses only catalog evidence in explanations',()=>{
  const result=validateGenerated(selection([2,1]),tracks,2,
    {kind:'MOOD',genres:['Jazz'],moods:['Peaceful'],excludedGenres:[],target:''});
  assert.deepEqual(result.items,[
    {trackId:tracks[1].id,position:1,reason:'Совпали жанр «Jazz» и настроение «Peaceful» по данным каталога.'},
    {trackId:tracks[0].id,position:2,reason:'Выбор по уместности для запроса.'},
  ]);
  assert.equal(result.title,'Chill');
  assert.deepEqual(result.warnings,[]);
  assert.equal(validateGenerated(selection([2]),tracks,2).warnings.length,1);
});

Deno.test('selection rejects unknown, fractional, negative, duplicate, empty or substituted identifiers',()=>{
  for(const [items,reason] of [
    [[1.5],'invalid_index'],[[-1],'unknown_index'],[[0],'unknown_index'],[[3],'unknown_index'],
    [[1,1],'duplicate_index'],[[tracks[0].id],'invalid_index'],[[{trackId:tracks[0].id}],'invalid_index'],
    [['1'],'invalid_index'],[[],'empty_selection'],[[1,2,1],'too_many_items'],
  ] as const) {
    assert.throws(()=>validateGenerated(selection([...items]),tracks,2),{reason,code:'AI_INVALID_RESULT'});
  }
  for(const value of [null,[],{}, {...selection([1]),title:''},{...selection([1]),title:'x'.repeat(151)},
    {...selection([1]),items:'not-array'},{...selection([1]),reason:'arbitrary model claim'}]) {
    assert.throws(()=>validateGenerated(value,tracks,2),{reason:'invalid_structure'});
  }
});

Deno.test('completion decoder distinguishes empty, truncated, malformed, refused and unsupported answers',()=>{
  const body=(content:unknown,finish_reason='stop',refusal?:string)=>({choices:[{finish_reason,message:{content,refusal}}]});
  for(const [raw,reason] of [
    [null,'invalid_envelope'],[{choices:[]},'missing_choices'],[body([]),'invalid_content_type'],
    [body('  '),'empty_content'],[body('{}','length'),'truncated_content'],[body('{"title":"unfinished'),'truncated_content'],
    [body('{x:1}'),'invalid_json'],[body('','blacklist'),'model_refusal'],[body('','stop','private'),'model_refusal'],
    [body('{}','function_call'),'unexpected_finish_reason'],
  ]) assert.equal(decodeCompletion(raw).parserFailure,reason);
  assert.deepEqual(decodeCompletion(body('{"items":[2]}')).value,{items:[2]});
});

Deno.test('completion logs contain only allowlisted metadata and token counts',()=>{
  const decoded=decodeCompletion({
    model:'GigaChat-2:2.0.30.01',usage:{completion_tokens:2,prompt_tokens:10,precached_prompt_tokens:5,total_tokens:12},
    choices:[{finish_reason:'PRIVATE_CONTEXT',message:{content:'PRIVATE_ANSWER',refusal:'PRIVATE_REFUSAL'}}],
  });
  const oldLog=console.log;
  let line='';
  console.log=value=>{line=value;};
  try{
    logGigaCompletion(newRequestContext('v1-generate-playlist'),{stage:'selection',status:200,
      candidateCount:4,requestedModel:'PRIVATE_CONFIG',startedAt:performance.now(),decoded});
  }finally{console.log=oldLog;}
  assert.ok(!line.includes('PRIVATE'));
  const event=JSON.parse(line);
  assert.equal(event.actualModel,'GigaChat-2:2.0.30.01');
  assert.equal(event.finishReason,'other');
  assert.equal(event.contentType,'string');
  assert.equal(event.contentLength,14);
  assert.equal(event.completionTokens,2);
  assert.equal(event.cachedTokens,5);
  assert.equal(event.parserFailure,'model_refusal');
  assert.equal(event.candidateCount,4);
});
