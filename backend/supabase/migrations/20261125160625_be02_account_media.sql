-- The Auth user remains available for retry until Storage cleanup succeeds.
CREATE TABLE internal.account_deletions (
  user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE
);
ALTER TABLE internal.account_deletions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON internal.account_deletions FROM PUBLIC, anon, authenticated;

CREATE FUNCTION internal.remove_account_quota()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  DELETE FROM internal.api_quota_counters q WHERE q.scope_type = 'USER' AND q.subject_key = OLD.id::text;
  RETURN OLD;
END;
$$;
REVOKE ALL ON FUNCTION internal.remove_account_quota() FROM PUBLIC, anon, authenticated;
CREATE TRIGGER remove_soundscape_account_quota AFTER DELETE ON auth.users
FOR EACH ROW EXECUTE FUNCTION internal.remove_account_quota();

CREATE FUNCTION internal.active_account(account uuid)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT EXISTS (SELECT 1 FROM auth.users u WHERE u.id = account)
    AND NOT EXISTS (SELECT 1 FROM internal.account_deletions d WHERE d.user_id = account);
$$;
REVOKE ALL ON FUNCTION internal.active_account(uuid) FROM PUBLIC, anon, authenticated;

CREATE FUNCTION internal.check_api_account()
RETURNS void LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  session_id uuid := (auth.jwt()->>'session_id')::uuid;
BEGIN
  IF auth.uid() IS NOT NULL AND NOT internal.active_account(auth.uid()) THEN
    PERFORM internal.fail('PT401', 'ACCOUNT_UNAVAILABLE');
  END IF;
  IF auth.uid() IS NOT NULL AND session_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM auth.sessions s WHERE s.id = session_id AND s.user_id = auth.uid()
  ) THEN
    PERFORM internal.fail('PT401', 'SESSION_UNAVAILABLE');
  END IF;
END;
$$;
REVOKE ALL ON FUNCTION internal.check_api_account() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION internal.check_api_account() TO anon, authenticated, service_role;
ALTER ROLE authenticator SET pgrst.db_pre_request = 'internal.check_api_account';
GRANT USAGE ON SCHEMA internal TO service_role;

CREATE FUNCTION public.edge_verify_session(p_user uuid, p_session uuid, p_allow_pending boolean)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT EXISTS (SELECT 1 FROM auth.sessions s WHERE s.id = p_session AND s.user_id = p_user)
    AND (p_allow_pending IS TRUE OR internal.active_account(p_user));
$$;
REVOKE ALL ON FUNCTION public.edge_verify_session(uuid, uuid, boolean) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.edge_verify_session(uuid, uuid, boolean) TO service_role;

CREATE OR REPLACE FUNCTION internal.require_user()
RETURNS uuid LANGUAGE plpgsql STABLE SET search_path = '' AS $$
BEGIN
  IF auth.uid() IS NULL THEN PERFORM internal.fail('PT401', 'AUTH_REQUIRED'); END IF;
  PERFORM internal.check_api_account();
  RETURN auth.uid();
END;
$$;

CREATE FUNCTION internal.media_account_allowed()
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT internal.active_account(auth.uid()) AND (
    auth.jwt()->>'session_id' IS NULL OR EXISTS (
      SELECT 1 FROM auth.sessions s
      WHERE s.id = (auth.jwt()->>'session_id')::uuid AND s.user_id = auth.uid()
    )
  );
$$;
REVOKE ALL ON FUNCTION internal.media_account_allowed() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION internal.media_account_allowed() TO authenticated;
CREATE POLICY soundscape_media_active_account ON storage.objects AS RESTRICTIVE
  FOR ALL TO authenticated USING (bucket_id <> 'soundscape-media' OR (SELECT internal.media_account_allowed()))
  WITH CHECK (bucket_id <> 'soundscape-media' OR (SELECT internal.media_account_allowed()));

-- Remove only the account's material. Foreign diary entries are detached and retained.
CREATE FUNCTION internal.detach_deleted_pin()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  UPDATE public.travel_events SET music_pin_id = NULL,
    location_name = CASE WHEN location_name IS NULL AND geom IS NULL THEN 'Удалённая отметка' ELSE location_name END,
    revision = revision + 1, updated_at = now()
  WHERE music_pin_id = OLD.id;
  UPDATE public.music_journeys j SET revision = revision + 1, updated_at = now()
  WHERE EXISTS (SELECT 1 FROM public.journey_places s WHERE s.pin_id = OLD.id AND s.journey_id = j.id);
  RETURN OLD;
