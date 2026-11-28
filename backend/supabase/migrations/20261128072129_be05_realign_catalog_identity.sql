-- Explicit IDs in historical catalog loads did not advance the owned sequences.
DO $migration$
DECLARE
  catalog_table text;
  owned_sequence regclass;
  largest_id bigint;
  sequence_max bigint;
  next_id numeric;
  previous_lock_timeout text := current_setting('lock_timeout');
BEGIN
  IF current_setting('transaction_isolation') <> 'read committed' THEN
    RAISE EXCEPTION 'Catalog identity repair requires read committed isolation';
  END IF;
  PERFORM set_config('lock_timeout', '5s', true);

  -- Wait for earlier writers, then keep the observed ID maxima stable until commit.
  LOCK TABLE public.genres, public.moods, public.tags IN SHARE ROW EXCLUSIVE MODE;

  FOREACH catalog_table IN ARRAY ARRAY['genres', 'moods', 'tags'] LOOP
    owned_sequence := pg_get_serial_sequence(format('public.%I', catalog_table), 'id')::regclass;
    SELECT seqmax INTO sequence_max
    FROM pg_catalog.pg_sequence
    WHERE seqrelid = owned_sequence AND seqincrement = 1 AND seqcache = 1 AND NOT seqcycle;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'Unsupported catalog sequence for public.%.id', catalog_table;
    END IF;

    EXECUTE format('SELECT max(id) FROM public.%I', catalog_table) INTO largest_id;
    EXECUTE format('SELECT last_value::numeric + is_called::int FROM %s', owned_sequence) INTO next_id;

    IF next_id <= largest_id THEN
      -- Acquire the sequence DDL lock without changing its cache or counter. Recheck
      -- under that lock in case a direct nextval ran before it was acquired.
      EXECUTE format('ALTER SEQUENCE %s CACHE 1', owned_sequence);
      EXECUTE format('SELECT last_value::numeric + is_called::int FROM %s', owned_sequence) INTO next_id;
    END IF;

    IF next_id > sequence_max OR largest_id >= sequence_max THEN
      RAISE EXCEPTION 'No ID available above the current catalog state for public.%.id', catalog_table;
    END IF;

    IF next_id <= largest_id THEN
      EXECUTE format('ALTER SEQUENCE %s RESTART WITH %s', owned_sequence, largest_id + 1);
    END IF;
  END LOOP;
  PERFORM set_config('lock_timeout', previous_lock_timeout, true);
END;
$migration$;
