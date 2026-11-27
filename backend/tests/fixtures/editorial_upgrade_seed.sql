-- Installs controlled rows after migration 18 in the disposable upgrade database.
BEGIN;
CREATE SCHEMA editorial_upgrade_qa;
REVOKE ALL ON SCHEMA editorial_upgrade_qa FROM PUBLIC,anon,authenticated;
CREATE TABLE editorial_upgrade_qa.baseline AS SELECT gen_random_uuid() AS place_id,gen_random_uuid() AS playlist_id,title,country
FROM (VALUES ('Токио','JP'),('Сеул','KR'),('Бангкок','TH'),('Нью-Дели','IN'),('Джакарта','ID'),('Лондон','GB'),('Париж','FR'),('Берлин','DE'),('Рим','IT'),('Мадрид','ES'),('Стокгольм','SE'),('Москва','RU'),('Каир','EG'),('Найроби','KE'),('Вашингтон','US'),('Мехико','MX'),('Бразилиа','BR'),('Буэнос-Айрес','AR'),('Канберра','AU'),('Веллингтон','NZ')) v(title,country);
INSERT INTO auth.users(id) VALUES ('de7c0501-2486-4d3a-8fdb-2a6c81d6d343');
UPDATE public.profiles SET role_code='EDITOR' WHERE id='de7c0501-2486-4d3a-8fdb-2a6c81d6d343';
INSERT INTO public.places(id,title,country_code,geom,is_published)
SELECT place_id,title,country,extensions.ST_GeogFromText('POINT(1 1)'),true FROM editorial_upgrade_qa.baseline;
INSERT INTO storage.objects(bucket_id,name,metadata)
SELECT 'soundscape-media','editorial/capitals/v1/'||lower(country)||'-qa.png','{"size":100,"mimetype":"image/png"}'
FROM editorial_upgrade_qa.baseline;
INSERT INTO public.playlists(id,owner_id,place_id,title,description,cover_path,visibility,is_editorial)
SELECT playlist_id,'de7c0501-2486-4d3a-8fdb-2a6c81d6d343',place_id,title||' QA','Upgrade legacy description',
  'editorial/capitals/v1/'||lower(country)||'-qa.png','PRIVATE',true FROM editorial_upgrade_qa.baseline;
INSERT INTO public.playlists(id,owner_id,title,visibility)
VALUES ('de7c0501-2486-4d3a-8fdb-2a6c81d6d344','de7c0501-2486-4d3a-8fdb-2a6c81d6d343','BE-03-FIX QA Personal','PRIVATE');
CREATE TABLE editorial_upgrade_qa.positions AS
SELECT playlist_id,gen_random_uuid() AS track_id,n AS position FROM editorial_upgrade_qa.baseline,generate_series(1,13) n;
INSERT INTO public.tracks(id,title,duration_ms)
SELECT track_id,'BE-03-FIX QA Track '||position,180000 FROM editorial_upgrade_qa.positions;
INSERT INTO public.playlist_tracks(playlist_id,track_id,position) SELECT * FROM editorial_upgrade_qa.positions;
INSERT INTO public.track_sources(track_id,provider,provider_track_id,external_url,verified_at)
SELECT track_id,'BANDCAMP',track_id::text,'https://be03-fixture.bandcamp.com/track/qa-'||track_id,now()
FROM editorial_upgrade_qa.positions;
INSERT INTO public.genres(id,name) VALUES(32198,'BE-03-FIX QA Legacy Genre');
INSERT INTO public.tags(id,name) VALUES(32198,'BE-03-FIX QA Legacy Tag');
INSERT INTO public.playlist_genres SELECT playlist_id,32198 FROM editorial_upgrade_qa.baseline;
INSERT INTO public.playlist_tags SELECT playlist_id,32198 FROM editorial_upgrade_qa.baseline;
COMMIT;
