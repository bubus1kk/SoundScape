-- Drafts live outside PostgREST's exposed schemas. Public read paths still use playlists.
CREATE TABLE internal.editorial_drafts (
  place_id uuid PRIMARY KEY REFERENCES public.places(id),
  city_code text NOT NULL UNIQUE,
  playlist_id uuid UNIQUE REFERENCES public.playlists(id) ON DELETE SET NULL,
  revision integer NOT NULL DEFAULT 1 CHECK (revision > 0),
  title text NOT NULL DEFAULT '',
  description text NOT NULL DEFAULT '',
  cover_path text,
  genre_ids smallint[] NOT NULL DEFAULT '{}',
  tag_ids smallint[] NOT NULL DEFAULT '{}',
  track_ids uuid[] NOT NULL DEFAULT '{}',
  approved_revision integer,
  approved_by uuid,
  approved_at timestamptz,
  published_revision integer,
  CHECK (num_nonnulls(approved_revision, approved_by, approved_at) IN (0, 3)),
  CHECK (approved_revision IS NULL OR approved_revision = revision)
);
ALTER TABLE internal.editorial_drafts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON internal.editorial_drafts FROM PUBLIC, anon, authenticated, service_role;
CREATE INDEX editorial_drafts_cover_idx ON internal.editorial_drafts(cover_path) WHERE cover_path IS NOT NULL;

-- Only disposable QA databases are opted in. This table is empty after migration.
CREATE TABLE internal.editorial_fixture_target (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  marker uuid NOT NULL
);
ALTER TABLE internal.editorial_fixture_target ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON internal.editorial_fixture_target FROM PUBLIC, anon, authenticated, service_role;

CREATE FUNCTION internal.editorial_capitals()
RETURNS TABLE(code text, country text, title text, aliases text[])
LANGUAGE sql IMMUTABLE SET search_path = '' AS $$
  VALUES
  ('tokyo','JP','Токио',ARRAY['tokyo','tōkyō','токио','東京']),
  ('seoul','KR','Сеул',ARRAY['seoul','сеул','서울']),
  ('bangkok','TH','Бангкок',ARRAY['bangkok','бангкок']),
  ('new-delhi','IN','Нью-Дели',ARRAY['new delhi','new-delhi','нью-дели','нью дели']),
  ('jakarta','ID','Джакарта',ARRAY['jakarta','джакарта']),
  ('london','GB','Лондон',ARRAY['london','лондон']),
  ('paris','FR','Париж',ARRAY['paris','париж']),
  ('berlin','DE','Берлин',ARRAY['berlin','берлин']),
  ('rome','IT','Рим',ARRAY['rome','roma','рим']),
  ('madrid','ES','Мадрид',ARRAY['madrid','мадрид']),
  ('stockholm','SE','Стокгольм',ARRAY['stockholm','стокгольм']),
  ('moscow','RU','Москва',ARRAY['moscow','москва']),
  ('cairo','EG','Каир',ARRAY['cairo','каир']),
  ('nairobi','KE','Найроби',ARRAY['nairobi','найроби']),
  ('washington','US','Вашингтон',ARRAY['washington','washington dc','washington, dc','washington, d.c.','вашингтон']),
  ('mexico-city','MX','Мехико',ARRAY['mexico city','ciudad de méxico','ciudad de mexico','мехико']),
  ('brasilia','BR','Бразилиа',ARRAY['brasilia','brasília','бразилиа','бразилия']),
  ('buenos-aires','AR','Буэнос-Айрес',ARRAY['buenos aires','буэнос-айрес','буэнос айрес']),
  ('canberra','AU','Канберра',ARRAY['canberra','канберра']),
  ('wellington','NZ','Веллингтон',ARRAY['wellington','веллингтон']);
$$;

CREATE FUNCTION internal.editorial_cities()
RETURNS TABLE(code text, country text, title text, place_id uuid, matches bigint, map_visible boolean)
LANGUAGE sql STABLE SET search_path = '' AS $$
  SELECT c.code,c.country,c.title,
    CASE WHEN count(p.id)=1 THEN (array_agg(p.id))[1] END,
    count(p.id),coalesce(bool_or(p.is_published),false) AND count(p.id)=1
  FROM internal.editorial_capitals() c
  LEFT JOIN public.places p ON p.country_code=c.country AND lower(btrim(p.title))=ANY(c.aliases)
  GROUP BY c.code,c.country,c.title;
$$;

