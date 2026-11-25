-- Reuse the existing revision-checked mutations under an optional replay key.
CREATE TABLE internal.mutation_receipts (
  owner_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  request_id uuid NOT NULL,
  operation text NOT NULL,
  request_hash bytea NOT NULL,
  result jsonb,
  PRIMARY KEY (owner_id, request_id)
);
ALTER TABLE internal.mutation_receipts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON internal.mutation_receipts FROM PUBLIC, anon, authenticated;

ALTER FUNCTION public.save_playlist(jsonb) SET SCHEMA internal;
REVOKE ALL ON FUNCTION internal.save_playlist(jsonb) FROM PUBLIC, anon, authenticated;
ALTER FUNCTION public.delete_playlist(jsonb) SET SCHEMA internal;
REVOKE ALL ON FUNCTION internal.delete_playlist(jsonb) FROM PUBLIC, anon, authenticated;
ALTER FUNCTION public.save_music_pin(jsonb) SET SCHEMA internal;
REVOKE ALL ON FUNCTION internal.save_music_pin(jsonb) FROM PUBLIC, anon, authenticated;
ALTER FUNCTION public.delete_music_pin(jsonb) SET SCHEMA internal;
REVOKE ALL ON FUNCTION internal.delete_music_pin(jsonb) FROM PUBLIC, anon, authenticated;
ALTER FUNCTION public.save_travel_event(jsonb) SET SCHEMA internal;
REVOKE ALL ON FUNCTION internal.save_travel_event(jsonb) FROM PUBLIC, anon, authenticated;
ALTER FUNCTION public.delete_travel_event(jsonb) SET SCHEMA internal;
REVOKE ALL ON FUNCTION internal.delete_travel_event(jsonb) FROM PUBLIC, anon, authenticated;
ALTER FUNCTION public.save_music_journey(jsonb) SET SCHEMA internal;
REVOKE ALL ON FUNCTION internal.save_music_journey(jsonb) FROM PUBLIC, anon, authenticated;
ALTER FUNCTION public.delete_music_journey(jsonb) SET SCHEMA internal;
REVOKE ALL ON FUNCTION internal.delete_music_journey(jsonb) FROM PUBLIC, anon, authenticated;
ALTER FUNCTION public.copy_public_playlist(jsonb) SET SCHEMA internal;
REVOKE ALL ON FUNCTION internal.copy_public_playlist(jsonb) FROM PUBLIC, anon, authenticated;

CREATE FUNCTION internal.apply_mutation(operation_name text, request jsonb)
RETURNS jsonb LANGUAGE plpgsql SET search_path = '' AS $$
DECLARE
  caller uuid := internal.require_user();
  replay_id uuid;
  request_hash bytea;
  receipt internal.mutation_receipts%ROWTYPE;
  mutation_result jsonb;
