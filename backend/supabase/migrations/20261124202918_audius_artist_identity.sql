CREATE TABLE internal.audius_artist_identity (
  provider_artist_id text PRIMARY KEY CHECK (provider_artist_id ~ '^[A-Za-z0-9-]{1,120}$'),
  artist_id uuid NOT NULL UNIQUE REFERENCES public.artists(id) ON DELETE CASCADE
);
REVOKE ALL ON TABLE internal.audius_artist_identity FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT ON TABLE internal.audius_artist_identity TO service_role;

CREATE FUNCTION internal.ingest_audius_with_artist(
 p_provider_id text,p_title text,p_artist text,p_duration_ms integer,p_permalink text,p_canonical_artist uuid
) RETURNS uuid LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path = '' AS $$
DECLARE v_track uuid; v_artist uuid;
BEGIN
 IF p_provider_id IS NULL OR length(btrim(p_provider_id)) NOT BETWEEN 1 AND 120
    OR p_title IS NULL OR length(btrim(p_title)) NOT BETWEEN 1 AND 300
    OR p_artist IS NULL OR length(btrim(p_artist)) NOT BETWEEN 1 AND 200
    OR p_duration_ms NOT BETWEEN 1 AND 7200000
    OR p_permalink IS NULL OR p_permalink !~ '^https://(www\.)?audius\.co/[A-Za-z0-9_\-/%?=.&]+$' THEN
   RAISE EXCEPTION 'INVALID_AUDIUS_CANDIDATE' USING ERRCODE='22023';
 END IF;
 SELECT ts.track_id INTO v_track FROM public.track_sources ts
 WHERE ts.provider='AUDIUS' AND ts.provider_track_id=p_provider_id FOR UPDATE;
 IF v_track IS NOT NULL THEN RETURN v_track; END IF;
 -- Deduplicate concurrent ingestion for the same provider ID.
 PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext('AUDIUS'),pg_catalog.hashtext(p_provider_id));
 SELECT ts.track_id INTO v_track FROM public.track_sources ts
 WHERE ts.provider='AUDIUS' AND ts.provider_track_id=p_provider_id;
 IF v_track IS NOT NULL THEN RETURN v_track; END IF;
 INSERT INTO public.tracks(title,duration_ms) VALUES (p_title,p_duration_ms) RETURNING id INTO v_track;
 v_artist := p_canonical_artist;
 IF v_artist IS NULL THEN
   INSERT INTO public.artists(name) VALUES (p_artist) RETURNING id INTO v_artist;
 END IF;
 INSERT INTO public.track_artists(track_id,artist_id,credit_order)
 VALUES (v_track,v_artist,1);
 -- Streaming/caching/downloading forbidden until live and rights verification.
 INSERT INTO public.track_sources(track_id,provider,provider_track_id,external_url,
    can_stream,can_cache,can_download,verified_at)
 VALUES (v_track,'AUDIUS',p_provider_id,p_permalink,false,false,false,now());
 RETURN v_track;
