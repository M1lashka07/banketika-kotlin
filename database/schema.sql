-- Apply to the NEW Supabase project only. No service key is needed by the app.
create schema if not exists private;
revoke all on schema private from public, anon;
grant usage on schema private to authenticated;

create table private.administrators (
  user_id uuid primary key references auth.users(id) on delete cascade
);
alter table private.administrators enable row level security;
revoke all on private.administrators from public, anon, authenticated;

create function private.is_admin() returns boolean
language sql stable security definer set search_path = '' as $$
  select (select auth.uid()) is not null and exists (
    select 1 from private.administrators where user_id = (select auth.uid())
  );
$$;
revoke all on function private.is_admin() from public, anon;
grant execute on function private.is_admin() to authenticated;

create function public.admin_access() returns boolean
language sql stable security invoker set search_path = '' as $$
  select private.is_admin();
$$;
revoke all on function public.admin_access() from public, anon;
grant execute on function public.admin_access() to authenticated;

create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  full_name text not null check (char_length(btrim(full_name)) between 3 and 120),
  phone text not null check (phone ~ '^\+?[0-9 ()-]{10,25}$'),
  email text not null,
  created_at timestamptz not null default now()
);
alter table public.profiles enable row level security;
revoke all on public.profiles from public, anon, authenticated;
grant select on public.profiles to authenticated;
create policy profile_read on public.profiles for select to authenticated
using (id = (select auth.uid()) or (select private.is_admin()));

-- Only Auth's insert trigger creates profiles. Metadata supplies contact data,
-- never authorization. auth.users.email is authoritative.
create function private.create_profile() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles(id, full_name, phone, email)
  values(new.id, new.raw_user_meta_data->>'full_name', new.raw_user_meta_data->>'phone', new.email);
  return new;
end;
$$;
revoke all on function private.create_profile() from public, anon, authenticated;
create trigger banketika_auth_profile after insert on auth.users
for each row execute function private.create_profile();

create table public.banquets (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references public.profiles(id) on delete cascade,
  title text not null check (char_length(btrim(title)) between 2 and 120),
  venue text not null check (char_length(btrim(venue)) between 3 and 250),
  event_at timestamptz not null,
  payment_method text not null check (payment_method in ('cash', 'card')),
  status text not null default 'pending' check (status in ('pending', 'active', 'completed')),
  created_at timestamptz not null default now()
);
create index banquets_owner_created_idx on public.banquets(owner_id, created_at desc);
alter table public.banquets enable row level security;
revoke all on public.banquets from public, anon, authenticated;
grant select, delete on public.banquets to authenticated;
-- Explicitly supplied status, IDs, timestamps and owner changes are forbidden.
grant insert(owner_id,title,venue,event_at,payment_method) on public.banquets to authenticated;
grant update(status) on public.banquets to authenticated;
create policy banquet_read on public.banquets for select to authenticated
using (owner_id = (select auth.uid()) or (select private.is_admin()));
create policy banquet_create on public.banquets for insert to authenticated
with check ((owner_id = (select auth.uid()) or (select private.is_admin())) and status = 'pending');
create policy banquet_delete on public.banquets for delete to authenticated
using (owner_id = (select auth.uid()) or (select private.is_admin()));
create policy banquet_status_admin on public.banquets for update to authenticated
using ((select private.is_admin())) with check ((select private.is_admin()));

create function private.future_banquet() returns trigger
language plpgsql security invoker set search_path = '' as $$
begin
  if new.event_at <= now() then
    raise exception 'Banquet must be in the future' using errcode = '23514';
  end if;
  return new;
end;
$$;
revoke all on function private.future_banquet() from public, anon, authenticated;
create trigger banketika_future_banquet before insert on public.banquets
for each row execute function private.future_banquet();