BEGIN
  PERFORM pg_advisory_xact_lock_shared(hashtextextended(caller::text, 241510));
  caller := internal.require_user();
  PERFORM internal.assert_object(request);
  IF request ? 'idempotencyKey' THEN
    IF jsonb_typeof(request->'idempotencyKey') IS DISTINCT FROM 'string' THEN
      PERFORM internal.fail('PT422', 'INVALID_IDEMPOTENCY_KEY');
    END IF;
    replay_id := (request->>'idempotencyKey')::uuid;
    request := request - 'idempotencyKey';
    request_hash := sha256(convert_to(request::text, 'UTF8'));
    INSERT INTO internal.mutation_receipts(owner_id, request_id, operation, request_hash)
    VALUES (caller, replay_id, operation_name, request_hash) ON CONFLICT ON CONSTRAINT mutation_receipts_pkey DO NOTHING;
    IF NOT FOUND THEN
      SELECT r.* INTO STRICT receipt FROM internal.mutation_receipts r
      WHERE r.owner_id = caller AND r.request_id = replay_id;
      IF receipt.operation <> operation_name OR receipt.request_hash <> request_hash THEN
        PERFORM internal.fail('PT409', 'IDEMPOTENCY_KEY_REUSED');
      END IF;
      RETURN receipt.result;
    END IF;
  END IF;

  CASE operation_name
    WHEN 'save_playlist' THEN mutation_result := internal.save_playlist(request);
    WHEN 'delete_playlist' THEN mutation_result := internal.delete_playlist(request);
    WHEN 'save_music_pin' THEN mutation_result := internal.save_music_pin(request);
    WHEN 'delete_music_pin' THEN mutation_result := internal.delete_music_pin(request);
    WHEN 'save_travel_event' THEN mutation_result := internal.save_travel_event(request);
    WHEN 'delete_travel_event' THEN mutation_result := internal.delete_travel_event(request);
    WHEN 'save_music_journey' THEN mutation_result := internal.save_music_journey(request);
    WHEN 'delete_music_journey' THEN mutation_result := internal.delete_music_journey(request);
    WHEN 'copy_public_playlist' THEN mutation_result := internal.copy_public_playlist(request);
    ELSE RAISE EXCEPTION 'Unknown mutation';
  END CASE;

  IF replay_id IS NOT NULL THEN
    UPDATE internal.mutation_receipts r SET result = mutation_result
    WHERE r.owner_id = caller AND r.request_id = replay_id;
  END IF;
  RETURN mutation_result;
EXCEPTION WHEN data_exception OR check_violation OR not_null_violation THEN
  PERFORM internal.fail('PT422', 'INVALID_MUTATION_DATA');
  RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION internal.apply_mutation(text, jsonb) FROM PUBLIC, anon, authenticated;

CREATE FUNCTION public.save_playlist(jsonb)
RETURNS jsonb LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
  SELECT internal.apply_mutation('save_playlist', $1);