END;$$;
REVOKE ALL ON FUNCTION internal.ingest_audius_with_artist(text,text,text,integer,text,uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION internal.ingest_audius_with_artist(text,text,text,integer,text,uuid) TO service_role;

CREATE OR REPLACE FUNCTION public.edge_import_audius_batch(p_items jsonb)
RETURNS TABLE(id uuid, provider_track_id text)
LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path = '' AS $$
DECLARE
  item record;
  track_uuid uuid;
  facet_id smallint;
  provider_artist text;
  primary_artist uuid;
  canonical_artist uuid;
BEGIN
  IF jsonb_typeof(p_items) IS DISTINCT FROM 'array' THEN
    RAISE EXCEPTION 'INVALID_AUDIUS_BATCH' USING ERRCODE = '22023';
  END IF;
  IF jsonb_array_length(p_items) NOT BETWEEN 1 AND 25 THEN
    RAISE EXCEPTION 'INVALID_AUDIUS_BATCH' USING ERRCODE = '22023';
  END IF;

  -- Track batches may overlap across workers. Acquire all artist locks in a stable order first.
  FOR provider_artist IN
    SELECT DISTINCT x."artistId" FROM jsonb_to_recordset(p_items) x("artistId" text)
    WHERE x."artistId" ~ '^[A-Za-z0-9-]{1,120}$' ORDER BY 1
  LOOP
    PERFORM pg_advisory_xact_lock(hashtext('AUDIUS_ARTIST'),hashtext(provider_artist));
  END LOOP;

  -- Lock candidates in a consistent order across overlapping batches.
  FOR item IN SELECT * FROM jsonb_to_recordset(p_items) AS x(
    id text, title text, artist text, "durationMs" integer, permalink text,
    genre text, mood text, "canStream" boolean, "artistId" text
  ) ORDER BY x.id LOOP
    IF item."canStream" IS NULL OR item.id IS NULL OR item.id !~ '^[A-Za-z0-9-]{1,120}$'
      OR length(item.genre) > 80 OR length(item.mood) > 80 THEN
      RAISE EXCEPTION 'INVALID_AUDIUS_CANDIDATE' USING ERRCODE = '22023';
    END IF;
    canonical_artist := NULL;
    IF item."artistId" ~ '^[A-Za-z0-9-]{1,120}$' THEN
      SELECT a.artist_id INTO canonical_artist FROM internal.audius_artist_identity a
      WHERE a.provider_artist_id=item."artistId";
    END IF;
    track_uuid := internal.ingest_audius_with_artist(
      item.id, item.title, item.artist, item."durationMs", item.permalink, canonical_artist
    );
    IF item."artistId" ~ '^[A-Za-z0-9-]{1,120}$' THEN
      SELECT ta.artist_id INTO primary_artist FROM public.track_artists ta
      WHERE ta.track_id=track_uuid AND ta.credit_order=1;
      SELECT a.artist_id INTO canonical_artist FROM internal.audius_artist_identity a
      WHERE a.provider_artist_id=item."artistId";
      IF canonical_artist IS NULL THEN
        -- Only claim a primary credit when this confirmed provider identity is observed.
        IF primary_artist IS NOT NULL THEN
          INSERT INTO internal.audius_artist_identity VALUES (item."artistId",primary_artist)
          ON CONFLICT DO NOTHING RETURNING artist_id INTO canonical_artist;
        END IF;
        IF canonical_artist IS NULL THEN
          INSERT INTO public.artists(name) VALUES (item.artist) RETURNING id INTO canonical_artist;
          INSERT INTO internal.audius_artist_identity VALUES (item."artistId",canonical_artist);
        END IF;
      END IF;
      UPDATE public.artists a SET name=item.artist WHERE a.id=canonical_artist AND a.name IS DISTINCT FROM item.artist;
      IF primary_artist IS DISTINCT FROM canonical_artist THEN
        DELETE FROM public.track_artists ta WHERE ta.track_id=track_uuid AND ta.credit_order=1;
        INSERT INTO public.track_artists(track_id,artist_id,credit_order)
          VALUES (track_uuid,canonical_artist,1) ON CONFLICT DO NOTHING;
      END IF;
    END IF;
    PERFORM public.edge_mark_streamable(track_uuid, item.id, item."canStream");
    id := track_uuid;
    provider_track_id := item.id;
    RETURN NEXT;

  END LOOP;

  -- Taxonomy writes are serialized independently of provider track IDs.
  PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext('AUDIUS_FACETS'), 0);

  -- Sorted track locks are acquired before touching shared facet rows.
  FOR item IN SELECT t.track_id AS track_uuid, x.genre, x.mood
    FROM jsonb_to_recordset(p_items) AS x(id text, genre text, mood text)
    JOIN public.track_sources t ON t.provider = 'AUDIUS' AND t.provider_track_id = x.id
  LOOP
    IF length(btrim(item.genre)) > 0 THEN
      SELECT g.id INTO facet_id FROM public.genres g WHERE lower(g.name) = lower(btrim(item.genre));
      IF facet_id IS NULL THEN
        INSERT INTO public.genres(name) VALUES (btrim(item.genre)) ON CONFLICT (lower(name)) DO NOTHING;
        SELECT g.id INTO facet_id FROM public.genres g WHERE lower(g.name) = lower(btrim(item.genre));
      END IF;
      INSERT INTO public.track_genres(track_id, genre_id)
        VALUES (item.track_uuid, facet_id) ON CONFLICT DO NOTHING;
    END IF;
    IF length(btrim(item.mood)) > 0 THEN
      SELECT m.id INTO facet_id FROM public.moods m WHERE lower(m.name) = lower(btrim(item.mood));
      IF facet_id IS NULL THEN
        INSERT INTO public.moods(name) VALUES (btrim(item.mood)) ON CONFLICT (lower(name)) DO NOTHING;
        SELECT m.id INTO facet_id FROM public.moods m WHERE lower(m.name) = lower(btrim(item.mood));
      END IF;
      INSERT INTO public.track_moods(track_id, mood_id)
        VALUES (item.track_uuid, facet_id) ON CONFLICT DO NOTHING;
    END IF;
  END LOOP;
END;
$$;
