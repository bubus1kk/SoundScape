-- Official content is owned by the application. Personal ownership and its existing
-- cascading foreign key are still mandatory for every ordinary playlist.
ALTER TABLE public.playlists ALTER COLUMN owner_id DROP NOT NULL;
UPDATE public.playlists SET owner_id=NULL WHERE is_editorial AND owner_id IS NOT NULL;
ALTER TABLE public.playlists ADD CONSTRAINT playlists_ownership_kind
  CHECK ((owner_id IS NULL)=is_editorial);
CREATE UNIQUE INDEX playlists_official_city_unique ON public.playlists(place_id)
  WHERE is_editorial AND place_id IS NOT NULL;

CREATE FUNCTION internal.normalize_official_playlist_owner()
RETURNS trigger LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
  IF NEW.is_editorial THEN NEW.owner_id := NULL; END IF;
  RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION internal.normalize_official_playlist_owner() FROM PUBLIC, anon, authenticated, service_role;
CREATE TRIGGER normalize_official_playlist_owner
  BEFORE INSERT OR UPDATE OF owner_id, is_editorial ON public.playlists
  FOR EACH ROW EXECUTE FUNCTION internal.normalize_official_playlist_owner();

CREATE FUNCTION internal.clear_deleted_editorial_approval()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
  UPDATE internal.editorial_drafts SET approved_revision=NULL,approved_by=NULL,approved_at=NULL
  WHERE approved_by=OLD.id;
  RETURN OLD;
END;
$$;
REVOKE ALL ON FUNCTION internal.clear_deleted_editorial_approval() FROM PUBLIC,anon,authenticated,service_role;
CREATE TRIGGER clear_deleted_editorial_approval AFTER DELETE ON public.profiles
  FOR EACH ROW EXECUTE FUNCTION internal.clear_deleted_editorial_approval();

-- Use a stable application identity in the existing non-null UUID API field.
-- The system value does not identify an Auth user or a profile.


