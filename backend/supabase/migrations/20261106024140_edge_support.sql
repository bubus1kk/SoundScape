-- SoundScape Block C. COPY to file created by `supabase migration new edge_support`.
-- Requires initial_schema, auth_rls_storage, rpc_crud, rpc_journey_validation.
-- IMPORTANT: service_role ONLY; none of these functions are callable by anon/authenticated.

-- Reserve quota atomically for Edge calls. Counters intentionally fail closed.
CREATE OR REPLACE FUNCTION public.edge_reserve_quota(p_operation text, p_user uuid DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
 v_window timestamptz; v_global_window timestamptz;
 v_global_limit integer; v_user_limit integer; v_window_seconds integer;
 v_count bigint; v_user_count bigint; v_lease uuid;
 v_existing_lease uuid; v_lease_exp timestamptz; v_retry integer;
BEGIN
 -- This function is intended to be callable only by service_role.
 CASE p_operation
   WHEN 'GENERATE' THEN v_global_limit:=50; v_user_limit:=5; v_window_seconds:=86400;
   WHEN 'SEARCH_TRACKS' THEN v_global_limit:=120; v_user_limit:=NULL; v_window_seconds:=60;
   WHEN 'SEARCH' THEN v_global_limit:=600; v_user_limit:=NULL; v_window_seconds:=60;
   WHEN 'RESOLVE' THEN v_global_limit:=240; v_user_limit:=NULL; v_window_seconds:=60;
   WHEN 'OPEN_SHARE' THEN v_global_limit:=240; v_user_limit:=NULL; v_window_seconds:=60;
   WHEN 'MEDIA' THEN v_global_limit:=600; v_user_limit:=NULL; v_window_seconds:=60;
   WHEN 'MANAGE_SHARE' THEN v_global_limit:=120; v_user_limit:=NULL; v_window_seconds:=60;
   ELSE RAISE EXCEPTION 'UNKNOWN_QUOTA_OPERATION' USING ERRCODE='22023';
 END CASE;
 IF v_user_limit IS NOT NULL AND p_user IS NULL THEN
    RAISE EXCEPTION 'USER_REQUIRED' USING ERRCODE='22023';
 END IF;
 -- Serialize short counter checks across edge worker instances, safe for low-volume v1.
 PERFORM pg_catalog.pg_advisory_xact_lock(30495, 1);
 -- Opportunistic bounded retention; never expose counters to client roles.
 IF pg_catalog.random() < 0.01 THEN
   DELETE FROM internal.api_quota_counters WHERE updated_at < pg_catalog.now() - interval '3 days';
 END IF;
 v_window := CASE WHEN v_window_seconds=86400 THEN pg_catalog.date_trunc('day',pg_catalog.now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
            ELSE pg_catalog.date_trunc('minute',pg_catalog.now()) END;
 v_retry := GREATEST(1, CEIL(EXTRACT(EPOCH FROM (v_window + pg_catalog.make_interval(secs=>v_window_seconds) - pg_catalog.now())))::integer);
 INSERT INTO internal.api_quota_counters(scope_type,subject_key,operation_code,window_started_at)
 VALUES ('GLOBAL','project',p_operation,v_window)
 ON CONFLICT DO NOTHING;
 SELECT request_count,active_lease_id,active_lease_expires_at INTO v_count,v_existing_lease,v_lease_exp
 FROM internal.api_quota_counters
 WHERE scope_type='GLOBAL' AND subject_key='project' AND operation_code=p_operation AND window_started_at=v_window FOR UPDATE;
 IF v_count >= v_global_limit THEN
   RETURN jsonb_build_object('allowed',false,'reason','GLOBAL_LIMIT','retryAfter',v_retry);
 END IF;
 IF p_operation='GENERATE' AND v_existing_lease IS NOT NULL AND v_lease_exp>pg_catalog.now() THEN
   RETURN jsonb_build_object('allowed',false,'reason','CONCURRENT_LIMIT','retryAfter',GREATEST(1,CEIL(EXTRACT(EPOCH FROM (v_lease_exp-pg_catalog.now())))::int));
 END IF;
 IF v_user_limit IS NOT NULL THEN
   INSERT INTO internal.api_quota_counters(scope_type,subject_key,operation_code,window_started_at)
   VALUES ('USER',p_user::text,p_operation,v_window)
   ON CONFLICT DO NOTHING;
   SELECT request_count INTO v_user_count FROM internal.api_quota_counters
   WHERE scope_type='USER' AND subject_key=p_user::text AND operation_code=p_operation AND window_started_at=v_window FOR UPDATE;
   IF v_user_count>=v_user_limit THEN
     RETURN jsonb_build_object('allowed',false,'reason','USER_LIMIT','retryAfter',v_retry);
   END IF;
   UPDATE internal.api_quota_counters SET request_count=request_count+1,updated_at=pg_catalog.now()
   WHERE scope_type='USER' AND subject_key=p_user::text AND operation_code=p_operation AND window_started_at=v_window;
 END IF;
 IF p_operation='GENERATE' THEN v_lease:=pg_catalog.gen_random_uuid(); END IF;
 UPDATE internal.api_quota_counters SET request_count=request_count+1,
   active_lease_id=CASE WHEN p_operation='GENERATE' THEN v_lease ELSE NULL END,
   active_lease_expires_at=CASE WHEN p_operation='GENERATE' THEN pg_catalog.now()+interval '120 seconds' ELSE NULL END,
   updated_at=pg_catalog.now()
 WHERE scope_type='GLOBAL' AND subject_key='project' AND operation_code=p_operation AND window_started_at=v_window;
 RETURN jsonb_build_object('allowed',true,'leaseId',v_lease,'retryAfter',0);
END;$$;

CREATE OR REPLACE FUNCTION public.edge_release_generation(p_lease uuid)
RETURNS boolean LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_changed integer;
BEGIN
 IF p_lease IS NULL THEN RETURN false; END IF;
 UPDATE internal.api_quota_counters SET active_lease_id=NULL,active_lease_expires_at=NULL,updated_at=now()
 WHERE scope_type='GLOBAL' AND operation_code='GENERATE' AND active_lease_id=p_lease;
 GET DIAGNOSTICS v_changed=ROW_COUNT;
 RETURN v_changed>0;
END;$$;

-- One atomic and validated ingest of an Audius candidate. No guessed URLs or permissions.
CREATE OR REPLACE FUNCTION public.edge_ingest_audius(
 p_provider_id text,p_title text,p_artist text,p_duration_ms integer,p_permalink text
) RETURNS uuid LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
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
 INSERT INTO public.artists(name) VALUES (p_artist) RETURNING id INTO v_artist;
 INSERT INTO public.track_artists(track_id,artist_id,credit_order)
 VALUES (v_track,v_artist,1);
 -- Streaming/caching/downloading forbidden until live and rights verification.
 INSERT INTO public.track_sources(track_id,provider,provider_track_id,external_url,
    can_stream,can_cache,can_download,verified_at)
 VALUES (v_track,'AUDIUS',p_provider_id,p_permalink,false,false,false,now());
 RETURN v_track;
END;$$;

-- Update stream flag only after the Edge resolver validates an actual stream URL.
CREATE OR REPLACE FUNCTION public.edge_mark_streamable(p_track uuid,p_provider_id text,p_allowed boolean)
RETURNS void LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
 UPDATE public.track_sources SET can_stream=p_allowed,can_cache=false,can_download=false,verified_at=now()
 WHERE track_id=p_track AND provider='AUDIUS' AND provider_track_id=p_provider_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'UNKNOWN_SOURCE' USING ERRCODE='22023'; END IF;
END;$$;

-- User-controlled, revocable secret links. Raw tokens never enter Postgres.
CREATE OR REPLACE FUNCTION public.edge_create_share(p_user uuid,p_type text,p_id uuid,p_hash text,p_expires timestamptz DEFAULT NULL)
RETURNS uuid LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_owner uuid; v_visibility text;v_share uuid;
BEGIN
 IF p_user IS NULL OR p_id IS NULL OR p_hash !~ '^[a-f0-9]{64}$'
   OR p_type NOT IN ('PLAYLIST','MUSIC_PIN','TRAVEL_EVENT','JOURNEY')
   OR (p_expires IS NOT NULL AND (p_expires <= now() OR p_expires>now()+interval '90 days')) THEN
  RAISE EXCEPTION 'INVALID_SHARE_REQUEST' USING ERRCODE='22023';
 END IF;
 IF p_type='PLAYLIST' THEN SELECT owner_id,visibility INTO v_owner,v_visibility FROM public.playlists WHERE id=p_id;
 ELSIF p_type='MUSIC_PIN' THEN SELECT owner_id,visibility INTO v_owner,v_visibility FROM public.music_pins WHERE id=p_id;
 ELSIF p_type='TRAVEL_EVENT' THEN SELECT owner_id,visibility INTO v_owner,v_visibility FROM public.travel_events WHERE id=p_id;
 ELSE SELECT owner_id,visibility INTO v_owner,v_visibility FROM public.music_journeys WHERE id=p_id; END IF;
 IF v_owner IS NULL OR v_owner<>p_user OR v_visibility<>'UNLISTED' THEN
   RAISE EXCEPTION 'FORBIDDEN_SHARE' USING ERRCODE='42501';
 END IF;
 INSERT INTO public.share_tokens(owner_id,token_hash,playlist_id,pin_id,event_id,journey_id,expires_at)
 VALUES(p_user,p_hash,
   CASE WHEN p_type='PLAYLIST' THEN p_id END,
   CASE WHEN p_type='MUSIC_PIN' THEN p_id END,
   CASE WHEN p_type='TRAVEL_EVENT' THEN p_id END,
   CASE WHEN p_type='JOURNEY' THEN p_id END,p_expires)
 RETURNING id INTO v_share;
 RETURN v_share;
END;$$;

CREATE OR REPLACE FUNCTION public.edge_revoke_share(p_user uuid,p_share uuid)
RETURNS boolean LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_rows integer;
BEGIN
 UPDATE public.share_tokens SET revoked_at=now()
 WHERE id=p_share AND owner_id=p_user AND revoked_at IS NULL;
 GET DIAGNOSTICS v_rows=ROW_COUNT;
 RETURN v_rows>0;
END;$$;

-- Check unlisted token and return sanitized DTO (no private Storage paths).
CREATE OR REPLACE FUNCTION public.edge_open_share(p_hash text)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v public.share_tokens%ROWTYPE; v_result jsonb;
BEGIN
 IF p_hash !~ '^[a-f0-9]{64}$' THEN RETURN NULL; END IF;
 SELECT * INTO v FROM public.share_tokens t WHERE t.token_hash=p_hash
 AND t.revoked_at IS NULL AND (t.expires_at IS NULL OR t.expires_at>now());
 IF NOT FOUND THEN RETURN NULL; END IF;
 IF v.playlist_id IS NOT NULL THEN
  SELECT jsonb_build_object('objectType','PLAYLIST','id',p.id,'title',p.title,
     'payload',jsonb_build_object('description',p.description,'items',COALESCE(
      (SELECT jsonb_agg(jsonb_build_object('trackId',pt.track_id,'position',pt.position) ORDER BY pt.position)
       FROM public.playlist_tracks pt WHERE pt.playlist_id=p.id),'[]'::jsonb)))
  INTO v_result FROM public.playlists p
  WHERE p.id=v.playlist_id AND p.owner_id=v.owner_id AND p.visibility='UNLISTED';
 ELSIF v.pin_id IS NOT NULL THEN
  SELECT jsonb_build_object('objectType','MUSIC_PIN','id',p.id,'title',p.title,
    'payload',jsonb_build_object('description',p.description,
        'latitude',extensions.ST_Y(p.geom::extensions.geometry),
        'longitude',extensions.ST_X(p.geom::extensions.geometry),
        'trackIds',COALESCE((SELECT jsonb_agg(pt.track_id ORDER BY pt.position) FROM public.pin_tracks pt WHERE pt.pin_id=p.id),'[]'::jsonb)))
  INTO v_result FROM public.music_pins p
  WHERE p.id=v.pin_id AND p.owner_id=v.owner_id AND p.visibility='UNLISTED';
 ELSIF v.event_id IS NOT NULL THEN
  SELECT jsonb_build_object('objectType','TRAVEL_EVENT','id',e.id,'title',e.title,
     'payload',jsonb_build_object('eventDate',e.event_date,'locationName',e.location_name,'body',e.body,
       'trackIds',COALESCE((SELECT jsonb_agg(et.track_id ORDER BY et.position) FROM public.event_tracks et WHERE et.event_id=e.id),'[]'::jsonb)))
  INTO v_result FROM public.travel_events e
  WHERE e.id=v.event_id AND e.owner_id=v.owner_id AND e.visibility='UNLISTED';
 ELSE
  SELECT jsonb_build_object('objectType','JOURNEY','id',j.id,'title',j.title,
     'payload',jsonb_build_object('stops',COALESCE((SELECT jsonb_agg(jsonb_build_object(
       'kind',CASE WHEN jp.place_id IS NOT NULL THEN 'PLACE' ELSE 'PIN' END,
       'id',COALESCE(jp.place_id,jp.pin_id),'position',jp.position) ORDER BY jp.position)
     FROM public.journey_places jp WHERE jp.journey_id=j.id
        AND (jp.pin_id IS NULL OR EXISTS(SELECT 1 FROM public.music_pins pi WHERE pi.id=jp.pin_id AND pi.visibility='PUBLIC'))
        AND (jp.place_id IS NULL OR EXISTS(SELECT 1 FROM public.places plc WHERE plc.id=jp.place_id AND plc.is_published))
     ),'[]'::jsonb)))
  INTO v_result FROM public.music_journeys j
  WHERE j.id=v.journey_id AND j.owner_id=v.owner_id AND j.visibility='UNLISTED';
 END IF;
 RETURN v_result;
END;$$;

-- Return exactly one server-confirmed path. No arbitrary path argument.
CREATE OR REPLACE FUNCTION public.edge_media_path(
 p_user uuid,p_kind text,p_object uuid,p_photo uuid DEFAULT NULL,p_token_hash text DEFAULT NULL
) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_path text;v_owner uuid;v_vis text;v_allowed boolean:=false;v_token_col text;
BEGIN
 IF p_kind NOT IN ('PIN','EVENT','PLAYLIST','PLACE') OR p_object IS NULL THEN RETURN NULL; END IF;
 IF p_kind='PIN' THEN
    SELECT p.owner_id,p.visibility,ph.storage_path INTO v_owner,v_vis,v_path
    FROM public.music_pins p JOIN public.pin_photos ph ON ph.pin_id=p.id
    WHERE p.id=p_object AND ph.id=p_photo;
    v_token_col:='pin_id';
 ELSIF p_kind='EVENT' THEN
    SELECT e.owner_id,e.visibility,ph.storage_path INTO v_owner,v_vis,v_path
    FROM public.travel_events e JOIN public.event_photos ph ON ph.event_id=e.id
    WHERE e.id=p_object AND ph.id=p_photo;
    v_token_col:='event_id';
 ELSIF p_kind='PLAYLIST' THEN
    SELECT owner_id,CASE WHEN is_editorial AND published_at IS NULL THEN 'PRIVATE' ELSE visibility END,cover_path
    INTO v_owner,v_vis,v_path FROM public.playlists WHERE id=p_object;
    v_token_col:='playlist_id';
 ELSE
    SELECT NULL::uuid,CASE WHEN is_published THEN 'PUBLIC' ELSE 'PRIVATE' END, cover_path
    INTO v_owner,v_vis,v_path FROM public.places WHERE id=p_object;
 END IF;
 IF v_path IS NULL THEN RETURN NULL; END IF;
 v_allowed := v_vis='PUBLIC' OR (p_user IS NOT NULL AND p_user=v_owner);
 IF p_kind='PLACE' THEN v_allowed:=v_vis='PUBLIC'; END IF;
 IF NOT v_allowed AND v_vis='UNLISTED' AND p_token_hash ~ '^[a-f0-9]{64}$' THEN
    v_allowed:=EXISTS(SELECT 1 FROM public.share_tokens t
     WHERE t.token_hash=p_token_hash AND t.owner_id=v_owner AND t.revoked_at IS NULL
       AND (t.expires_at IS NULL OR t.expires_at>now())
       AND ((p_kind='PIN' AND t.pin_id=p_object) OR (p_kind='EVENT' AND t.event_id=p_object)
           OR (p_kind='PLAYLIST' AND t.playlist_id=p_object)));
 END IF;
 IF NOT v_allowed THEN RETURN NULL; END IF;
 -- Only paths belonging to configured bucket are accepted; not full URLs.
 IF v_path LIKE 'http%' OR position('..' in v_path)>0 OR left(v_path,1)='/' THEN RETURN NULL; END IF;
 IF NOT EXISTS (SELECT 1 FROM storage.objects so WHERE so.bucket_id='soundscape-media' AND so.name=v_path) THEN RETURN NULL; END IF;
 RETURN jsonb_build_object('path',v_path);
END;$$;

-- Limit access to all service-only RPCs; no reliance on PUBLIC default EXECUTE.
REVOKE ALL ON FUNCTION public.edge_reserve_quota(text,uuid), public.edge_release_generation(uuid),
 public.edge_ingest_audius(text,text,text,integer,text), public.edge_mark_streamable(uuid,text,boolean),
 public.edge_create_share(uuid,text,uuid,text,timestamptz),public.edge_revoke_share(uuid,uuid),
 public.edge_open_share(text),public.edge_media_path(uuid,text,uuid,uuid,text)
 FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.edge_reserve_quota(text,uuid), public.edge_release_generation(uuid),
 public.edge_ingest_audius(text,text,text,integer,text), public.edge_mark_streamable(uuid,text,boolean),
 public.edge_create_share(uuid,text,uuid,text,timestamptz),public.edge_revoke_share(uuid,uuid),
 public.edge_open_share(text),public.edge_media_path(uuid,text,uuid,uuid,text)
 TO service_role;
