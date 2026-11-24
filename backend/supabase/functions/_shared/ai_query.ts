// Match against catalog evidence only. Terms in a title carry less weight
// than the same terms in a track's genre or mood.
const musicTerms = [
  'ambient', 'chill', 'chillout', 'lofi', 'jazz', 'rock', 'pop', 'hip hop',
  'rap', 'techno', 'house', 'classical', 'piano', 'acoustic', 'instrumental',
  'electronic', 'electronica', 'synthwave', 'retrowave', 'downtempo',
  'metal', 'indie', 'folk', 'funk', 'soul', 'blues', 'reggae', 'disco',
  'calm', 'relaxing', 'meditation', 'sleep', 'study', 'focus',
  'atmospheric', 'trance', 'dubstep', 'drum and bass', 'dnb', 'edm', 'rnb',
  'latin', 'country', 'soundtrack', 'nature',
];
const situations: { pattern: RegExp; terms: string[] }[] = [
  { pattern: /(?:ноч\p{L}*|night|midnight).*?(?:поезд\p{L}*|дорог\p{L}*|маршрут|ед[еу]\p{L}*|drive|driving|ride)|(?:поезд\p{L}*|дорог\p{L}*|маршрут|drive|driving|ride).*?(?:ноч\p{L}*|night|midnight)/u,
    terms: ['night drive', 'night ride', 'synthwave', 'retrowave', 'downtempo', 'midnight', 'night', 'ночь', 'lofi'] },
  { pattern: /спокойн|расслаб|тих\p{L}* вечер|уютн|неспешн|relax|calm/u,
    terms: ['calm', 'relaxing', 'ambient', 'downtempo', 'chill', 'chillout', 'lofi', 'спокойное', 'спокойная'] },
  { pattern: /(?:^| )(?:работ[ауые]|уч[её]б\p{L}*|сосредоточ\p{L}*|концентрац\p{L}*|focus|study)(?: |$)/u,
    terms: ['focus', 'study', 'lofi', 'instrumental', 'ambient', 'piano', 'инструментальная'] },
  { pattern: /медитац|(?:^| )(?:сон|сна|sleep)(?: |$)/u,
    terms: ['meditation', 'sleep', 'ambient', 'calm'] },
  { pattern: /(?:^| )(?:поезд\p{L}*|дорог\p{L}*|путешеств\p{L}*|road trip)(?: |$)/u,
    terms: ['road trip', 'on the road', 'road', 'drive', 'journey', 'travel'] },
  { pattern: /клубн\p{L}* (?:культур\p{L}*|музык\p{L}*)|танцевальн\p{L}*/u,
    terms: ['house', 'techno', 'disco'] },
];
const aliases: [RegExp, string][] = [
  [/эмбиент/u, 'ambient'], [/лоу ?фай|лофи/u, 'lofi'], [/джаз/u, 'jazz'],
  [/электрон/u, 'electronic'], [/синтвейв/u, 'synthwave'], [/даунтемпо/u, 'downtempo'],
  [/классик|классическ/u, 'classical'], [/фортепиано|пианино/u, 'piano'],
  [/инструментальн/u, 'instrumental'], [/спокойн/u, 'calm'], [/медитац/u, 'meditation'],
  [/(?:^| )рок(?: |$)/u, 'rock'], [/техно/u, 'techno'], [/хаус/u, 'house'],
  [/хип ?хоп/u, 'hip hop'], [/рэп/u, 'rap'],
];

function normalize(value: string): string {
  return value.toLowerCase().normalize('NFKC').replaceAll('ё', 'е')
    .replace(/\blo[ -]fi\b/g, 'lofi').replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
}

function contains(value: string, term: string): boolean {
  return term.length > 1 && ` ${value} `.includes(` ${term} `);
}

function searchText(raw: string, infer: boolean) {
  const phrase = normalize(raw);
  const terms = new Set(musicTerms.filter(term => contains(phrase, term)));
  for (const [pattern, term] of aliases) if (pattern.test(phrase)) terms.add(term);
  if (infer) {
    // Prefer the specific night-drive intent to the generic road-trip intent.
    const situation = situations.find(({ pattern }) => pattern.test(phrase));
    for (const term of situation?.terms ?? []) terms.add(term);
  }
  return { phrase, terms: [...terms] };
}

export type MusicContext = { title: string; description?: string | null };

export function candidateRelevance(
  prompt: string, options: { genre: string; mood: string; context: readonly MusicContext[] },
) {
  const query = searchText(prompt, true);
  const directQuery = query.phrase.replace(
    /^(?:(?:хочу|включи|подбери|песни|треки|музык[ау]|исполнителя|please|play|songs by|music by|tracks by) )+/u, '',
  );
  const context = options.context.flatMap(({ title, description }) =>
    [searchText(title, true), searchText(description ?? '', true)]);
  const hints = [searchText(options.genre, false), searchText(options.mood, true)];
  const intent = situations.find(({ pattern }) => pattern.test(query.phrase));
  const locations = options.context.map(({ title }) => normalize(title));
  const genreHints = new Set([...query.terms, ...hints.flatMap(hint => hint.terms)]);

  function editorial(playlist: MusicContext, genres: string[]): number {
    const city = normalize(playlist.title.split(':', 1)[0]);
    if (city && locations.includes(city)) return 12;
    const text = normalize(playlist.title + ' ' + (playlist.description ?? ''));
    if (intent?.pattern.test(text)) return 8;
    return genres.some(genre => {
      const label = searchText(genre, false);
      return [label.phrase, ...label.terms].some(term => genreHints.has(term));
    }) ? 3 : 0;
  }

  function track(track: MusicContext & { artist: string; genres: string[]; moods: string[] }): number {
    const title = normalize(track.title);
    const credit = normalize(track.artist);
    const facets = [...track.genres, ...track.moods].flatMap(value => {
      const label = searchText(value, false);
      return [label.phrase, ...label.terms];
    });
    const score = ({ phrase, terms }: ReturnType<typeof searchText>) => {
      if (contains(title, phrase) || contains(credit, phrase) || (phrase && facets.includes(phrase))) return 12;
      const facetMatches = terms.filter(term => facets.includes(term)).length;
      const titleMatches = terms.filter(term => contains(title, term)).length;
      return Math.min(facetMatches, 2) * 5 + Math.min(titleMatches, 2) * 2;
    };
    return 3 * score({ ...query, phrase: directQuery }) +
      Math.max(0, ...context.map(score)) + Math.max(...hints.map(score));
  }
  return { track, editorial };
}