END;
$$;
REVOKE ALL ON FUNCTION internal.detach_deleted_pin() FROM PUBLIC, anon, authenticated;
CREATE TRIGGER detach_deleted_pin BEFORE DELETE ON public.music_pins
FOR EACH ROW EXECUTE FUNCTION internal.detach_deleted_pin();

ALTER TABLE public.journey_places DROP CONSTRAINT journey_places_pin_id_fkey,
  ADD CONSTRAINT journey_places_pin_id_fkey FOREIGN KEY (pin_id) REFERENCES public.music_pins(id) ON DELETE CASCADE;
ALTER TABLE public.playlists DROP CONSTRAINT playlists_owner_id_fkey,
  ADD CONSTRAINT playlists_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.profiles(id) ON DELETE CASCADE;
ALTER TABLE public.music_pins DROP CONSTRAINT music_pins_owner_id_fkey,
  ADD CONSTRAINT music_pins_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.profiles(id) ON DELETE CASCADE;
ALTER TABLE public.travel_events DROP CONSTRAINT travel_events_owner_id_fkey,
  ADD CONSTRAINT travel_events_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.profiles(id) ON DELETE CASCADE;
ALTER TABLE public.music_journeys DROP CONSTRAINT music_journeys_owner_id_fkey,
  ADD CONSTRAINT music_journeys_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.profiles(id) ON DELETE CASCADE;

CREATE FUNCTION public.edge_begin_account_deletion(p_user uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  paths jsonb;
BEGIN
  IF p_user IS NULL OR NOT EXISTS (SELECT 1 FROM auth.users u WHERE u.id = p_user) THEN
    RETURN jsonb_build_object('paths', '[]'::jsonb);
  END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(p_user::text, 241510));
  INSERT INTO internal.account_deletions(user_id) VALUES (p_user) ON CONFLICT DO NOTHING;
  SELECT COALESCE(jsonb_agg(name ORDER BY name), '[]'::jsonb) INTO paths FROM (
    SELECT s.name FROM storage.objects s WHERE s.bucket_id = 'soundscape-media'
      AND split_part(s.name, '/', 1) = 'users' AND split_part(s.name, '/', 2) = p_user::text
    ORDER BY s.name LIMIT 100
  ) objects;
  RETURN jsonb_build_object('paths', paths);
END;
$$;

-- Editorial artwork belongs to the shared corpus. Release Auth ownership before removal.
CREATE FUNCTION public.edge_finish_account_media(p_user uuid)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM internal.account_deletions d WHERE d.user_id = p_user)
    OR EXISTS (SELECT 1 FROM storage.objects s WHERE s.bucket_id = 'soundscape-media'
      AND split_part(s.name, '/', 1) = 'users' AND split_part(s.name, '/', 2) = p_user::text) THEN
    RETURN false;
  END IF;
  UPDATE storage.objects SET owner = NULL, owner_id = NULL
  WHERE bucket_id = 'soundscape-media' AND split_part(name, '/', 1) = 'editorial'
    AND (owner = p_user OR owner_id = p_user::text);
  RETURN true;
END;
$$;

CREATE TABLE internal.media_cleanup (
  path text PRIMARY KEY,
  owner_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  claimed boolean NOT NULL DEFAULT false,
  completed boolean NOT NULL DEFAULT false
);
ALTER TABLE internal.media_cleanup ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON internal.media_cleanup FROM PUBLIC, anon, authenticated;

