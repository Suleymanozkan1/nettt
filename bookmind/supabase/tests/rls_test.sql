-- Row level security and integrity tests for the BookMind schema. Run by scripts/test-supabase-sql.sh.
-- Every failed expectation raises an exception, which aborts psql with a non-zero exit code.
\set ON_ERROR_STOP on

insert into auth.users (id, email) values
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'alice@example.com'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'bob@example.com');

create function pg_temp.as_user(uid text) returns void language plpgsql as $$
begin
  perform set_config('role', 'authenticated', false);
  perform set_config('request.jwt.claim.sub', uid, false);
end $$;

-- 1. Alice creates a book without sending user_id: the server binds it to her.
do $$ begin perform pg_temp.as_user('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'); end $$;
insert into public.books (id, title, source_type, processing_status, processing_progress, chapter_count, word_count, is_demo, created_at, updated_at)
values ('book-a', 'Alice Book', 'TXT', 'READY', 100, 1, 10, false, 1, 1);
insert into public.chapters (id, book_id, title, order_index, word_count, content_hash, processing_status, processing_version, updated_at)
values ('book-a:0', 'book-a', 'Ch 1', 0, 10, 'h', 'READY', 3, 1);
insert into public.concepts (id, book_id, chapter_id, title, normalized_title, explanation, importance, difficulty, is_prerequisite, source_references, is_favorite, user_edited, created_at, updated_at)
values ('concept-a', 'book-a', 'book-a:0', 'Kavram', 'kavram', 'açıklama', 3, 3, false, '[]', false, false, 1, 1);
insert into public.learning_items (id, book_id, concept_id, item_type, prompt, expected_answer, source_references, difficulty, dedupe_key, suspended, created_at, updated_at)
values ('item-a', 'book-a', 'concept-a', 'CONCEPT', 'p', 'e', '[]', 3, 'concept:book-a:kavram', false, 1, 1);
do $$ begin
  if (select user_id from public.books where id = 'book-a') <> 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' then raise exception 'user_id default not applied'; end if;
end $$;

-- 2. Bob cannot read, change or delete Alice's rows.
do $$ begin perform pg_temp.as_user('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'); end $$;
do $$
declare n int;
begin
  select count(*) into n from public.books; if n <> 0 then raise exception 'bob can see % books', n; end if;
  select count(*) into n from public.chapters; if n <> 0 then raise exception 'bob can see chapters'; end if;
  update public.books set title = 'hacked' where id = 'book-a'; get diagnostics n = row_count; if n <> 0 then raise exception 'bob updated alice book'; end if;
  delete from public.books where id = 'book-a'; get diagnostics n = row_count; if n <> 0 then raise exception 'bob deleted alice book'; end if;
end $$;

-- 3. Bob cannot attach rows to Alice's book (composite FK) nor write rows owned by Alice (RLS check).
do $$ begin
  begin
    insert into public.chapters (id, book_id, title, order_index, word_count, content_hash, processing_status, processing_version, updated_at)
    values ('evil', 'book-a', 'x', 9, 1, 'h', 'READY', 3, 1);
    raise exception 'cross-user foreign key accepted';
  exception when foreign_key_violation then null;
  end;
  begin
    insert into public.books (id, title, source_type, processing_status, processing_progress, chapter_count, word_count, is_demo, created_at, updated_at, user_id)
    values ('book-evil', 'x', 'TXT', 'READY', 0, 0, 0, false, 1, 1, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa');
    raise exception 'insert as another user accepted';
  exception when insufficient_privilege then null;
  end;
end $$;

-- 4. Anonymous callers see nothing.
reset role; set role anon; set request.jwt.claim.sub = '';
do $$ begin
  begin
    perform count(*) from public.books;
    raise exception 'anon can query books';
  exception when insufficient_privilege then null;
  end;
end $$;

-- 5. Integrity: server_updated_at is server-maintained; SET NULL keeps ownership; cascades remove children.
do $$ begin perform pg_temp.as_user('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'); end $$;
do $$
declare before timestamptz; after_ts timestamptz; n int;
begin
  select server_updated_at into before from public.books where id = 'book-a';
  perform pg_sleep(0.01);
  update public.books set title = 'Alice Book 2', server_updated_at = '2000-01-01' where id = 'book-a';
  select server_updated_at into after_ts from public.books where id = 'book-a';
  if after_ts < before or after_ts = '2000-01-01'::timestamptz then raise exception 'server_updated_at not maintained by the server'; end if;

  delete from public.concepts where id = 'concept-a';
  if (select concept_id from public.learning_items where id = 'item-a') is not null then raise exception 'concept_id not nulled'; end if;
  if (select user_id from public.learning_items where id = 'item-a') is null then raise exception 'SET NULL cleared user_id'; end if;

  delete from public.books where id = 'book-a';
  select count(*) into n from public.chapters; if n <> 0 then raise exception 'chapters not cascaded'; end if;
  select count(*) into n from public.learning_items; if n <> 0 then raise exception 'learning items not cascaded'; end if;
end $$;

-- 6. Profiles: an upsert without id binds to the caller.
insert into public.profiles (language, timezone, daily_goal_minutes) values ('tr', 'Europe/Istanbul', 15)
on conflict (id) do update set language = excluded.language;
do $$ begin
  if (select count(*) from public.profiles) <> 1 then raise exception 'profile not created'; end if;
end $$;

-- 7. Storage: users write only under their own folder and see only their own files.
insert into storage.objects (bucket_id, name) values ('book-files', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/book-a/source.pdf');
do $$ begin
  begin
    insert into storage.objects (bucket_id, name) values ('book-files', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/x/source.pdf');
    raise exception 'wrote into another user folder';
  exception when insufficient_privilege then null;
  end;
end $$;
do $$ begin perform pg_temp.as_user('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'); end $$;
do $$ begin
  if (select count(*) from storage.objects) <> 0 then raise exception 'bob sees alice files'; end if;
end $$;

-- 8. AI quota: clients cannot call the reservation function; the service role gets null at the limit.
do $$ begin
  begin
    perform public.ai_reserve_request('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'SUMMARY', 100);
    raise exception 'client could reserve AI quota';
  exception when insufficient_privilege then null;
  end;
end $$;
reset role;
do $$ begin
  if public.ai_reserve_request('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'SUMMARY', 2) is null then raise exception 'first reservation refused'; end if;
  if public.ai_reserve_request('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'SUMMARY', 2) is null then raise exception 'second reservation refused'; end if;
  if public.ai_reserve_request('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'SUMMARY', 2) is not null then raise exception 'quota not enforced'; end if;
end $$;

-- 9. Deleting the auth user (account deletion) removes all of their rows.
do $$ begin perform pg_temp.as_user('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'); end $$;
insert into public.books (id, title, source_type, processing_status, processing_progress, chapter_count, word_count, is_demo, created_at, updated_at)
values ('book-b', 'Bob Book', 'TXT', 'READY', 100, 0, 0, false, 1, 1);
reset role;
delete from auth.users where id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
do $$ begin
  if exists (select 1 from public.books where id = 'book-b') then raise exception 'books survived account deletion'; end if;
  if exists (select 1 from public.ai_requests where user_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb') then raise exception 'ai ledger survived account deletion'; end if;
end $$;

select 'ALL RLS TESTS PASSED' as result;
