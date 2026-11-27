BEGIN;
SELECT set_config('request.jwt.claim.sub','de7c0501-2486-4d3a-8fdb-2a6c81d6d343',true);
DO $$
DECLARE row record; draft jsonb; ref jsonb;
BEGIN
  IF (SELECT count(*) FROM public.playlists WHERE is_editorial AND owner_id IS NULL AND
      visibility='PRIVATE' AND published_at IS NULL)<>20 OR
    NOT EXISTS (SELECT FROM public.playlists WHERE id='de7c0501-2486-4d3a-8fdb-2a6c81d6d344'
      AND owner_id='de7c0501-2486-4d3a-8fdb-2a6c81d6d343') THEN
    RAISE EXCEPTION 'Upgrade lost official or personal control rows';
  END IF;
  FOR row IN SELECT * FROM editorial_upgrade_qa.baseline LOOP
    draft := public.save_editorial_draft(jsonb_build_object('placeId',row.place_id,'expectedRevision',0));
    ref := public.save_editorial_draft(jsonb_build_object('placeId',row.place_id,'expectedRevision',0));
    IF draft IS DISTINCT FROM ref OR draft->>'playlistId' IS DISTINCT FROM row.playlist_id::text
        OR draft->>'title' IS DISTINCT FROM row.title||' QA'
        OR draft->>'description' <> 'Upgrade legacy description'
        OR draft->>'coverPath' IS DISTINCT FROM 'editorial/capitals/v1/'||lower(row.country)||'-qa.png'
        OR draft->'trackIds' IS DISTINCT FROM (SELECT jsonb_agg(track_id ORDER BY position)
          FROM editorial_upgrade_qa.positions WHERE playlist_id=row.playlist_id)
        OR draft->'genreIds' <> '[32198]' OR draft->'tagIds' <> '[32198]'
        OR draft->>'approvedRevision' IS NOT NULL OR draft->>'isPublished'<>'false' THEN
      RAISE EXCEPTION 'Legacy adoption failed for %',row.title;
    END IF;
  END LOOP;
END;
$$;
ROLLBACK;