-- The flag is trusted Auth app metadata, assigned out of band to the owner's ADMIN account.
CREATE FUNCTION internal.editorial_owner(p_user uuid)
RETURNS boolean LANGUAGE sql STABLE SET search_path = '' AS $$
  SELECT internal.active_account(p_user) AND EXISTS (
    SELECT 1 FROM public.profiles p JOIN auth.users u ON u.id=p.id
    WHERE p.id=p_user AND p.role_code='ADMIN'
      AND u.raw_app_meta_data->'soundscape_editorial_owner'='true'::jsonb
  );
$$;

CREATE FUNCTION internal.require_editorial(p_owner boolean DEFAULT false)
RETURNS uuid LANGUAGE plpgsql STABLE SET search_path = '' AS $$
DECLARE caller uuid := internal.require_user();
BEGIN
  IF NOT internal.is_editor() THEN PERFORM internal.fail('PT403','EDITORIAL_EDITOR_REQUIRED'); END IF;
  IF p_owner AND NOT internal.editorial_owner(caller) THEN
    PERFORM internal.fail('PT403','EDITORIAL_OWNER_REQUIRED');
  END IF;
  RETURN caller;
END;
$$;

CREATE FUNCTION internal.editorial_place(p_data jsonb, p_allow_existing boolean DEFAULT false)
RETURNS uuid LANGUAGE plpgsql STABLE SET search_path = '' AS $$
DECLARE v_place uuid;
BEGIN
  PERFORM internal.assert_object(p_data);
  IF NOT (p_data ? 'placeId') THEN PERFORM internal.fail('PT422','EDITORIAL_PLACE_ID_REQUIRED'); END IF;
  IF jsonb_typeof(p_data->'placeId')<>'string'
     OR (p_data->>'placeId') !~* '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$' THEN
    PERFORM internal.fail('PT422','EDITORIAL_INVALID_PLACE_ID');
  END IF;
  v_place := (p_data->>'placeId')::uuid;
  IF NOT EXISTS (SELECT FROM internal.editorial_cities() c WHERE c.place_id=v_place)
    AND NOT (p_allow_existing AND EXISTS (SELECT FROM internal.editorial_drafts d WHERE d.place_id=v_place)) THEN
    PERFORM internal.fail('PT404','EDITORIAL_PLACE_NOT_FOUND_OR_AMBIGUOUS');
  END IF;
  RETURN v_place;
END;
$$;

CREATE FUNCTION internal.editorial_revision(p_data jsonb, p_min integer)
RETURNS integer LANGUAGE plpgsql IMMUTABLE SET search_path = '' AS $$
BEGIN
  IF jsonb_typeof(p_data->'expectedRevision') IS DISTINCT FROM 'number'
     OR (p_data->>'expectedRevision') !~ '^[0-9]{1,10}$'
     OR (p_data->>'expectedRevision')::numeric NOT BETWEEN p_min AND 2147483647 THEN
    PERFORM internal.fail('PT422','EDITORIAL_INVALID_REVISION');
  END IF;
  RETURN (p_data->>'expectedRevision')::integer;
END;
$$;

CREATE FUNCTION internal.editorial_payload(d internal.editorial_drafts)
RETURNS jsonb LANGUAGE sql STABLE SET search_path = '' AS $$
  SELECT jsonb_build_object(
    'placeId',d.place_id,'playlistId',d.playlist_id,'revision',d.revision,
    'title',d.title,'description',d.description,'coverPath',d.cover_path,
    'genreIds',d.genre_ids,'tagIds',d.tag_ids,'trackIds',d.track_ids,
    'approvedRevision',d.approved_revision,'approvedBy',d.approved_by,'approvedAt',d.approved_at,
    'publishedRevision',d.published_revision,
    'isPublished',EXISTS (SELECT 1 FROM public.playlists p WHERE p.id=d.playlist_id
      AND p.visibility='PUBLIC' AND p.published_at IS NOT NULL)
  );
$$;

CREATE FUNCTION internal.editorial_cover(p_path text)
RETURNS boolean LANGUAGE sql STABLE SET search_path = '' AS $$
  SELECT p_path ~ '^editorial/([A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\.(jpg|jpeg|png|webp)$'
    AND (p_path LIKE 'editorial/covers/%'
      OR EXISTS (SELECT FROM public.places p WHERE p.cover_path=p_path)
      OR EXISTS (SELECT FROM public.playlists p WHERE p.is_editorial AND p.cover_path=p_path)
      OR EXISTS (SELECT FROM internal.editorial_drafts d WHERE d.cover_path=p_path))
    AND EXISTS (
      SELECT 1 FROM storage.objects o WHERE o.bucket_id='soundscape-media' AND o.name=p_path
        AND o.metadata->>'mimetype' = CASE substring(p_path FROM '[^.]+$')
          WHEN 'png' THEN 'image/png' WHEN 'webp' THEN 'image/webp' ELSE 'image/jpeg' END
        AND CASE WHEN o.metadata->>'size' ~ '^[0-9]{1,9}$'
          THEN (o.metadata->>'size')::integer BETWEEN 1 AND 10485760 ELSE false END
    );
