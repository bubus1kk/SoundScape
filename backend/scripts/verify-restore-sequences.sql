-- Run after restoring application rows and sequence state, before opening writes.
BEGIN READ ONLY;
DO $check$
DECLARE
  table_name text;
  identity_sequence regclass;
  largest_id bigint;
  stored_value bigint;
  has_been_called boolean;
  next_id numeric;
  sequence_max bigint;
  failures text[] := ARRAY[]::text[];
BEGIN
  FOREACH table_name IN ARRAY ARRAY['genres', 'moods', 'tags'] LOOP
    identity_sequence := pg_get_serial_sequence('public.' || table_name, 'id')::regclass;
    SELECT seqmax INTO sequence_max
    FROM pg_catalog.pg_sequence
    WHERE seqrelid = identity_sequence AND seqincrement = 1 AND seqcache = 1 AND NOT seqcycle;
    IF sequence_max IS NULL THEN
      RAISE EXCEPTION 'Unsupported identity sequence for public.%.id', table_name;
    END IF;

    EXECUTE format('SELECT max(id) FROM public.%I', table_name) INTO largest_id;
    EXECUTE format('SELECT last_value, is_called FROM %s', identity_sequence)
      INTO stored_value, has_been_called;
    next_id := stored_value::numeric + has_been_called::int;
    RAISE NOTICE 'public.%.id: max(id)=%, next=%', table_name, largest_id, next_id;

    IF next_id > sequence_max OR next_id <= largest_id THEN
      failures := array_append(failures, format('public.%I.id', table_name));
    END IF;
  END LOOP;

  IF cardinality(failures) > 0 THEN
    RAISE EXCEPTION 'Restored identity state cannot safely allocate a new ID: %',
      array_to_string(failures, ', ');
  END IF;
END;
$check$;
COMMIT;
