-- Profile and social contracts. The legacy username-only REST projection remains valid.
ALTER TABLE public.profiles ADD COLUMN avatar_path text;

CREATE FUNCTION internal.initialize_profile(account uuid)
RETURNS void LANGUAGE plpgsql SET search_path = '' AS $$
DECLARE
  candidate text := 'u_' || replace(account::text, '-', '');
BEGIN
  LOOP
    INSERT INTO public.profiles(id, username, role_code)
      VALUES (account, candidate, 'USER') ON CONFLICT DO NOTHING;
    EXIT WHEN FOUND OR EXISTS (SELECT 1 FROM public.profiles p WHERE p.id = account);
    candidate := 'u_' || replace(gen_random_uuid()::text, '-', '');
  END LOOP;
END;
$$;
REVOKE ALL ON FUNCTION internal.initialize_profile(uuid) FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION internal.handle_new_user()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  PERFORM internal.initialize_profile(NEW.id);
  RETURN NEW;
END;
$$;

SELECT internal.initialize_profile(u.id) FROM auth.users u
WHERE NOT EXISTS (SELECT 1 FROM public.profiles p WHERE p.id = u.id);

CREATE FUNCTION public.get_my_profile(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  caller uuid := internal.require_user();
  result jsonb;
BEGIN
  SELECT jsonb_build_object('id', p.id, 'username', p.username, 'role', p.role_code,
      'avatarPath', p.avatar_path, 'createdAt', p.created_at)
    INTO result FROM public.profiles p WHERE p.id = caller;
  IF NOT FOUND THEN PERFORM internal.fail('PT404', 'PROFILE_NOT_FOUND'); END IF;
  RETURN result;
END;
$$;

CREATE FUNCTION public.update_my_profile(jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  caller uuid := internal.require_user();
  new_username text := btrim($1->>'username');
  new_avatar text := $1->>'avatarPath';
BEGIN
  PERFORM internal.assert_object($1);
  IF ($1 - 'username' - 'avatarPath') <> '{}'::jsonb
      OR (NOT ($1 ? 'username') AND NOT ($1 ? 'avatarPath'))
      OR ($1 ? 'username' AND (jsonb_typeof($1->'username') IS DISTINCT FROM 'string'
          OR char_length(new_username) NOT BETWEEN 1 AND 40))
      OR ($1 ? 'avatarPath' AND jsonb_typeof($1->'avatarPath') NOT IN ('string', 'null')) THEN
    PERFORM internal.fail('PT422', 'INVALID_PROFILE');
  END IF;
  PERFORM internal.assert_media(new_avatar, caller);
  UPDATE public.profiles p SET
    username = CASE WHEN $1 ? 'username' THEN new_username ELSE p.username END,
    avatar_path = CASE WHEN $1 ? 'avatarPath' THEN new_avatar ELSE p.avatar_path END
  WHERE p.id = caller;
  RETURN public.get_my_profile('{}'::jsonb);
EXCEPTION WHEN unique_violation THEN
  PERFORM internal.fail('PT409', 'USERNAME_TAKEN');
  RETURN NULL;
END;
$$;

CREATE FUNCTION internal.public_playlist(target uuid)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT EXISTS (SELECT 1 FROM public.playlists p WHERE p.id = target
    AND p.visibility = 'PUBLIC' AND (NOT p.is_editorial OR p.published_at IS NOT NULL));
$$;

CREATE FUNCTION internal.public_pin(target uuid)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT EXISTS (SELECT 1 FROM public.music_pins p WHERE p.id = target AND p.visibility = 'PUBLIC');
$$;

REVOKE ALL ON FUNCTION internal.public_playlist(uuid), internal.public_pin(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION internal.public_playlist(uuid), internal.public_pin(uuid) TO authenticated;

ALTER POLICY playlist_likes_insert ON public.playlist_likes WITH CHECK (
  user_id = (SELECT auth.uid()) AND internal.public_playlist(playlist_id)
);
ALTER POLICY pin_likes_insert ON public.pin_likes WITH CHECK (
  user_id = (SELECT auth.uid()) AND internal.public_pin(pin_id)
);
ALTER POLICY saved_playlists_insert ON public.saved_playlists WITH CHECK (
  user_id = (SELECT auth.uid()) AND internal.public_playlist(playlist_id)
  AND EXISTS (SELECT 1 FROM public.playlists p WHERE p.id = playlist_id
    AND p.owner_id IS DISTINCT FROM (SELECT auth.uid()))
);
ALTER POLICY saved_playlists_select ON public.saved_playlists USING (
  user_id = (SELECT auth.uid()) AND internal.public_playlist(playlist_id)
);

CREATE FUNCTION public.get_public_profile(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  target uuid := ($1->>'profileId')::uuid;
  profile jsonb;
BEGIN
  SELECT jsonb_build_object('id', p.id, 'username', p.username, 'hasAvatar', p.avatar_path IS NOT NULL)
    INTO profile FROM public.profiles p WHERE p.id = target;
  IF NOT FOUND THEN PERFORM internal.fail('PT404', 'PROFILE_NOT_FOUND'); END IF;
  RETURN profile;
END;
$$;

CREATE FUNCTION public.list_public_profile_items(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  target uuid := ($1->>'profileId')::uuid;
  kind text := $1->>'kind';
  page_limit integer := COALESCE(($1->>'limit')::integer, 20);
  page_offset integer := COALESCE(($1->>'offset')::integer, 0);
  items jsonb;
  has_more boolean;
BEGIN
  PERFORM public.get_public_profile(jsonb_build_object('profileId', target));
  PERFORM internal.assert_pagination(page_limit, page_offset);
  IF kind IS NULL OR kind NOT IN ('PLAYLIST', 'MUSIC_PIN', 'TRAVEL_EVENT', 'JOURNEY') THEN
    PERFORM internal.fail('PT422', 'INVALID_PROFILE_ITEM_KIND');
  END IF;
  -- Public mode uses the same safe summary for every caller, including the owner.
  WITH candidates AS (
    SELECT p.id, p.title, p.created_at FROM public.playlists p
      WHERE kind = 'PLAYLIST' AND p.owner_id = target AND p.visibility = 'PUBLIC'
        AND (NOT p.is_editorial OR p.published_at IS NOT NULL)
    UNION ALL
    SELECT p.id, p.title, p.created_at FROM public.music_pins p
      WHERE kind = 'MUSIC_PIN' AND p.owner_id = target AND p.visibility = 'PUBLIC'
    UNION ALL
    SELECT e.id, e.title, e.created_at FROM public.travel_events e
      WHERE kind = 'TRAVEL_EVENT' AND e.owner_id = target AND e.visibility = 'PUBLIC'
    UNION ALL
    SELECT j.id, j.title, j.created_at FROM public.music_journeys j
      WHERE kind = 'JOURNEY' AND j.owner_id = target AND j.visibility = 'PUBLIC'
  ), page AS (
    SELECT *, row_number() OVER (ORDER BY created_at DESC, id) AS position
    FROM (SELECT * FROM candidates ORDER BY created_at DESC, id LIMIT page_limit + 1 OFFSET page_offset) selected
  )
  SELECT COALESCE(jsonb_agg(jsonb_build_object('id', id, 'title', title, 'kind', kind)
        ORDER BY position) FILTER (WHERE position <= page_limit), '[]'::jsonb),
      count(*) > page_limit INTO items, has_more FROM page;
  RETURN jsonb_build_object('items', items, 'limit', page_limit, 'offset', page_offset, 'hasMore', has_more);
END;
$$;

CREATE FUNCTION public.social_state(jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  caller uuid := auth.uid();
  target uuid := ($1->>'targetId')::uuid;
  kind text := $1->>'kind';
  change_state boolean := $1 ? 'active';
  desired boolean;
  active boolean;
  total bigint;
BEGIN
  PERFORM internal.assert_object($1);
  IF change_state THEN
    caller := internal.require_user();
    IF jsonb_typeof($1->'active') IS DISTINCT FROM 'boolean' THEN
      PERFORM internal.fail('PT422', 'INVALID_SOCIAL_STATE');
    END IF;
    desired := ($1->>'active')::boolean;
    PERFORM pg_advisory_xact_lock(hashtextextended(caller::text || ':' || kind || ':' || target::text, 0));
  END IF;
  IF kind IN ('FAVORITE', 'SAVED_PLAYLIST') THEN caller := internal.require_user(); END IF;

  IF kind = 'PLAYLIST_LIKE' THEN
    IF NOT (internal.public_playlist(target)) THEN PERFORM internal.fail('PT404', 'SOCIAL_TARGET_NOT_FOUND'); END IF;
    IF desired THEN
      INSERT INTO public.playlist_likes(user_id, playlist_id) VALUES (caller, target) ON CONFLICT DO NOTHING;
    ELSIF desired = false THEN
      DELETE FROM public.playlist_likes WHERE user_id = caller AND playlist_id = target;
    END IF;
    SELECT EXISTS (SELECT 1 FROM public.playlist_likes WHERE user_id = caller AND playlist_id = target) INTO active;
    SELECT count(*) INTO total FROM public.playlist_likes WHERE playlist_id = target;
  ELSIF kind = 'PIN_LIKE' THEN
    IF NOT (internal.public_pin(target)) THEN PERFORM internal.fail('PT404', 'SOCIAL_TARGET_NOT_FOUND'); END IF;
    IF desired THEN
      INSERT INTO public.pin_likes(user_id, pin_id) VALUES (caller, target) ON CONFLICT DO NOTHING;
    ELSIF desired = false THEN
      DELETE FROM public.pin_likes WHERE user_id = caller AND pin_id = target;
    END IF;
    SELECT EXISTS (SELECT 1 FROM public.pin_likes WHERE user_id = caller AND pin_id = target) INTO active;
    SELECT count(*) INTO total FROM public.pin_likes WHERE pin_id = target;
  ELSIF kind = 'FAVORITE' THEN
    IF NOT (EXISTS (SELECT 1 FROM public.tracks t WHERE t.id = target)) THEN PERFORM internal.fail('PT404', 'SOCIAL_TARGET_NOT_FOUND'); END IF;
    IF desired THEN
      INSERT INTO public.favorites(user_id, track_id) VALUES (caller, target) ON CONFLICT DO NOTHING;
    ELSIF desired = false THEN
      DELETE FROM public.favorites WHERE user_id = caller AND track_id = target;
    END IF;
    SELECT EXISTS (SELECT 1 FROM public.favorites WHERE user_id = caller AND track_id = target) INTO active;
  ELSIF kind = 'SAVED_PLAYLIST' THEN
    IF NOT (EXISTS (SELECT 1 FROM public.playlists p WHERE p.id = target AND p.owner_id IS DISTINCT FROM caller AND p.visibility = 'PUBLIC' AND (NOT p.is_editorial OR p.published_at IS NOT NULL))) THEN PERFORM internal.fail('PT404', 'SOCIAL_TARGET_NOT_FOUND'); END IF;
    IF desired THEN
      INSERT INTO public.saved_playlists(user_id, playlist_id) VALUES (caller, target) ON CONFLICT DO NOTHING;
    ELSIF desired = false THEN
      DELETE FROM public.saved_playlists WHERE user_id = caller AND playlist_id = target;
    END IF;
    SELECT EXISTS (SELECT 1 FROM public.saved_playlists WHERE user_id = caller AND playlist_id = target) INTO active;
  ELSE
    PERFORM internal.fail('PT422', 'INVALID_SOCIAL_KIND');
  END IF;
  RETURN jsonb_build_object('active', active, 'count', total);
END;
$$;

CREATE FUNCTION public.list_my_collection(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  caller uuid := internal.require_user();
  kind text := $1->>'kind';
  page_limit integer := COALESCE(($1->>'limit')::integer, 20);
  page_offset integer := COALESCE(($1->>'offset')::integer, 0);
  items jsonb;
  has_more boolean;
BEGIN
  PERFORM internal.assert_pagination(page_limit, page_offset);
  IF kind IS NULL OR kind NOT IN ('FAVORITE', 'SAVED_PLAYLIST') THEN
    PERFORM internal.fail('PT422', 'INVALID_COLLECTION_KIND');
  END IF;
  WITH candidates AS (
    SELECT f.track_id AS id, f.created_at FROM public.favorites f
      WHERE kind = 'FAVORITE' AND f.user_id = caller
    UNION ALL
    SELECT s.playlist_id, s.created_at FROM public.saved_playlists s
      JOIN public.playlists p ON p.id = s.playlist_id
      WHERE kind = 'SAVED_PLAYLIST' AND s.user_id = caller AND p.owner_id IS DISTINCT FROM caller
        AND p.visibility = 'PUBLIC' AND (NOT p.is_editorial OR p.published_at IS NOT NULL)
  ), page AS (
    SELECT *, row_number() OVER (ORDER BY created_at DESC, id) AS position
    FROM (SELECT * FROM candidates ORDER BY created_at DESC, id LIMIT page_limit + 1 OFFSET page_offset) selected
  )
  SELECT COALESCE(jsonb_agg(CASE WHEN kind = 'FAVORITE' THEN internal.track_view(id)
          ELSE internal.playlist_summary(id) END ORDER BY position)
          FILTER (WHERE position <= page_limit), '[]'::jsonb),
      count(*) > page_limit INTO items, has_more FROM page;
  RETURN jsonb_build_object('items', items, 'limit', page_limit, 'offset', page_offset, 'hasMore', has_more);
END;
$$;

CREATE FUNCTION public.list_my_music_pins(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  caller uuid := internal.require_user();
  page_limit integer := COALESCE(($1->>'limit')::integer, 20);
  page_offset integer := COALESCE(($1->>'offset')::integer, 0);
  items jsonb;
  has_more boolean;
BEGIN
  PERFORM internal.assert_object($1);
  PERFORM internal.assert_pagination(page_limit, page_offset);
  WITH page AS (
    SELECT p.id, row_number() OVER (ORDER BY p.updated_at DESC, p.id) AS position
    FROM public.music_pins p WHERE p.owner_id = caller
    ORDER BY p.updated_at DESC, p.id LIMIT page_limit + 1 OFFSET page_offset
  )
  SELECT COALESCE(jsonb_agg(internal.pin_detail(id) ORDER BY position)
      FILTER (WHERE position <= page_offset::bigint + page_limit), '[]'::jsonb),
    count(*) > page_limit INTO items, has_more FROM page;
  RETURN jsonb_build_object('items', items, 'limit', page_limit, 'offset', page_offset, 'hasMore', has_more);
END;
$$;

CREATE FUNCTION public.copy_public_playlist(jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  caller uuid := internal.require_user();
  source_id uuid := ($1->>'sourcePlaylistId')::uuid;
  copy_id uuid := COALESCE(($1->>'playlistId')::uuid, gen_random_uuid());
  source public.playlists%ROWTYPE;
BEGIN
  SELECT * INTO source FROM public.playlists p WHERE p.id = source_id
    AND p.owner_id IS DISTINCT FROM caller AND p.visibility = 'PUBLIC'
    AND (NOT p.is_editorial OR p.published_at IS NOT NULL) FOR SHARE;
  IF NOT FOUND THEN PERFORM internal.fail('PT404', 'PLAYLIST_NOT_FOUND'); END IF;

  INSERT INTO public.playlists(id, owner_id, title, description, visibility, is_editorial)
    VALUES (copy_id, caller, source.title, source.description, 'PRIVATE', false)
    ON CONFLICT (id) DO NOTHING;
  IF NOT FOUND THEN PERFORM internal.fail('PT409', 'OBJECT_ALREADY_EXISTS'); END IF;
  INSERT INTO public.playlist_tracks(playlist_id, track_id, position)
    SELECT copy_id, t.track_id, t.position FROM public.playlist_tracks t WHERE t.playlist_id = source_id;
  INSERT INTO public.playlist_genres(playlist_id, genre_id)
    SELECT copy_id, g.genre_id FROM public.playlist_genres g WHERE g.playlist_id = source_id;
  INSERT INTO public.playlist_tags(playlist_id, tag_id)
    SELECT copy_id, t.tag_id FROM public.playlist_tags t WHERE t.playlist_id = source_id;
  RETURN internal.playlist_detail(copy_id);
END;
$$;

REVOKE ALL ON FUNCTION public.get_my_profile(jsonb), public.update_my_profile(jsonb),
  public.get_public_profile(jsonb), public.list_public_profile_items(jsonb),
  public.social_state(jsonb), public.list_my_collection(jsonb), public.list_my_music_pins(jsonb), public.copy_public_playlist(jsonb)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.get_my_profile(jsonb), public.update_my_profile(jsonb),
  public.list_my_collection(jsonb), public.list_my_music_pins(jsonb), public.copy_public_playlist(jsonb) TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_public_profile(jsonb), public.list_public_profile_items(jsonb),
  public.social_state(jsonb) TO anon, authenticated;

NOTIFY pgrst, 'reload schema';