$$;

-- Content under an editorial path is immutable. The editor uploads a new path for a replacement.
CREATE POLICY soundscape_editorial_immutable ON storage.objects AS RESTRICTIVE
  FOR UPDATE TO authenticated
  USING (bucket_id <> 'soundscape-media' OR split_part(name,'/',1) <> 'editorial')
  WITH CHECK (bucket_id <> 'soundscape-media' OR split_part(name,'/',1) <> 'editorial');
CREATE POLICY soundscape_editorial_managed_upload ON storage.objects AS RESTRICTIVE
  FOR INSERT TO authenticated
  WITH CHECK (bucket_id <> 'soundscape-media' OR name NOT LIKE 'editorial/covers/%');

CREATE OR REPLACE FUNCTION internal.media_referenced(asset_path text)
RETURNS boolean LANGUAGE sql STABLE SET search_path = '' AS $$
  SELECT EXISTS (SELECT 1 FROM public.profiles p WHERE p.avatar_path = asset_path)
    OR EXISTS (SELECT 1 FROM public.playlists p WHERE p.cover_path = asset_path)
    OR EXISTS (SELECT 1 FROM public.pin_photos f WHERE f.storage_path = asset_path)
    OR EXISTS (SELECT 1 FROM public.event_photos f WHERE f.storage_path = asset_path)
    OR EXISTS (SELECT 1 FROM public.places p WHERE p.cover_path = asset_path)
    OR EXISTS (SELECT 1 FROM internal.editorial_drafts d WHERE d.cover_path = asset_path);
$$;

CREATE FUNCTION internal.guard_editorial_cover_delete()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  IF OLD.bucket_id='soundscape-media' AND OLD.name LIKE 'editorial/%'
      AND internal.media_referenced(OLD.name) THEN
    PERFORM internal.fail('PT409','EDITORIAL_COVER_IN_USE');
  END IF;
  RETURN OLD;
END;
$$;
REVOKE ALL ON FUNCTION internal.guard_editorial_cover_delete() FROM PUBLIC, anon, authenticated, service_role;
CREATE TRIGGER guard_editorial_cover_delete BEFORE DELETE ON storage.objects
  FOR EACH ROW EXECUTE FUNCTION internal.guard_editorial_cover_delete();

CREATE FUNCTION public.edge_can_upload_editorial_cover(p_user uuid)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT internal.active_account(p_user) AND EXISTS (
    SELECT FROM public.profiles p WHERE p.id=p_user AND p.role_code IN ('EDITOR','ADMIN')
  );
