-- BookMind AI base objects: helper functions, profile, AI usage/quota ledger, private book file storage.

create or replace function public.touch_server_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  -- Server clock only: clients use this column as their incremental pull cursor.
  new.server_updated_at := now();
  return new;
end;
$$;

-- One profile per auth user. The client upserts without an id; the default binds it to the caller.
create table public.profiles (
  id uuid primary key default auth.uid() references auth.users (id) on delete cascade,
  language text,
  timezone text,
  daily_goal_minutes integer check (daily_goal_minutes between 1 and 600),
  server_updated_at timestamptz not null default now()
);
alter table public.profiles enable row level security;
create policy profiles_select_own on public.profiles for select to authenticated using (id = auth.uid());
create policy profiles_insert_own on public.profiles for insert to authenticated with check (id = auth.uid());
create policy profiles_update_own on public.profiles for update to authenticated using (id = auth.uid()) with check (id = auth.uid());
create policy profiles_delete_own on public.profiles for delete to authenticated using (id = auth.uid());
create trigger profiles_touch before insert or update on public.profiles for each row execute function public.touch_server_updated_at();

-- AI request ledger. Written only by the `ai` Edge Function (service role); users may read their own usage.
create table public.ai_requests (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  task text not null,
  model text,
  status text not null default 'started' check (status in ('started', 'ok', 'error')),
  input_tokens integer,
  output_tokens integer,
  cost_micros bigint,
  created_at timestamptz not null default now()
);
create index ai_requests_user_day_idx on public.ai_requests (user_id, created_at);
alter table public.ai_requests enable row level security;
create policy ai_requests_select_own on public.ai_requests for select to authenticated using (user_id = auth.uid());
-- No insert/update/delete policies: clients cannot write the ledger.

-- Atomically checks the daily quota and records a started request. Returns the new row id, or null when the
-- user has reached the limit. SECURITY DEFINER so it works with the service role and for the caller only.
create or replace function public.ai_reserve_request(p_user uuid, p_task text, p_daily_limit integer)
returns bigint
language plpgsql
security definer
set search_path = ''
as $$
declare
  used integer;
  new_id bigint;
begin
  -- Serialise reservations per user so concurrent calls cannot both slip under the limit.
  perform pg_advisory_xact_lock(hashtextextended(p_user::text, 42));
  select count(*) into used
    from public.ai_requests
   where user_id = p_user and created_at >= date_trunc('day', now() at time zone 'utc') at time zone 'utc';
  if used >= p_daily_limit then
    return null;
  end if;
  insert into public.ai_requests (user_id, task) values (p_user, p_task) returning id into new_id;
  return new_id;
end;
$$;
revoke all on function public.ai_reserve_request(uuid, text, integer) from public, anon, authenticated;

-- Private bucket for imported book files: <user id>/<book id>/source.<ext>. Never public.
insert into storage.buckets (id, name, public, file_size_limit)
values ('book-files', 'book-files', false, 157286400)
on conflict (id) do nothing;

create policy book_files_select_own on storage.objects for select to authenticated
  using (bucket_id = 'book-files' and (storage.foldername(name))[1] = auth.uid()::text);
create policy book_files_insert_own on storage.objects for insert to authenticated
  with check (bucket_id = 'book-files' and (storage.foldername(name))[1] = auth.uid()::text);
create policy book_files_update_own on storage.objects for update to authenticated
  using (bucket_id = 'book-files' and (storage.foldername(name))[1] = auth.uid()::text)
  with check (bucket_id = 'book-files' and (storage.foldername(name))[1] = auth.uid()::text);
create policy book_files_delete_own on storage.objects for delete to authenticated
  using (bucket_id = 'book-files' and (storage.foldername(name))[1] = auth.uid()::text);
