import { HttpError } from './core.ts';
import type { RequestContext } from './observability.ts';
import type { NetworkCategory } from './provider_errors.ts';

type Stage = 'intent' | 'selection';
type ParserFailure = 'invalid_envelope' | 'missing_choices' | 'invalid_content_type' | 'empty_content' |
  'truncated_content' | 'invalid_json' | 'model_refusal' | 'unexpected_finish_reason';
type SelectionFailure = 'invalid_structure' | 'invalid_index' | 'unknown_index' | 'duplicate_index' |
  'empty_selection' | 'too_many_items';

export class SelectionValidationError extends HttpError {
  constructor(readonly reason: SelectionFailure) {
    super(502,'AI_INVALID_RESULT','AI returned invalid playlist');
  }
}

const object = (value:unknown) => value && typeof value==='object' && !Array.isArray(value)
  ? value as Record<string,unknown> : undefined;
const modelIds=['GigaChat','GigaChat-2','GigaChat-Pro','GigaChat-2-Pro','GigaChat-Max','GigaChat-2-Max','GigaChat-3-Ultra'];
const safeModel=(value:unknown)=>{
  if(typeof value!=='string')return null;
  const [name,version,...rest]=value.split(':');
  if(!modelIds.includes(name)||rest.length||(version!==undefined&&!/^\d{1,4}(?:\.\d{1,4}){1,5}$/.test(version)))return 'other';
  return value;
};
const safeCount=(value:unknown)=>typeof value==='number'&&Number.isSafeInteger(value)&&value>=0?value:null;
const knownReasons=['stop','length','blacklist','refusal','content_filter','function_call','tool_calls','error'];

export function decodeCompletion(raw:unknown) {
  const envelope=object(raw);
  const choices=Array.isArray(envelope?.choices)?envelope.choices:undefined;
  const choice=object(choices?.[0]);
  const message=object(choice?.message);
  const content=message?.content;
  const finish=choice?.finish_reason;
  const finishReason=typeof finish==='string'?(knownReasons.includes(finish)?finish:'other'):null;
  const usage=object(envelope?.usage);
  const observation={
    actualModel:safeModel(envelope?.model), finishReason, hasChoices:Boolean(choices?.length),
    contentType:content===undefined?'missing':content===null?'null':Array.isArray(content)?'array':typeof content,
    contentLength:typeof content==='string'?content.length:null,
    promptTokens:safeCount(usage?.prompt_tokens), completionTokens:safeCount(usage?.completion_tokens),
    totalTokens:safeCount(usage?.total_tokens), cachedTokens:safeCount(usage?.precached_prompt_tokens),
  };
  const failed=(parserFailure:ParserFailure)=>({observation,parserFailure,value:undefined});
  if(!envelope)return failed('invalid_envelope');
  if(!choices?.length)return failed('missing_choices');
  if ((typeof message?.refusal==='string'&&message.refusal.trim()) ||
    ['blacklist','refusal','content_filter'].includes(finishReason??'')) return failed('model_refusal');
  if(finishReason==='length')return failed('truncated_content');
  if(finishReason&&finishReason!=='stop')return failed('unexpected_finish_reason');
  if(typeof content!=='string')return failed('invalid_content_type');
  if(!content.trim())return failed('empty_content');
  try{
    return {observation,parserFailure:null,value:JSON.parse(content) as unknown};
  }catch(error){
    const incomplete=error instanceof SyntaxError &&
      /unexpected end|unterminated|end of (?:json|data)/i.test(error.message);
    return failed(incomplete?'truncated_content':'invalid_json');
  }
}

export function logGigaCompletion(ctx:RequestContext|undefined, fields:{
  stage:Stage;requestedModel:string;startedAt:number; status:number|null; candidateCount:number|null;
  decoded?:ReturnType<typeof decodeCompletion>;
  validationFailure?:SelectionFailure|'no_eligible_genres'|'unverified_audio_characteristics';
  transportFailure?:NetworkCategory|'http_error';
}) {
  if(!ctx)return;
  const {stage,requestedModel,startedAt,status,candidateCount,decoded,validationFailure,transportFailure}=fields;
  console.log(JSON.stringify({
    event:'gigachat_completion',requestId:ctx.requestId,operation:ctx.operation,stage,
    requestedModel:safeModel(requestedModel),status,candidateCount,durationMs:Math.round(performance.now()-startedAt),
    ...decoded?.observation,parserFailure:decoded?.parserFailure??null,validationFailure:validationFailure??null,
    transportFailure:transportFailure??null,
  }));
}

export function logGenerationEligibility(ctx:RequestContext|undefined,
  reason:'no_eligible_tracks'|'no_current_source',candidateCount:number){
  if(ctx)console.log(JSON.stringify({event:'gigachat_selection_eligibility',requestId:ctx.requestId,
    operation:ctx.operation,stage:'selection',reason,candidateCount,durationMs:Date.now()-ctx.startedAtMs}));
}