CREATE FUNCTION internal.media_path_available(asset_path text)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT NOT EXISTS (SELECT 1 FROM internal.media_cleanup q WHERE q.path = asset_path AND q.claimed);
$$;
REVOKE ALL ON FUNCTION internal.media_path_available(text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION internal.media_path_available(text) TO authenticated;
CREATE POLICY soundscape_media_available_insert ON storage.objects AS RESTRICTIVE
  FOR INSERT TO authenticated WITH CHECK (bucket_id <> 'soundscape-media' OR internal.media_path_available(name));
CREATE POLICY soundscape_media_available_update ON storage.objects AS RESTRICTIVE
  FOR UPDATE TO authenticated
  USING (bucket_id <> 'soundscape-media' OR internal.media_path_available(name))
  WITH CHECK (bucket_id <> 'soundscape-media' OR internal.media_path_available(name));

-- Only SoundScape links may create bearer access to these files.
CREATE POLICY soundscape_media_no_signed_reads ON storage.objects AS RESTRICTIVE
  FOR SELECT TO authenticated USING (bucket_id <> 'soundscape-media'
    OR NOT storage.allow_any_operation(ARRAY['object.sign', 'object.sign_many']));
CREATE POLICY soundscape_media_no_signed_uploads ON storage.objects AS RESTRICTIVE
  FOR INSERT TO authenticated WITH CHECK (bucket_id <> 'soundscape-media'
    OR NOT storage.allow_only_operation('object.sign_upload_url'));

CREATE FUNCTION internal.media_referenced(asset_path text)
RETURNS boolean LANGUAGE sql STABLE SET search_path = '' AS $$
  SELECT EXISTS (SELECT 1 FROM public.profiles p WHERE p.avatar_path = asset_path)
    OR EXISTS (SELECT 1 FROM public.playlists p WHERE p.cover_path = asset_path)
    OR EXISTS (SELECT 1 FROM public.pin_photos f WHERE f.storage_path = asset_path)
    OR EXISTS (SELECT 1 FROM public.event_photos f WHERE f.storage_path = asset_path)
    OR EXISTS (SELECT 1 FROM public.places p WHERE p.cover_path = asset_path);
$$;
REVOKE ALL ON FUNCTION internal.media_referenced(text) FROM PUBLIC, anon, authenticated;

CREATE FUNCTION internal.can_delete_media(asset_path text)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT NOT internal.media_referenced(asset_path);
$$;
REVOKE ALL ON FUNCTION internal.can_delete_media(text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION internal.can_delete_media(text) TO authenticated;
ALTER POLICY soundscape_media_delete ON storage.objects USING (
  bucket_id = 'soundscape-media' AND internal.can_delete_media(name) AND (
    (split_part(name, '/', 1) = 'users' AND split_part(name, '/', 2) = (SELECT auth.uid())::text)
    OR (split_part(name, '/', 1) = 'editorial' AND (SELECT internal.is_editor()))
  )
);

CREATE INDEX profiles_avatar_path_idx ON public.profiles (avatar_path) WHERE avatar_path IS NOT NULL;
CREATE INDEX playlists_cover_path_idx ON public.playlists (cover_path) WHERE cover_path IS NOT NULL;
CREATE INDEX pin_photos_storage_path_idx ON public.pin_photos (storage_path);
CREATE INDEX event_photos_storage_path_idx ON public.event_photos (storage_path);
CREATE INDEX places_cover_path_idx ON public.places (cover_path) WHERE cover_path IS NOT NULL;

CREATE FUNCTION internal.guard_media_upload()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  account uuid;
BEGIN
  IF NEW.bucket_id <> 'soundscape-media' OR split_part(NEW.name, '/', 1) <> 'users' THEN
    RETURN NEW;
  END IF;
  IF NEW.name !~ '^users/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[^/]+'
    OR position('..' in NEW.name) > 0 OR position(chr(92) in NEW.name) > 0 THEN
    RAISE insufficient_privilege;
  END IF;
  account := split_part(NEW.name, '/', 2)::uuid;
  PERFORM pg_advisory_xact_lock_shared(hashtextextended(account::text, 241510));
  IF NOT internal.active_account(account) OR NOT internal.media_path_available(NEW.name) THEN
    RAISE insufficient_privilege;
  END IF;
  IF TG_OP = 'UPDATE' AND NEW.name IS DISTINCT FROM OLD.name AND internal.media_referenced(OLD.name) THEN
    RAISE insufficient_privilege;
  END IF;
  RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION internal.guard_media_upload() FROM PUBLIC, anon, authenticated;
CREATE TRIGGER soundscape_media_guard_upload BEFORE INSERT OR UPDATE OF name, metadata ON storage.objects
FOR EACH ROW EXECUTE FUNCTION internal.guard_media_upload();

CREATE FUNCTION internal.queue_removed_media()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  old_path text := to_jsonb(OLD)->>TG_ARGV[0];
  asset_owner uuid;
BEGIN
  IF old_path IS NULL OR (TG_OP = 'UPDATE' AND old_path IS NOT DISTINCT FROM to_jsonb(NEW)->>TG_ARGV[0])
      OR old_path !~ '^users/[0-9a-f-]{36}/' THEN RETURN NULL; END IF;
  asset_owner := split_part(old_path, '/', 2)::uuid;
  INSERT INTO internal.media_cleanup(path, owner_id)
    SELECT old_path, p.id FROM public.profiles p WHERE p.id = asset_owner
    ON CONFLICT DO NOTHING;
  RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION internal.queue_removed_media() FROM PUBLIC, anon, authenticated;
CREATE TRIGGER queue_removed_media AFTER DELETE OR UPDATE OF avatar_path ON public.profiles
FOR EACH ROW EXECUTE FUNCTION internal.queue_removed_media('avatar_path');
CREATE TRIGGER queue_removed_media AFTER DELETE OR UPDATE OF cover_path ON public.playlists
FOR EACH ROW EXECUTE FUNCTION internal.queue_removed_media('cover_path');
CREATE TRIGGER queue_removed_media AFTER DELETE OR UPDATE OF storage_path ON public.pin_photos
FOR EACH ROW EXECUTE FUNCTION internal.queue_removed_media('storage_path');
CREATE TRIGGER queue_removed_media AFTER DELETE OR UPDATE OF storage_path ON public.event_photos
FOR EACH ROW EXECUTE FUNCTION internal.queue_removed_media('storage_path');

CREATE OR REPLACE FUNCTION internal.assert_media(p_path text, p_owner uuid)
RETURNS void LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  IF p_path IS NULL THEN RETURN; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(p_path, 963421));
  IF p_path NOT LIKE ('users/' || p_owner::text || '/%')
    OR position('..' in p_path) > 0 OR position(chr(92) in p_path) > 0
    OR EXISTS (SELECT 1 FROM internal.media_cleanup q WHERE q.path = p_path AND q.claimed)
    OR NOT EXISTS (SELECT 1 FROM storage.objects s WHERE s.bucket_id = 'soundscape-media' AND s.name = p_path)
  THEN
    PERFORM internal.fail('PT422', 'INVALID_MEDIA_PATH');
  END IF;
END;
$$;
ALTER FUNCTION internal.assert_media_array(jsonb, uuid, integer) VOLATILE;

CREATE FUNCTION public.edge_claim_media_cleanup(p_user uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  candidate text;
  paths text[] := '{}';
BEGIN
  IF NOT internal.active_account(p_user) THEN RAISE insufficient_privilege; END IF;
  FOR candidate IN SELECT q.path FROM internal.media_cleanup q WHERE q.owner_id = p_user AND NOT q.completed
      ORDER BY q.claimed DESC, q.path LIMIT 100
  LOOP
    IF cardinality(paths) >= 20 THEN EXIT; END IF;
    IF NOT pg_try_advisory_xact_lock(hashtextextended(candidate, 963421)) THEN CONTINUE; END IF;
    IF NOT internal.media_referenced(candidate) THEN
      UPDATE internal.media_cleanup SET claimed = true WHERE path = candidate;
      IF FOUND THEN paths := array_append(paths, candidate); END IF;
    ELSE
      DELETE FROM internal.media_cleanup WHERE path = candidate AND NOT claimed;
    END IF;
  END LOOP;
  RETURN to_jsonb(paths);
END;
$$;

CREATE FUNCTION public.edge_complete_media_cleanup(p_user uuid, p_paths text[])
RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path = '' AS $$
  UPDATE internal.media_cleanup q SET completed = true WHERE q.owner_id = p_user AND q.claimed AND q.path = ANY(p_paths)
    AND NOT EXISTS (SELECT 1 FROM storage.objects s WHERE s.bucket_id = 'soundscape-media' AND s.name = q.path);
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
    SELECT NULL::uuid,CASE WHEN is_published THEN 'PUBLIC' ELSE 'PRIVATE' END, cover_path
    INTO v_owner,v_vis,v_path FROM public.places WHERE id=p_object;
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
    v_allowed := v_vis='PUBLIC' OR (p_user IS NOT NULL AND p_user=v_owner);
 END IF;
 IF NOT v_allowed THEN RETURN NULL; END IF;
 IF position('..' in v_path)>0 OR position(chr(92) in v_path)>0 THEN RETURN NULL; END IF;
 IF NOT ((v_owner IS NOT NULL AND v_path LIKE 'users/' || v_owner::text || '/_%')
     OR ((p_kind='PLACE' OR v_editorial) AND v_path LIKE 'editorial/_%')) THEN RETURN NULL; END IF;
 IF NOT EXISTS (SELECT 1 FROM storage.objects so WHERE so.bucket_id='soundscape-media' AND so.name=v_path) THEN RETURN NULL; END IF;
 RETURN jsonb_build_object('path',v_path);
END;$$;


REVOKE ALL ON FUNCTION public.edge_begin_account_deletion(uuid), public.edge_finish_account_media(uuid),
  public.edge_claim_media_cleanup(uuid), public.edge_complete_media_cleanup(uuid, text[]) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.edge_begin_account_deletion(uuid), public.edge_finish_account_media(uuid),
  public.edge_claim_media_cleanup(uuid), public.edge_complete_media_cleanup(uuid, text[]) TO service_role;

NOTIFY pgrst, 'reload config';
NOTIFY pgrst, 'reload schema';
