// Bounded, deterministic catalog search fallback. Pure code with no provider calls.
// Natural-language prompt is still sent intact to the AI model. This function only
// chooses safe catalog search terms and does not fabricate track IDs or provider data.

const stopwords = new Set([
  'a', 'an', 'and', 'any', 'are', 'at', 'be', 'by', 'can', 'for', 'from', 'i',
  'in', 'is', 'it', 'me', 'my', 'of', 'on', 'or', 'please', 'some', 'that', 'the',
  'these', 'this', 'to', 'want', 'with', 'would', 'you', 'make', 'create',
  'music', 'musical', 'song', 'songs', 'track', 'tracks', 'sound', 'sounds',
  'playlist', 'mix', 'give', 'listen', 'listening', 'nice', 'good',
  'в', 'во', 'для', 'и', 'или', 'из', 'к', 'мне', 'мой', 'на', 'не', 'ну', 'о',
  'по', 'под', 'про', 'с', 'со', 'хочу', 'чтобы', 'это', 'эти', 'песни',
  'песен', 'музыка', 'музыку', 'музыки', 'музыкальный', 'плейлист', 'трек',
  'треки', 'подборка', 'подборку', 'что', 'нибудь', 'какой', 'сделай',
]);

const genres = new Set([
  'ambient','atmospheric','chill','chillout','lofi','jazz','rock','pop',
  'hiphop','rap','techno','house','trance','dubstep','drum','bass','dnb',
  'classical','piano','acoustic','instrumental','electronic','edm',
  'synthwave','retrowave','metal','indie','folk','funk','soul','rnb',
  'blues','reggae','disco','latin','country','soundtrack','meditation',
  'relaxing','calm','sleep','study','focus','nature',
]);

function canonicalToken(input: string): string {
  const token=input.toLowerCase();
  if (/^эмбиент/.test(token) || /^атмосферн/.test(token)) return 'ambient';
  if (/^спокойн/.test(token) || /^расслабл/.test(token)) return 'calm';
  if (/^джаз/.test(token)) return 'jazz';
  if (/^рок/.test(token)) return 'rock';
  if (/^техно/.test(token)) return 'techno';
  if (/^хаус/.test(token)) return 'house';
  if (/^классич/.test(token) || /^классик/.test(token)) return 'classical';
  if (/^фортепиан/.test(token) || /^пианино/.test(token)) return 'piano';
  if (/^электронн/.test(token)) return 'electronic';
  if (/^рэп/.test(token) || /^реп/.test(token)) return 'rap';
  if (/^лоу.?фай/.test(token) || /^лофи/.test(token)) return 'lofi';
  if (/^хип.?хоп/.test(token)) return 'hiphop';
  if (/^медитац/.test(token)) return 'meditation';
  if (/^сон$/.test(token) || /^сна$/.test(token)) return 'sleep';
  return token.replace(/-/g,'');
}

function tokens(value: string): string[] {
  return (value.toLowerCase().match(/[\p{L}\p{N}]+(?:-[\p{L}\p{N}]+)*/gu) ?? [])
    .map(canonicalToken).filter(v=>v.length>=3 && !stopwords.has(v));
}

/** Searches the original phrase, then at most three targeted alternatives. */
export function candidateQueries(
  primary: string, options: { genre?:string; mood?:string }={}, max=4,
): string[] {
  const result:string[]=[];
  const seen=new Set<string>();
  const add=(value:string)=>{
    const term=value.replace(/\s+/g,' ').trim().slice(0,200);
    const key=term.toLowerCase();
    if(term && !seen.has(key) && result.length<max){result.push(term);seen.add(key);}
  };
  add(primary);
  if(options.genre) add(options.genre);
  if(options.mood) add(options.mood);
  const words=tokens(primary);
  // Domain vocabulary first: avoid searching meaningless generic words ("sounds", "playlist").
  for(const w of words){if(genres.has(w))add(w);}
  // Last resort: search a few informative keywords rather than the entire sentence.
  for(const w of words){if(!genres.has(w))add(w);}
  return result;
}

/** No fallback after a provider/DB error: those errors should remain observable. */
export async function firstMatchingCandidates<T>(
  queries:readonly string[], search:(query:string)=>Promise<T[]>,
):Promise<T[]> {
  for(const query of queries){
    const found=await search(query);
    if(found.length) return found;
  }
  return [];
}
