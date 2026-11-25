-- psql -X -v BE01_ISOLATED=1 -f benchmark-generation.sql against an isolated database.
\if :{?BE01_ISOLATED}
\else
  \echo Set BE01_ISOLATED only on a disposable local database.
  \quit
\endif
\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE be01_measurements(scale integer, approach text, elapsed_ms double precision, rows integer);
DO $$
DECLARE
  scale integer;
  previous_scale integer := 0;
  genre smallint;
  started timestamptz;
  cursor_id uuid;
  results integer;
  page_rows integer;
BEGIN
  INSERT INTO public.genres(name) VALUES ('BE01 benchmark ambient') RETURNING id INTO genre;
  FOREACH scale IN ARRAY ARRAY[300,1000,5000,10000] LOOP
    INSERT INTO public.tracks(id,title,duration_ms)
    SELECT md5('be01-bench-'||i)::uuid,'BE01 benchmark '||i,90000
    FROM generate_series(previous_scale+1,scale) i;
    INSERT INTO public.track_genres(track_id,genre_id)
    SELECT md5('be01-bench-'||i)::uuid,genre FROM generate_series(previous_scale+1,scale) i
    WHERE i%5=0;
    INSERT INTO public.track_sources(track_id,provider,provider_track_id,external_url,can_stream,verified_at)
    SELECT md5('be01-bench-'||i)::uuid,'AUDIUS','Be01Bench'||i,'https://audius.co/bench/be01-'||i,true,now()
    FROM generate_series(previous_scale+1,scale) i;
    previous_scale:=scale;
    ANALYZE public.tracks;
    ANALYZE public.track_sources;
    ANALYZE public.track_genres;
    FOR sample IN 0..20 LOOP
      started:=clock_timestamp();
      results:=0;
      cursor_id:='00000000-0000-0000-0000-000000000000';
      LOOP
        SELECT count(*), (array_agg(page.id ORDER BY page.id DESC))[1] INTO page_rows,cursor_id FROM (
          SELECT id FROM public.tracks WHERE id>cursor_id ORDER BY id LIMIT 100
        ) page;
        results:=results+page_rows;
        EXIT WHEN page_rows<100;
      END LOOP;
      IF sample>0 THEN
        INSERT INTO be01_measurements VALUES (scale,'old: id pages only',1000*extract(epoch FROM clock_timestamp()-started),results);
      END IF;
      started:=clock_timestamp();
      SELECT count(*) INTO results FROM public.edge_generation_track_ids(
        ARRAY['be01 benchmark ambient'],'{}','{}','{}','{}','ATMOSPHERE','','{}','{}',240);
      IF sample>0 THEN
        INSERT INTO be01_measurements VALUES (scale,'new: evidence RPC',1000*extract(epoch FROM clock_timestamp()-started),results);
      END IF;
    END LOOP;
  END LOOP;
END;
$$;
SELECT scale,approach,count(*) AS repeats,min(rows) AS returned_rows,
  round(percentile_cont(0.50) WITHIN GROUP(ORDER BY elapsed_ms)::numeric,3) AS p50_ms,
  round(percentile_cont(0.95) WITHIN GROUP(ORDER BY elapsed_ms)::numeric,3) AS p95_ms
FROM be01_measurements GROUP BY scale,approach ORDER BY scale,approach;
EXPLAIN (ANALYZE,BUFFERS) SELECT id FROM public.tracks ORDER BY id LIMIT 100;
EXPLAIN (ANALYZE,BUFFERS) SELECT * FROM public.edge_generation_track_ids(
  ARRAY['be01 benchmark ambient'],'{}','{}','{}','{}','ATMOSPHERE','','{}','{}',240);
EXPLAIN (ANALYZE,BUFFERS) SELECT tg.track_id FROM public.track_genres tg
  JOIN public.genres g ON g.id=tg.genre_id WHERE lower(g.name)='be01 benchmark ambient';
ROLLBACK;