$$;
REVOKE ALL ON FUNCTION public.edge_can_upload_editorial_cover(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.edge_can_upload_editorial_cover(uuid) TO service_role;

CREATE FUNCTION internal.editorial_issues(d internal.editorial_drafts)
RETURNS jsonb LANGUAGE sql STABLE SET search_path = '' AS $$
  WITH issues(code, item) AS (
    SELECT 'CITY_NOT_ON_PUBLIC_MAP',NULL::uuid WHERE NOT EXISTS (
      SELECT 1 FROM internal.editorial_cities() c
      WHERE c.place_id=d.place_id AND c.code=d.city_code AND c.map_visible)
    UNION ALL SELECT 'TITLE_REQUIRED',NULL WHERE char_length(btrim(d.title)) NOT BETWEEN 1 AND 150
    UNION ALL SELECT 'DESCRIPTION_REQUIRED',NULL WHERE char_length(btrim(d.description)) NOT BETWEEN 1 AND 2000
    UNION ALL SELECT 'COVER_REQUIRED_OR_INVALID',NULL WHERE NOT coalesce(internal.editorial_cover(d.cover_path),false)
    UNION ALL SELECT 'GENRES_REQUIRED',NULL WHERE cardinality(d.genre_ids)=0
      OR EXISTS (SELECT FROM unnest(d.genre_ids) g(id) WHERE NOT EXISTS (SELECT FROM public.genres x WHERE x.id=g.id))
    UNION ALL SELECT 'TAGS_REQUIRED',NULL WHERE cardinality(d.tag_ids)=0
      OR EXISTS (SELECT FROM unnest(d.tag_ids) t(id) WHERE NOT EXISTS (SELECT FROM public.tags x WHERE x.id=t.id))
    UNION ALL SELECT 'TRACK_COUNT_12_TO_15',NULL WHERE cardinality(d.track_ids) NOT BETWEEN 12 AND 15
    UNION ALL SELECT 'DUPLICATE_TRACK',t.id FROM unnest(d.track_ids) t(id) GROUP BY t.id HAVING count(*)>1
    UNION ALL SELECT 'UNKNOWN_TRACK',t.id FROM unnest(d.track_ids) t(id)
      WHERE NOT EXISTS (SELECT FROM public.tracks x WHERE x.id=t.id)
    UNION ALL SELECT CASE WHEN EXISTS (
      SELECT FROM public.track_sources x WHERE x.track_id=t.id AND x.provider='AUDIUS' AND x.can_stream
    ) THEN 'IN_APP_RECHECK_REQUIRED' ELSE 'NO_VERIFIED_SOURCE' END,t.id
    FROM unnest(d.track_ids) t(id) JOIN public.tracks tr ON tr.id=t.id
    WHERE NOT EXISTS (
      SELECT FROM public.track_sources s WHERE s.track_id=t.id AND s.verified_at<=statement_timestamp()
      AND (s.provider_track_id NOT LIKE 'BE03Fixture-%'
        OR EXISTS (SELECT FROM internal.editorial_fixture_target))
      AND (
        (s.provider='AUDIUS' AND s.can_stream AND s.verified_at>statement_timestamp()-interval '1 hour')
        OR (NOT s.can_stream AND (
          (s.provider='AUDIUS' AND s.external_url ~ '^https://(www\.)?audius\.co/[a-zA-Z0-9_-]+/[a-zA-Z0-9_-]+$'
            AND lower(split_part(s.external_url,'/',4)) NOT IN ('search','explore','trending','playlists','albums','tracks','settings','feed','reposts','favorites')
            AND lower(split_part(s.external_url,'/',5)) NOT IN ('search','explore','trending','playlists','albums','tracks','settings','feed','reposts','favorites'))
          OR (s.provider='APPLE_MUSIC' AND s.external_url ~ '^https://music\.apple\.com/[a-z]{2}/song/[a-zA-Z0-9_%.-]+/[0-9]+$'
            AND s.provider_track_id=regexp_replace(s.external_url,'^.*/',''))
          OR (s.provider='BANDCAMP' AND s.external_url ~ '^https://[a-z0-9-]+\.bandcamp\.com/track/[a-zA-Z0-9_-]+$')
        ))
      )
    )
  )
  SELECT coalesce(jsonb_agg(jsonb_strip_nulls(jsonb_build_object('code',code,'trackId',item)) ORDER BY code,item),'[]')
  FROM issues;
$$;

CREATE FUNCTION internal.editorial_assert_ready(d internal.editorial_drafts)
RETURNS void LANGUAGE plpgsql STABLE SET search_path = '' AS $$
DECLARE issues jsonb := internal.editorial_issues(d);
BEGIN
  IF issues <> '[]' THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='EDITORIAL_NOT_READY', DETAIL=issues::text;
  END IF;
END;
$$;

CREATE FUNCTION public.list_editorial_cities(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  PERFORM internal.require_editorial();
  RETURN (SELECT jsonb_agg(jsonb_build_object('code',c.code,'countryCode',c.country,
    'title',c.title,'placeId',c.place_id,'mapVisible',c.map_visible,
    'binding',CASE c.matches WHEN 0 THEN 'MISSING' WHEN 1 THEN 'BOUND' ELSE 'AMBIGUOUS' END,
    'draftRevision',d.revision,'playlistId',p.id) ORDER BY c.code)
  FROM internal.editorial_cities() c
  LEFT JOIN internal.editorial_drafts d ON d.city_code=c.code AND d.place_id=c.place_id
  LEFT JOIN LATERAL (SELECT pl.id FROM public.playlists pl WHERE pl.place_id=c.place_id
    AND pl.is_editorial AND pl.visibility='PUBLIC' AND pl.published_at IS NOT NULL
    ORDER BY pl.published_at DESC,pl.id LIMIT 1) p ON true);
END;
$$;

CREATE FUNCTION public.get_editorial_draft(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_place uuid; d internal.editorial_drafts;
BEGIN
  PERFORM internal.require_editorial();
  v_place := internal.editorial_place($1,true);
  SELECT * INTO d FROM internal.editorial_drafts WHERE place_id=v_place;
  IF NOT FOUND THEN PERFORM internal.fail('PT404','EDITORIAL_DRAFT_NOT_FOUND'); END IF;
  RETURN internal.editorial_payload(d);
END;
$$;

CREATE FUNCTION public.save_editorial_draft(jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  v_place uuid; expected integer; d internal.editorial_drafts; previous internal.editorial_drafts;
  existing uuid[]; field text;
BEGIN
  PERFORM internal.require_editorial();
  v_place := internal.editorial_place($1);
  expected := internal.editorial_revision($1,0);
  -- The place row also serializes creation when no draft row exists.
  PERFORM FROM public.places WHERE places.id=v_place FOR UPDATE;
  PERFORM internal.editorial_place($1);
  SELECT * INTO previous FROM internal.editorial_drafts WHERE place_id=v_place FOR UPDATE;
  IF NOT FOUND THEN
    IF expected<>0 THEN PERFORM internal.fail('PT409','EDITORIAL_REVISION_CONFLICT'); END IF;
    SELECT array_agg(p.id) INTO existing FROM public.playlists p WHERE p.place_id=v_place AND p.is_editorial;
    IF cardinality(existing)>1 THEN PERFORM internal.fail('PT409','EDITORIAL_MULTIPLE_LEGACY_PLAYLISTS'); END IF;
    d.place_id := v_place;
    SELECT c.code INTO d.city_code FROM internal.editorial_cities() c WHERE c.place_id=v_place;
    IF EXISTS (SELECT FROM internal.editorial_drafts e WHERE e.city_code=d.city_code) THEN
      PERFORM internal.fail('PT409','EDITORIAL_CITY_ALREADY_BOUND');
    END IF;
    d.playlist_id := existing[1];
    d.revision := 1;
    d.title := '';
    d.description := '';
    d.genre_ids := '{}';
    d.tag_ids := '{}';
    d.track_ids := '{}';
    IF d.playlist_id IS NOT NULL THEN
      SELECT p.title,coalesce(p.description,''),p.cover_path INTO d.title,d.description,d.cover_path
      FROM public.playlists p WHERE p.id=d.playlist_id FOR UPDATE;
      d.track_ids := ARRAY(SELECT t.track_id FROM public.playlist_tracks t WHERE t.playlist_id=d.playlist_id ORDER BY t.position);
      d.genre_ids := ARRAY(SELECT g.genre_id FROM public.playlist_genres g WHERE g.playlist_id=d.playlist_id ORDER BY g.genre_id);
      d.tag_ids := ARRAY(SELECT t.tag_id FROM public.playlist_tags t WHERE t.playlist_id=d.playlist_id ORDER BY t.tag_id);
    END IF;
  ELSE
    d := previous;
    IF NOT EXISTS (SELECT FROM internal.editorial_cities() c WHERE c.place_id=v_place AND c.code=d.city_code) THEN
      PERFORM internal.fail('PT409','EDITORIAL_CITY_BINDING_CHANGED');
    END IF;
  END IF;
  IF ($1 - ARRAY['placeId','expectedRevision','title','description','coverPath','genreIds','tagIds','trackIds']) <> '{}' THEN
    PERFORM internal.fail('PT422','EDITORIAL_UNKNOWN_FIELD');
  END IF;
  FOREACH field IN ARRAY ARRAY['title','description','coverPath'] LOOP
    IF $1 ? field AND jsonb_typeof($1->field)<>'string' AND
       NOT (field='coverPath' AND jsonb_typeof($1->field)='null') THEN
      PERFORM internal.fail('PT422','EDITORIAL_INVALID_TEXT:'||field);
    END IF;
  END LOOP;
  IF $1 ? 'title' THEN d.title := btrim($1->>'title'); END IF;
  IF $1 ? 'description' THEN d.description := btrim($1->>'description'); END IF;
  IF $1 ? 'coverPath' THEN d.cover_path := $1->>'coverPath'; END IF;
  IF char_length(d.title)>150 OR char_length(d.description)>2000 OR char_length(d.cover_path)>250 THEN
    PERFORM internal.fail('PT422','EDITORIAL_TEXT_TOO_LONG');
  END IF;
  FOREACH field IN ARRAY ARRAY['genreIds','tagIds','trackIds'] LOOP
    IF NOT ($1 ? field) THEN CONTINUE; END IF;
    IF jsonb_typeof($1->field)<>'array' THEN PERFORM internal.fail('PT422','EDITORIAL_INVALID_ARRAY:'||field); END IF;
    IF jsonb_array_length($1->field)>30 THEN PERFORM internal.fail('PT422','EDITORIAL_ARRAY_TOO_LONG:'||field); END IF;
    IF field='trackIds' THEN
      IF EXISTS (SELECT FROM jsonb_array_elements($1->field) v WHERE jsonb_typeof(v)<>'string'
        OR (v#>>'{}') !~* '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') THEN
        PERFORM internal.fail('PT422','EDITORIAL_INVALID_TRACK_ID');
      END IF;
      d.track_ids := ARRAY(SELECT v::uuid FROM jsonb_array_elements_text($1->field) WITH ORDINALITY t(v,n) ORDER BY n);
      IF EXISTS (SELECT FROM unnest(d.track_ids) t(id) WHERE NOT EXISTS (SELECT FROM public.tracks x WHERE x.id=t.id)) THEN
        PERFORM internal.fail('PT422','EDITORIAL_UNKNOWN_TRACK');
      END IF;
    ELSE
      IF EXISTS (SELECT FROM jsonb_array_elements($1->field) v
        WHERE jsonb_typeof(v)<>'number' OR (v#>>'{}') !~ '^[0-9]{1,5}$'
          OR CASE WHEN (v#>>'{}') ~ '^[0-9]{1,5}$' THEN (v#>>'{}')::integer NOT BETWEEN 1 AND 32767 ELSE false END) THEN
        PERFORM internal.fail('PT422','EDITORIAL_INVALID_FACET_ID:'||field);
      END IF;
      IF field='genreIds' THEN
        d.genre_ids := ARRAY(SELECT DISTINCT v::smallint FROM jsonb_array_elements_text($1->field) v ORDER BY 1);
        IF EXISTS (SELECT FROM unnest(d.genre_ids) f(id) WHERE NOT EXISTS (SELECT FROM public.genres g WHERE g.id=f.id)) THEN
          PERFORM internal.fail('PT422','EDITORIAL_UNKNOWN_GENRE');
        END IF;
      ELSE
        d.tag_ids := ARRAY(SELECT DISTINCT v::smallint FROM jsonb_array_elements_text($1->field) v ORDER BY 1);
        IF EXISTS (SELECT FROM unnest(d.tag_ids) f(id) WHERE NOT EXISTS (SELECT FROM public.tags t WHERE t.id=f.id)) THEN
          PERFORM internal.fail('PT422','EDITORIAL_UNKNOWN_TAG');
        END IF;
      END IF;
    END IF;
  END LOOP;
  IF d.cover_path IS NOT NULL AND ($1 ? 'coverPath') THEN
    PERFORM FROM storage.objects o WHERE o.bucket_id='soundscape-media' AND o.name=d.cover_path FOR SHARE;
    IF NOT internal.editorial_cover(d.cover_path) THEN PERFORM internal.fail('PT422','EDITORIAL_INVALID_COVER'); END IF;
  END IF;
  IF previous.place_id IS NULL THEN
    INSERT INTO internal.editorial_drafts SELECT (d).* ON CONFLICT (city_code) DO NOTHING RETURNING * INTO d;
    IF NOT FOUND THEN PERFORM internal.fail('PT409','EDITORIAL_CITY_ALREADY_BOUND'); END IF;
  ELSIF (d.title,d.description,d.cover_path,d.genre_ids,d.tag_ids,d.track_ids)
      IS NOT DISTINCT FROM (previous.title,previous.description,previous.cover_path,previous.genre_ids,previous.tag_ids,previous.track_ids) THEN
    -- An identical replay may succeed with the immediately preceding revision.
    IF expected NOT IN (d.revision,d.revision-1) THEN PERFORM internal.fail('PT409','EDITORIAL_REVISION_CONFLICT'); END IF;
  ELSE
    IF expected<>d.revision THEN PERFORM internal.fail('PT409','EDITORIAL_REVISION_CONFLICT'); END IF;
    UPDATE internal.editorial_drafts e SET revision=e.revision+1, title=d.title, description=d.description,
      cover_path=d.cover_path, genre_ids=d.genre_ids, tag_ids=d.tag_ids, track_ids=d.track_ids,
      approved_revision=NULL, approved_by=NULL, approved_at=NULL
    WHERE e.place_id=v_place RETURNING * INTO d;
  END IF;
  RETURN internal.editorial_payload(d);
END;
$$;

CREATE FUNCTION public.check_editorial_draft(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_place uuid; d internal.editorial_drafts; issues jsonb; approved boolean;
BEGIN
  PERFORM internal.require_editorial();
  v_place := internal.editorial_place($1,true);
  SELECT * INTO d FROM internal.editorial_drafts WHERE place_id=v_place;
  IF NOT FOUND THEN PERFORM internal.fail('PT404','EDITORIAL_DRAFT_NOT_FOUND'); END IF;
  issues := internal.editorial_issues(d);
  approved := d.approved_revision IS NOT NULL AND internal.editorial_owner(d.approved_by);
  RETURN jsonb_build_object('revision',d.revision,'issues',issues,'readyForApproval',issues='[]',
    'readyForPublication',issues='[]' AND approved,'approvalValid',approved);
END;
$$;

CREATE FUNCTION internal.editorial_transition(p_data jsonb, p_action text)
RETURNS jsonb LANGUAGE plpgsql SET search_path = '' AS $$
DECLARE
  caller uuid := internal.require_editorial(true);
  v_place uuid := internal.editorial_place(p_data,p_action='UNPUBLISH');
  expected integer := internal.editorial_revision(p_data,1);
  d internal.editorial_drafts;
  published boolean;
  public_revision integer;
BEGIN
  PERFORM FROM public.places WHERE places.id=v_place FOR UPDATE;
  PERFORM internal.editorial_place(p_data,p_action='UNPUBLISH');
  SELECT * INTO d FROM internal.editorial_drafts WHERE place_id=v_place FOR UPDATE;
  IF NOT FOUND THEN PERFORM internal.fail('PT404','EDITORIAL_DRAFT_NOT_FOUND'); END IF;
  IF d.revision<>expected THEN PERFORM internal.fail('PT409','EDITORIAL_REVISION_CONFLICT'); END IF;

  SELECT p.visibility='PUBLIC' AND p.published_at IS NOT NULL,p.revision INTO published,public_revision
  FROM public.playlists p WHERE p.id=d.playlist_id FOR UPDATE;

  IF p_action='UNPUBLISH' THEN
    IF published THEN
      UPDATE public.playlists SET visibility='PRIVATE',published_at=NULL,revision=revision+1,updated_at=now()
      WHERE playlists.id=d.playlist_id;
      UPDATE internal.editorial_drafts SET approved_revision=NULL,approved_by=NULL,approved_at=NULL,
        published_revision=NULL, revision=revision+1 WHERE place_id=v_place RETURNING * INTO d;
    END IF;
  ELSIF p_action='APPROVE' THEN
    PERFORM internal.editorial_assert_ready(d);
    IF d.approved_by IS DISTINCT FROM caller THEN
      UPDATE internal.editorial_drafts SET approved_revision=expected,approved_by=caller,approved_at=now()
      WHERE place_id=v_place RETURNING * INTO d;
    END IF;
  ELSIF p_action='PUBLISH' THEN
    IF published AND d.published_revision=expected THEN RETURN internal.editorial_payload(d); END IF;
    IF d.approved_revision IS DISTINCT FROM expected OR NOT internal.editorial_owner(d.approved_by) THEN
      PERFORM internal.fail('PT409','EDITORIAL_APPROVAL_REQUIRED');
    END IF;
    PERFORM FROM storage.objects o WHERE o.bucket_id='soundscape-media' AND o.name=d.cover_path FOR SHARE;
    PERFORM FROM public.track_sources s WHERE s.track_id=ANY(d.track_ids) ORDER BY s.id FOR SHARE;
    PERFORM internal.editorial_assert_ready(d);
    IF d.playlist_id IS NULL THEN
      INSERT INTO public.playlists(owner_id,place_id,title,description,cover_path,visibility,is_editorial,published_at)
      VALUES (caller,v_place,d.title,d.description,d.cover_path,'PUBLIC',true,now()) RETURNING playlists.id INTO d.playlist_id;
    ELSE
      UPDATE public.playlists SET title=d.title,description=d.description,cover_path=d.cover_path,
        visibility='PUBLIC',published_at=now(),revision=public_revision+1,updated_at=now() WHERE playlists.id=d.playlist_id;
    END IF;
    DELETE FROM public.playlist_tracks t WHERE t.playlist_id=d.playlist_id;
    DELETE FROM public.playlist_genres g WHERE g.playlist_id=d.playlist_id;
    DELETE FROM public.playlist_tags t WHERE t.playlist_id=d.playlist_id;
    INSERT INTO public.playlist_tracks(playlist_id,track_id,position)
      SELECT d.playlist_id,t.track_id,t.position FROM unnest(d.track_ids) WITH ORDINALITY t(track_id,position);
    INSERT INTO public.playlist_genres SELECT d.playlist_id,g FROM unnest(d.genre_ids) g;
    INSERT INTO public.playlist_tags SELECT d.playlist_id,t FROM unnest(d.tag_ids) t;
    UPDATE internal.editorial_drafts e SET playlist_id=d.playlist_id,published_revision=expected
      WHERE e.place_id=v_place RETURNING * INTO d;
  END IF;
  RETURN internal.editorial_payload(d);
END;
$$;

CREATE FUNCTION public.approve_editorial_draft(jsonb)
RETURNS jsonb LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
  SELECT internal.editorial_transition($1,'APPROVE');
$$;
CREATE FUNCTION public.publish_editorial_draft(jsonb)
RETURNS jsonb LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
  SELECT internal.editorial_transition($1,'PUBLISH');
$$;
CREATE FUNCTION public.unpublish_editorial_playlist(jsonb)
RETURNS jsonb LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
  SELECT internal.editorial_transition($1,'UNPUBLISH');
$$;

REVOKE ALL ON FUNCTION internal.editorial_capitals(), internal.editorial_cities(),
  internal.editorial_owner(uuid), internal.require_editorial(boolean), internal.editorial_place(jsonb,boolean),
  internal.editorial_revision(jsonb,integer), internal.editorial_payload(internal.editorial_drafts),
  internal.editorial_cover(text), internal.editorial_issues(internal.editorial_drafts),
  internal.editorial_assert_ready(internal.editorial_drafts), internal.editorial_transition(jsonb,text)
  FROM PUBLIC, anon, authenticated, service_role;

REVOKE ALL ON FUNCTION public.list_editorial_cities(jsonb),public.get_editorial_draft(jsonb),
  public.save_editorial_draft(jsonb),public.check_editorial_draft(jsonb),public.approve_editorial_draft(jsonb),
  public.publish_editorial_draft(jsonb),public.unpublish_editorial_playlist(jsonb) FROM PUBLIC, anon, authenticated, service_role;
-- Guests receive the standard 401 from require_user.
GRANT EXECUTE ON FUNCTION public.list_editorial_cities(jsonb),public.get_editorial_draft(jsonb),
  public.save_editorial_draft(jsonb),public.check_editorial_draft(jsonb),public.approve_editorial_draft(jsonb),
  public.publish_editorial_draft(jsonb),public.unpublish_editorial_playlist(jsonb) TO anon, authenticated;

-- Synthetic data must be explicitly enabled on the disposable database itself.
-- A loopback client check alone cannot distinguish a development DB from a tunnel.
CREATE FUNCTION public.editorial_import_fixture_batch(p_target uuid, p_items jsonb)
RETURNS TABLE(id uuid, provider_track_id text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  IF p_target IS NULL OR NOT EXISTS (
      SELECT FROM internal.editorial_fixture_target f WHERE f.marker=p_target) THEN
    PERFORM internal.fail('PT403','EDITORIAL_FIXTURE_TARGET_DISABLED');
  END IF;
  IF jsonb_typeof(p_items) IS DISTINCT FROM 'array' THEN
    PERFORM internal.fail('PT422','EDITORIAL_INVALID_FIXTURE_BATCH');
  END IF;
  IF EXISTS (SELECT FROM jsonb_array_elements(p_items) AS x(item)
      WHERE coalesce(item->>'id','') !~ '^BE03Fixture-[a-z0-9-]+$'
        OR coalesce(item->>'artistId','') !~ '^BE03Fixture-[a-z0-9-]+$'
        OR coalesce(item->>'title','') NOT LIKE '[BE-03 QA] %'
        OR coalesce(item->>'permalink','') !~ '^https://audius\.co/be03-fixture/[a-z0-9-]+$'
        OR (item->>'verifiedAt')::timestamptz IS NULL
        OR (item->>'verifiedAt')::timestamptz NOT BETWEEN now()-interval '1 hour' AND now()) THEN
    PERFORM internal.fail('PT422','EDITORIAL_INVALID_FIXTURE_BATCH');
  END IF;
  RETURN QUERY SELECT x.id,x.provider_track_id FROM public.edge_import_audius_batch(p_items) AS x;
  UPDATE public.track_sources s SET verified_at=(x.item->>'verifiedAt')::timestamptz
  FROM jsonb_array_elements(p_items) AS x(item)
  WHERE s.provider='AUDIUS' AND s.provider_track_id=x.item->>'id';
END;
$$;
REVOKE ALL ON FUNCTION public.editorial_import_fixture_batch(uuid,jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.editorial_import_fixture_batch(uuid,jsonb) TO service_role;