$$;
REVOKE ALL ON FUNCTION public.save_playlist(jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.save_playlist(jsonb) TO authenticated;

CREATE FUNCTION public.delete_playlist(jsonb)
RETURNS jsonb LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
  SELECT internal.apply_mutation('delete_playlist', $1);
$$;
REVOKE ALL ON FUNCTION public.delete_playlist(jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.delete_playlist(jsonb) TO authenticated;

CREATE FUNCTION public.save_music_pin(jsonb)
RETURNS jsonb LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
  SELECT internal.apply_mutation('save_music_pin', $1);
$$;
REVOKE ALL ON FUNCTION public.save_music_pin(jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.save_music_pin(jsonb) TO authenticated;

CREATE FUNCTION public.delete_music_pin(jsonb)
RETURNS jsonb LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
  SELECT internal.apply_mutation('delete_music_pin', $1);
$$;
REVOKE ALL ON FUNCTION public.delete_music_pin(jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.delete_music_pin(jsonb) TO authenticated;

CREATE FUNCTION public.save_travel_event(jsonb)
RETURNS jsonb LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
  SELECT internal.apply_mutation('save_travel_event', $1);
$$;
REVOKE ALL ON FUNCTION public.save_travel_event(jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.save_travel_event(jsonb) TO authenticated;

CREATE FUNCTION public.delete_travel_event(jsonb)
RETURNS jsonb LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
  SELECT internal.apply_mutation('delete_travel_event', $1);
$$;
REVOKE ALL ON FUNCTION public.delete_travel_event(jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.delete_travel_event(jsonb) TO authenticated;

CREATE FUNCTION public.save_music_journey(jsonb)
RETURNS jsonb LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
  SELECT internal.apply_mutation('save_music_journey', $1);
$$;
REVOKE ALL ON FUNCTION public.save_music_journey(jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.save_music_journey(jsonb) TO authenticated;

CREATE FUNCTION public.delete_music_journey(jsonb)
RETURNS jsonb LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
  SELECT internal.apply_mutation('delete_music_journey', $1);
$$;
REVOKE ALL ON FUNCTION public.delete_music_journey(jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.delete_music_journey(jsonb) TO authenticated;

CREATE FUNCTION public.copy_public_playlist(jsonb)
RETURNS jsonb LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
  SELECT internal.apply_mutation('copy_public_playlist', $1);
$$;
REVOKE ALL ON FUNCTION public.copy_public_playlist(jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.copy_public_playlist(jsonb) TO authenticated;

CREATE OR REPLACE FUNCTION public.list_travel_events(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  caller uuid := internal.require_user();
  page_limit integer := COALESCE(($1->>'limit')::integer, 20);
  page_offset integer := COALESCE(($1->>'offset')::integer, 0);
  first_date date := ($1->>'dateFrom')::date;
  last_date date := ($1->>'dateTo')::date;
  location_query text := nullif(btrim($1->>'locationQuery'), '');
  map_only boolean := COALESCE(($1->>'mapOnly')::boolean, false);
  items jsonb;
  has_more boolean;
BEGIN
  PERFORM internal.assert_object($1);
  PERFORM internal.assert_pagination(page_limit, page_offset);
  IF first_date > last_date OR char_length(location_query) > 150 THEN
    PERFORM internal.fail('PT422', 'INVALID_EVENT_FILTER');
  END IF;
  WITH page AS (
    SELECT e.id, e.event_date, COALESCE(e.geom, p.geom) AS map_geom,
      row_number() OVER (ORDER BY e.event_date DESC, e.id) AS position
    FROM public.travel_events e LEFT JOIN public.music_pins p ON p.id = e.music_pin_id
      AND (p.owner_id = caller OR p.visibility = 'PUBLIC')
    WHERE e.owner_id = caller AND (first_date IS NULL OR e.event_date >= first_date)
      AND (last_date IS NULL OR e.event_date <= last_date)
      AND (location_query IS NULL OR strpos(lower(COALESCE(e.location_name, p.title, '')), lower(location_query)) > 0)
      AND (NOT map_only OR COALESCE(e.geom, p.geom) IS NOT NULL)
    ORDER BY e.event_date DESC, e.id LIMIT page_limit + 1 OFFSET page_offset
  )
  SELECT COALESCE(jsonb_agg(internal.event_detail(id) || jsonb_build_object(
       'mapLatitude', extensions.ST_Y(map_geom::extensions.geometry),
       'mapLongitude', extensions.ST_X(map_geom::extensions.geometry))
      ORDER BY position) FILTER (WHERE position <= page_offset::bigint + page_limit), '[]'::jsonb),
    count(*) > page_limit INTO items, has_more FROM page;
  RETURN jsonb_build_object('items', items, 'limit', page_limit, 'offset', page_offset, 'hasMore', has_more);
EXCEPTION WHEN data_exception THEN
  PERFORM internal.fail('PT422', 'INVALID_EVENT_FILTER');
  RETURN NULL;
END;
$$;

CREATE FUNCTION internal.journey_durations(playlist uuid)
RETURNS jsonb LANGUAGE sql STABLE SET search_path = '' AS $$
  SELECT jsonb_build_object('durationMs', COALESCE(sum(t.duration_ms), 0),
    'inAppDurationMs', COALESCE(sum(t.duration_ms) FILTER (
      WHERE internal.track_view(t.id)->>'availability' = 'IN_APP'), 0))
  FROM public.playlist_tracks pt JOIN public.tracks t ON t.id = pt.track_id
  WHERE pt.playlist_id = playlist;
$$;
REVOKE ALL ON FUNCTION internal.journey_durations(uuid) FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION internal.journey_detail(p_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
 SELECT jsonb_build_object(
   'id',j.id,'ownerId',j.owner_id,'title',j.title,
   'playlistId',CASE WHEN j.playlist_id IS NULL OR internal.can_read_playlist(j.playlist_id)
     THEN j.playlist_id ELSE NULL END,'visibility',j.visibility,'revision',j.revision,
   'stops',COALESCE((SELECT jsonb_agg(jsonb_build_object(
          'kind',CASE WHEN jp.place_id IS NOT NULL THEN 'PLACE' ELSE 'PIN' END,
          'id',COALESCE(jp.place_id,jp.pin_id),'position',jp.position)
          ORDER BY jp.position)
         FROM public.journey_places jp WHERE jp.journey_id=j.id
           AND (jp.pin_id IS NULL OR internal.can_read_pin(jp.pin_id))
           AND (jp.place_id IS NULL OR EXISTS (SELECT 1 FROM public.places plc WHERE plc.id=jp.place_id AND plc.is_published))
         ),'[]'::jsonb)
 ) || internal.journey_durations(CASE WHEN internal.can_read_playlist(j.playlist_id)
      THEN j.playlist_id END) FROM public.music_journeys j WHERE j.id=p_id;
$$;

CREATE OR REPLACE FUNCTION public.edge_create_share(p_user uuid,p_type text,p_id uuid,p_hash text,p_expires timestamptz DEFAULT NULL)
RETURNS uuid LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_owner uuid; v_visibility text;v_share uuid;
BEGIN
 IF NOT EXISTS (SELECT 1 FROM public.profiles p WHERE p.id = p_user) OR p_id IS NULL OR p_hash !~ '^[a-f0-9]{64}$'
   OR p_type NOT IN ('PLAYLIST','MUSIC_PIN','TRAVEL_EVENT','JOURNEY')
   OR (p_expires IS NOT NULL AND (p_expires <= now() OR p_expires>now()+interval '90 days')) THEN
  RAISE EXCEPTION 'INVALID_SHARE_REQUEST' USING ERRCODE='22023';
 END IF;
 IF p_type='PLAYLIST' THEN SELECT owner_id,visibility INTO v_owner,v_visibility FROM public.playlists WHERE id=p_id FOR SHARE;
 ELSIF p_type='MUSIC_PIN' THEN SELECT owner_id,visibility INTO v_owner,v_visibility FROM public.music_pins WHERE id=p_id FOR SHARE;
 ELSIF p_type='TRAVEL_EVENT' THEN SELECT owner_id,visibility INTO v_owner,v_visibility FROM public.travel_events WHERE id=p_id FOR SHARE;
 ELSE SELECT owner_id,visibility INTO v_owner,v_visibility FROM public.music_journeys WHERE id=p_id FOR SHARE; END IF;
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
        'photoIds', COALESCE((SELECT jsonb_agg(f.id ORDER BY f.position) FROM public.pin_photos f WHERE f.pin_id=p.id),'[]'::jsonb),
        'trackIds',COALESCE((SELECT jsonb_agg(pt.track_id ORDER BY pt.position) FROM public.pin_tracks pt WHERE pt.pin_id=p.id),'[]'::jsonb)))
  INTO v_result FROM public.music_pins p
  WHERE p.id=v.pin_id AND p.owner_id=v.owner_id AND p.visibility='UNLISTED';
 ELSIF v.event_id IS NOT NULL THEN
  SELECT jsonb_build_object('objectType','TRAVEL_EVENT','id',e.id,'title',e.title,
     'payload',jsonb_build_object('eventDate',e.event_date,'locationName',e.location_name,'body',e.body,
       'photoIds', COALESCE((SELECT jsonb_agg(f.id ORDER BY f.position) FROM public.event_photos f WHERE f.event_id=e.id),'[]'::jsonb),
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
     ),'[]'::jsonb),
     'playlistId', CASE WHEN internal.public_playlist(j.playlist_id) THEN j.playlist_id END)
     || internal.journey_durations(CASE WHEN internal.public_playlist(j.playlist_id) THEN j.playlist_id END))
  INTO v_result FROM public.music_journeys j
  WHERE j.id=v.journey_id AND j.owner_id=v.owner_id AND j.visibility='UNLISTED';
 END IF;
 RETURN v_result;
END;$$;

-- Leaving UNLISTED revokes existing links, including when the object is unlisted again.
CREATE FUNCTION internal.revoke_changed_visibility()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  IF OLD.visibility = 'UNLISTED' AND NEW.visibility <> 'UNLISTED' THEN
    UPDATE public.share_tokens t SET revoked_at = now()
    WHERE t.revoked_at IS NULL AND CASE TG_TABLE_NAME
      WHEN 'playlists' THEN t.playlist_id = NEW.id
      WHEN 'music_pins' THEN t.pin_id = NEW.id
      WHEN 'travel_events' THEN t.event_id = NEW.id
      WHEN 'music_journeys' THEN t.journey_id = NEW.id
    END;
  END IF;
  RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION internal.revoke_changed_visibility() FROM PUBLIC, anon, authenticated;
CREATE TRIGGER revoke_changed_visibility AFTER UPDATE OF visibility ON public.playlists
FOR EACH ROW EXECUTE FUNCTION internal.revoke_changed_visibility();
CREATE TRIGGER revoke_changed_visibility AFTER UPDATE OF visibility ON public.music_pins
FOR EACH ROW EXECUTE FUNCTION internal.revoke_changed_visibility();
CREATE TRIGGER revoke_changed_visibility AFTER UPDATE OF visibility ON public.travel_events
FOR EACH ROW EXECUTE FUNCTION internal.revoke_changed_visibility();
CREATE TRIGGER revoke_changed_visibility AFTER UPDATE OF visibility ON public.music_journeys
FOR EACH ROW EXECUTE FUNCTION internal.revoke_changed_visibility();

CREATE FUNCTION public.edge_open_public(p_kind text, p_id uuid)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  content jsonb;
  title text;
BEGIN
  CASE p_kind
    WHEN 'PROFILE' THEN
      SELECT p.username, jsonb_build_object('username', p.username, 'hasAvatar', p.avatar_path IS NOT NULL)
        INTO title, content FROM public.profiles p WHERE p.id = p_id;
    WHEN 'PLAYLIST' THEN
      IF internal.public_playlist(p_id) THEN
        content := internal.playlist_detail(p_id) - 'coverPath' - 'placeId';
      END IF;
    WHEN 'MUSIC_PIN' THEN
      IF internal.public_pin(p_id) THEN
        content := internal.pin_detail(p_id) - 'photoPaths' || jsonb_build_object('photoIds',
          COALESCE((SELECT jsonb_agg(f.id ORDER BY f.position) FROM public.pin_photos f WHERE f.pin_id=p_id), '[]'::jsonb));
      END IF;
    WHEN 'TRAVEL_EVENT' THEN
      IF EXISTS (SELECT 1 FROM public.travel_events e WHERE e.id = p_id AND e.visibility = 'PUBLIC') THEN
        content := internal.event_detail(p_id) - 'photoPaths' || jsonb_build_object('photoIds',
          COALESCE((SELECT jsonb_agg(f.id ORDER BY f.position) FROM public.event_photos f WHERE f.event_id=p_id), '[]'::jsonb));
      END IF;
    WHEN 'JOURNEY' THEN
      IF EXISTS (SELECT 1 FROM public.music_journeys j WHERE j.id = p_id AND j.visibility = 'PUBLIC') THEN
        content := internal.journey_detail(p_id);
      END IF;
    ELSE RETURN NULL;
  END CASE;
  IF content IS NULL THEN RETURN NULL; END IF;
  RETURN jsonb_build_object('objectType', p_kind, 'id', p_id, 'title', COALESCE(title, content->>'title'),
    'payload', content - 'id' - 'ownerId' - 'title' - 'revision');
END;
$$;
REVOKE ALL ON FUNCTION public.edge_open_public(text, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.edge_open_public(text, uuid) TO service_role;

NOTIFY pgrst, 'reload schema';