CREATE OR REPLACE FUNCTION internal.playlist_detail(p_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
 SELECT jsonb_build_object(
   'id', p.id, 'ownerId', coalesce(p.owner_id,'00000000-0000-4000-8000-000000000003'::uuid), 'title', p.title,
   'description', p.description, 'visibility', p.visibility,
   'revision', p.revision, 'placeId', CASE WHEN p.place_id IS NULL OR p.owner_id=(SELECT auth.uid())
     OR EXISTS (SELECT 1 FROM public.places plc WHERE plc.id=p.place_id AND plc.is_published)
     THEN p.place_id ELSE NULL END,
   'coverPath', p.cover_path, 'isEditorial', p.is_editorial,
   'genreIds', COALESCE((SELECT jsonb_agg(pg.genre_id ORDER BY pg.genre_id)
                        FROM public.playlist_genres pg WHERE pg.playlist_id=p.id),'[]'::jsonb),
   'tagIds', COALESCE((SELECT jsonb_agg(pt.tag_id ORDER BY pt.tag_id)
                      FROM public.playlist_tags pt WHERE pt.playlist_id=p.id),'[]'::jsonb),
   'items', COALESCE((SELECT jsonb_agg(
          jsonb_build_object('position',pt.position,'track', internal.track_view(pt.track_id))
          ORDER BY pt.position) FROM public.playlist_tracks pt
          WHERE pt.playlist_id=p.id),'[]'::jsonb)
 ) FROM public.playlists p WHERE p.id=p_id;
$$;

CREATE OR REPLACE FUNCTION internal.playlist_summary(p_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
 SELECT jsonb_build_object(
   'id', p.id, 'ownerId', coalesce(p.owner_id,'00000000-0000-4000-8000-000000000003'::uuid), 'title', p.title,
   'description', p.description, 'visibility', p.visibility,
   'revision', p.revision, 'placeId', CASE WHEN p.place_id IS NULL OR p.owner_id=(SELECT auth.uid())
     OR EXISTS (SELECT 1 FROM public.places plc WHERE plc.id=p.place_id AND plc.is_published)
     THEN p.place_id ELSE NULL END,
   'coverPath', p.cover_path, 'isEditorial', p.is_editorial,
   'genreIds', COALESCE((SELECT jsonb_agg(g.genre_id ORDER BY g.genre_id)
                        FROM public.playlist_genres g WHERE g.playlist_id=p.id),'[]'::jsonb),
   'tagIds', COALESCE((SELECT jsonb_agg(t.tag_id ORDER BY t.tag_id)
                      FROM public.playlist_tags t WHERE t.playlist_id=p.id),'[]'::jsonb)
 ) FROM public.playlists p WHERE p.id=p_id;
$$;


CREATE FUNCTION internal.editorial_track_state(p_track uuid)
RETURNS text LANGUAGE sql STABLE SET search_path = '' AS $$
  WITH sources AS (
    SELECT s.* FROM public.track_sources s WHERE s.track_id=p_track
      AND (s.provider_track_id NOT LIKE 'BE03Fixture-%'
        OR EXISTS (SELECT FROM internal.editorial_fixture_target))
  )
  SELECT CASE
    WHEN EXISTS (SELECT FROM sources s WHERE s.provider='AUDIUS' AND s.can_stream
      AND s.verified_at BETWEEN statement_timestamp()-interval '1 hour' AND statement_timestamp()
      AND s.verified_at>statement_timestamp()-interval '1 hour') THEN 'IN_APP'
    WHEN EXISTS (SELECT FROM sources s WHERE NOT s.can_stream AND s.verified_at<=statement_timestamp() AND (
      (s.provider='AUDIUS' AND s.external_url ~ '^https://(www\.)?audius\.co/[a-zA-Z0-9_-]+/[a-zA-Z0-9_-]+$'
        AND NOT string_to_array(lower(regexp_replace(s.external_url,'^https://[^/]+/','')),'/')
          && ARRAY['search','explore','trending','playlists','albums','tracks','settings','feed','reposts','favorites'])
      OR (s.provider='APPLE_MUSIC' AND (
        (s.external_url ~ '^https://music\.apple\.com/[a-z]{2}/song/[a-zA-Z0-9_%.-]+/[0-9]+$'
          AND s.provider_track_id=regexp_replace(s.external_url,'^.*/',''))
        OR (s.external_url ~ '^https://music\.apple\.com/[a-z]{2}/album/[a-zA-Z0-9_%.-]+/[0-9]+\?i=[0-9]+(&uo=[0-9]+)?$'
          AND s.provider_track_id=split_part(split_part(s.external_url,'?i=',2),'&',1))
      ))
      OR (s.provider='BANDCAMP' AND s.external_url ~ '^https://[a-z0-9-]+\.bandcamp\.com/track/[a-zA-Z0-9_-]+$')
    )) THEN 'EXTERNAL_ONLY'
    WHEN EXISTS (SELECT FROM sources s WHERE s.provider='AUDIUS' AND s.can_stream)
      THEN 'IN_APP_RECHECK_REQUIRED'
    ELSE 'NO_VERIFIED_SOURCE'
  END;
$$;
REVOKE ALL ON FUNCTION internal.editorial_track_state(uuid) FROM PUBLIC, anon, authenticated, service_role;


CREATE OR REPLACE FUNCTION internal.editorial_issues(d internal.editorial_drafts)
RETURNS jsonb LANGUAGE sql STABLE SET search_path = '' AS $$
  WITH slots AS (
    SELECT t.id, internal.editorial_track_state(t.id) AS state
    FROM unnest(d.track_ids) t(id) JOIN public.tracks tr ON tr.id=t.id
  ), totals AS (
    SELECT count(*) FILTER (WHERE state='IN_APP') AS in_app,
      count(*) FILTER (WHERE state='EXTERNAL_ONLY') AS external
    FROM slots
  ), issues(code, item) AS (
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
    UNION ALL SELECT s.state,s.id FROM slots s WHERE s.state NOT IN ('IN_APP','EXTERNAL_ONLY')
    UNION ALL SELECT 'NOT_ENOUGH_EXTERNAL_ONLY',NULL FROM totals WHERE external<2
    UNION ALL SELECT 'TOO_MANY_EXTERNAL_ONLY',NULL FROM totals WHERE external>3
    UNION ALL SELECT 'NOT_ENOUGH_IN_APP',NULL FROM totals
      WHERE in_app<greatest(9,cardinality(d.track_ids)-3)
  )
  SELECT coalesce(jsonb_agg(jsonb_strip_nulls(jsonb_build_object('code',code,'trackId',item)) ORDER BY code,item),'[]')
  FROM issues;
$$;

CREATE OR REPLACE FUNCTION public.check_editorial_draft(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_place uuid; d internal.editorial_drafts; issues jsonb; approved boolean; in_app integer; external integer;
BEGIN
  PERFORM internal.require_editorial();
  v_place := internal.editorial_place($1,true);
  SELECT * INTO d FROM internal.editorial_drafts WHERE place_id=v_place;
  IF NOT FOUND THEN PERFORM internal.fail('PT404','EDITORIAL_DRAFT_NOT_FOUND'); END IF;
  issues := internal.editorial_issues(d);
  approved := d.approved_revision IS NOT NULL AND internal.editorial_owner(d.approved_by);
  SELECT count(*) FILTER (WHERE state='IN_APP'),count(*) FILTER (WHERE state='EXTERNAL_ONLY')
    INTO in_app,external
    FROM (SELECT internal.editorial_track_state(id) AS state FROM unnest(d.track_ids) t(id)) s;
  RETURN jsonb_build_object('inAppCount',in_app,'externalOnlyCount',external,'revision',d.revision,'issues',issues,'readyForApproval',issues='[]',
    'readyForPublication',issues='[]' AND approved,'approvalValid',approved);
END;
$$;

CREATE OR REPLACE FUNCTION public.save_editorial_draft(jsonb)
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
    IF expected=0 AND ($1 - ARRAY['placeId','expectedRevision'])='{}' THEN
      RETURN internal.editorial_payload(previous);
    END IF;
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

CREATE OR REPLACE FUNCTION public.list_editorial_cities(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  PERFORM internal.require_editorial();
  RETURN (SELECT jsonb_agg(jsonb_build_object('code',c.code,'countryCode',c.country,
    'title',c.title,'placeId',c.place_id,'mapVisible',c.map_visible,
    'binding',CASE c.matches WHEN 0 THEN 'MISSING' WHEN 1 THEN 'BOUND' ELSE 'AMBIGUOUS' END,
    'draftRevision',d.revision,'playlistId',p.id,'officialPlaylistId',official.id,
    'officialBinding',CASE coalesce(official.matches,0) WHEN 0 THEN 'MISSING' WHEN 1 THEN 'BOUND' ELSE 'AMBIGUOUS' END) ORDER BY c.code)
  FROM internal.editorial_cities() c
  LEFT JOIN internal.editorial_drafts d ON d.city_code=c.code AND d.place_id=c.place_id
  LEFT JOIN LATERAL (SELECT CASE WHEN count(*)=1 THEN (array_agg(pl.id))[1] END AS id,count(*) AS matches
    FROM public.playlists pl WHERE pl.place_id=c.place_id AND pl.is_editorial) official ON true
  LEFT JOIN LATERAL (SELECT pl.id FROM public.playlists pl WHERE pl.place_id=c.place_id
    AND pl.is_editorial AND pl.visibility='PUBLIC' AND pl.published_at IS NOT NULL
    ORDER BY pl.published_at DESC,pl.id LIMIT 1) p ON true);
END;
$$;


CREATE FUNCTION internal.published_editorial_at(p_place uuid)
RETURNS TABLE(id uuid, cover_path text) LANGUAGE sql STABLE SET search_path = '' AS $$
  SELECT p.id,p.cover_path FROM public.playlists p WHERE p.place_id=p_place
    AND p.is_editorial AND p.visibility='PUBLIC' AND p.published_at IS NOT NULL
  ORDER BY p.published_at DESC,p.id LIMIT 1;
$$;
REVOKE ALL ON FUNCTION internal.published_editorial_at(uuid) FROM PUBLIC, anon, authenticated, service_role;


CREATE OR REPLACE FUNCTION internal.visible_places()
RETURNS TABLE (
 id uuid, kind text, title text, latitude double precision,
 longitude double precision, description text, playlist_id uuid,
 cover_path text, geom extensions.geography
)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
 SELECT p.id,'CITY'::text,p.title,
   extensions.ST_Y(p.geom::extensions.geometry),
   extensions.ST_X(p.geom::extensions.geometry),
   p.description,
   pl.id,coalesce(pl.cover_path,p.cover_path),p.geom
 FROM public.places p LEFT JOIN LATERAL internal.published_editorial_at(p.id) pl ON true
 WHERE p.is_published
 UNION ALL
 SELECT pin.id,'PIN'::text,pin.title,
   extensions.ST_Y(pin.geom::extensions.geometry),
   extensions.ST_X(pin.geom::extensions.geometry),
   pin.description,NULL::uuid,NULL::text,pin.geom
 FROM public.music_pins pin
 WHERE pin.visibility='PUBLIC' OR pin.owner_id=(SELECT auth.uid());
$$;

CREATE OR REPLACE FUNCTION public.edge_media_path(
 p_user uuid,p_kind text,p_object uuid,p_photo uuid DEFAULT NULL,p_token_hash text DEFAULT NULL
) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_path text;v_owner uuid;v_vis text;v_allowed boolean:=false;v_editorial boolean:=false;
BEGIN
 IF p_kind NOT IN ('PIN','EVENT','PLAYLIST','PLACE','PROFILE') OR p_object IS NULL THEN RETURN NULL; END IF;
 IF p_kind='PIN' THEN
    SELECT p.owner_id,p.visibility,ph.storage_path INTO v_owner,v_vis,v_path
    FROM public.music_pins p JOIN public.pin_photos ph ON ph.pin_id=p.id
    WHERE p.id=p_object AND ph.id=p_photo;
 ELSIF p_kind='EVENT' THEN
    SELECT e.owner_id,e.visibility,ph.storage_path INTO v_owner,v_vis,v_path
    FROM public.travel_events e JOIN public.event_photos ph ON ph.event_id=e.id
    WHERE e.id=p_object AND ph.id=p_photo;
 ELSIF p_kind='PLAYLIST' THEN
    SELECT owner_id,CASE WHEN is_editorial AND published_at IS NULL THEN 'PRIVATE' ELSE visibility END,cover_path,is_editorial
    INTO v_owner,v_vis,v_path,v_editorial FROM public.playlists WHERE id=p_object;
 ELSIF p_kind='PROFILE' THEN
    SELECT p.id,'PUBLIC',p.avatar_path INTO v_owner,v_vis,v_path FROM public.profiles p WHERE p.id=p_object;
 ELSE
    SELECT NULL::uuid,CASE WHEN p.is_published THEN 'PUBLIC' ELSE 'PRIVATE' END,
       coalesce(pl.cover_path,p.cover_path)
    INTO v_owner,v_vis,v_path FROM public.places p
    LEFT JOIN LATERAL internal.published_editorial_at(p.id) pl ON p.is_published WHERE p.id=p_object;
 END IF;
 IF v_path IS NULL OR (p_user IS NOT NULL AND NOT internal.active_account(p_user)) THEN RETURN NULL; END IF;
 IF p_token_hash IS NOT NULL THEN
    v_allowed:=v_vis='UNLISTED' AND p_token_hash ~ '^[a-f0-9]{64}$'
      AND EXISTS(SELECT 1 FROM public.share_tokens t
     WHERE t.token_hash=p_token_hash AND t.owner_id=v_owner AND t.revoked_at IS NULL
       AND (t.expires_at IS NULL OR t.expires_at>now())
       AND ((p_kind='PIN' AND t.pin_id=p_object) OR (p_kind='EVENT' AND t.event_id=p_object)
           OR (p_kind='PLAYLIST' AND t.playlist_id=p_object)));
 ELSE
    v_allowed := v_vis='PUBLIC' OR (p_user IS NOT NULL AND (p_user=v_owner OR
      (v_editorial AND EXISTS (SELECT FROM public.profiles p WHERE p.id=p_user AND p.role_code IN ('EDITOR','ADMIN')))));
 END IF;
 IF v_allowed IS DISTINCT FROM true THEN RETURN NULL; END IF;
 IF position('..' in v_path)>0 OR position(chr(92) in v_path)>0 THEN RETURN NULL; END IF;
 IF NOT ((v_owner IS NOT NULL AND v_path LIKE 'users/' || v_owner::text || '/_%')
     OR ((p_kind='PLACE' OR v_editorial) AND v_path LIKE 'editorial/_%')) THEN RETURN NULL; END IF;
 IF NOT EXISTS (SELECT 1 FROM storage.objects so WHERE so.bucket_id='soundscape-media' AND so.name=v_path) THEN RETURN NULL; END IF;
 RETURN jsonb_build_object('path',v_path);
END;$$;

-- The editor CLI only writes to explicitly armed isolated databases.
ALTER TABLE internal.editorial_fixture_target ADD COLUMN allow_live boolean NOT NULL DEFAULT false;

CREATE FUNCTION public.editorial_snapshot_target(p_target uuid)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT EXISTS (SELECT FROM internal.editorial_fixture_target f WHERE f.marker=p_target);
$$;
REVOKE ALL ON FUNCTION public.editorial_snapshot_target(uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.editorial_snapshot_target(uuid) TO service_role;

CREATE FUNCTION public.editorial_live_target(p_target uuid)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT EXISTS (SELECT FROM internal.editorial_fixture_target f WHERE f.marker=p_target AND f.allow_live);
$$;
REVOKE ALL ON FUNCTION public.editorial_live_target(uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.editorial_live_target(uuid) TO service_role;

CREATE FUNCTION public.editorial_import_verified_batch(p_target uuid,p_items jsonb)
RETURNS TABLE(id uuid,provider_track_id text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE item jsonb; v_provider text; v_track uuid; v_canonical uuid; v_verified timestamptz;
BEGIN
  IF NOT public.editorial_live_target(p_target) THEN
    PERFORM internal.fail('PT403','EDITORIAL_LIVE_TARGET_DISABLED');
  END IF;
  IF jsonb_typeof(p_items) IS DISTINCT FROM 'array' OR jsonb_array_length(p_items) NOT BETWEEN 1 AND 20 THEN
    PERFORM internal.fail('PT422','EDITORIAL_INVALID_IMPORT_BATCH');
  END IF;
  -- Match the lock ordering of the existing catalog importer.
  PERFORM pg_advisory_xact_lock(hashtext('AUDIUS_ARTIST'),hashtext(artist))
  FROM (SELECT DISTINCT x->>'artistId' AS artist FROM jsonb_array_elements(p_items) x ORDER BY 1) q
  WHERE artist IS NOT NULL;
  FOR v_provider IN SELECT DISTINCT x->>'id' FROM jsonb_array_elements(p_items) x ORDER BY 1 LOOP
    PERFORM FROM public.track_sources s WHERE s.provider='AUDIUS' AND s.provider_track_id=v_provider FOR UPDATE;
    IF NOT FOUND THEN
      PERFORM pg_advisory_xact_lock(hashtext('AUDIUS'),hashtext(v_provider));
    END IF;
  END LOOP;
  FOR item IN SELECT x FROM jsonb_array_elements(p_items) x ORDER BY x->>'id' LOOP
    v_provider := item->>'id';
    v_canonical := nullif(item->>'canonicalTrackId','')::uuid;
    v_verified := (item->>'verifiedAt')::timestamptz;
    IF coalesce(v_provider,'') !~ '^[A-Za-z0-9-]{1,120}$' OR v_provider LIKE 'BE03Fixture-%'
      OR v_verified IS NULL OR v_verified<=statement_timestamp()-interval '1 hour'
      OR v_verified>statement_timestamp() OR item->'canStream' IS DISTINCT FROM 'true'::jsonb
      OR coalesce(item->>'permalink','') !~ '^https://(www\.)?audius\.co/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+$' THEN
      PERFORM internal.fail('PT422','EDITORIAL_INVALID_VERIFIED_SOURCE');
    END IF;
    SELECT s.track_id INTO v_track FROM public.track_sources s
      WHERE s.provider='AUDIUS' AND s.provider_track_id=v_provider FOR UPDATE;
    IF v_canonical IS NOT NULL AND v_track IS DISTINCT FROM v_canonical AND NOT EXISTS (
      SELECT FROM public.tracks t JOIN public.track_artists c ON c.track_id=t.id AND c.credit_order=1
      JOIN public.artists a ON a.id=c.artist_id
      WHERE t.id=v_canonical AND lower(btrim(t.title))=lower(btrim(item->>'title'))
        AND lower(btrim(a.name))=lower(btrim(item->>'artist'))
        AND abs(t.duration_ms-(item->>'durationMs')::integer)<=2000
        AND ((t.isrc IS NOT NULL AND upper(t.isrc)=upper(item->>'isrc')) OR
          (t.musicbrainz_recording_id IS NOT NULL AND t.musicbrainz_recording_id::text=lower(item->>'recordingMbid')))
    ) THEN PERFORM internal.fail('PT422','EDITORIAL_CANONICAL_IDENTITY_UNCONFIRMED'); END IF;
    IF v_track IS NOT NULL AND v_canonical IS NOT NULL AND v_track<>v_canonical THEN
      PERFORM internal.fail('PT409','EDITORIAL_PROVIDER_ALREADY_BOUND');
    END IF;
    IF v_track IS NULL AND v_canonical IS NOT NULL THEN
      v_track := v_canonical;
      INSERT INTO public.track_sources(track_id,provider,provider_track_id,external_url,can_stream,verified_at)
      VALUES (v_track,'AUDIUS',v_provider,item->>'permalink',true,v_verified);
    ELSIF v_track IS NULL THEN
      SELECT x.id INTO v_track FROM public.edge_import_audius_batch(jsonb_build_array(item)) x;
      UPDATE public.track_sources s SET verified_at=v_verified
      WHERE s.provider='AUDIUS' AND s.provider_track_id=v_provider;
    ELSE
      UPDATE public.track_sources s SET can_stream=true,can_cache=false,can_download=false,
        verified_at=v_verified,external_url=item->>'permalink'
      WHERE s.provider='AUDIUS' AND s.provider_track_id=v_provider
        AND (s.verified_at IS NULL OR s.verified_at<v_verified);
    END IF;
    id := v_track;
    provider_track_id := v_provider;
    RETURN NEXT;
  END LOOP;
END;
$$;
REVOKE ALL ON FUNCTION public.editorial_import_verified_batch(uuid,jsonb) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.editorial_import_verified_batch(uuid,jsonb) TO service_role;

