import { editorialCities } from '../../../scripts/editorial-cities.ts';

export function editorialCapture(city: 'tokyo' | 'london', now = Date.now(), coverPath = 'editorial/qa.png', genreId = 1, tagId = 1,
  positions: 12 | 13 = 12) {
  const brief = editorialCities.find(row => row.code === city)!;
  const [first, second] = brief.queries;
  const concept = { atmosphere: '[BE-03 QA] ' + brief.atmosphere, mood: brief.mood,
    directions: brief.directions, queries: brief.queries };
  const rows = Array.from({length:positions+2},(_,i) => ({
    id:'BE03Fixture-' + city + '-' + (i+1),
    title:'[BE-03 QA] ' + brief.city + ' ' + (i+1),
    user:{name:'[BE-03 QA] Исполнитель ' + (i%4),id:'BE03Fixture-artist-' + (i%4)},
    permalink:'/be03-fixture/' + city + '-' + (i+1),
    duration:120+i, is_streamable:i<positions-2, genre:brief.directions[i%3], mood:'BE-03 QA',
    transient:'https://example.invalid/transient-secret',
  }));
  const evidence = rows.slice(0,positions+1).map(row => ({ id:row.id,
    verifiedAt:new Date(now).toISOString(),permalink:'https://audius.co'+row.permalink,
    streamingAllowed:row.is_streamable,streamProbePassed:row.is_streamable,exactRecording:true }));
  return { city,provenance:'SYNTHETIC_QA', completion:{model:'GigaChat-2-Pro',choices:[
    {finish_reason:'stop',message:{content:JSON.stringify(concept)}},
  ]},
  searches:[{query:first,rows},{query:second,rows:[rows[0]]}], evidence,
  selected:rows.slice(0,positions).map(row=>row.id), draft:{expectedRevision:0,coverPath,genreIds:[genreId],tagIds:[tagId]} };
}
