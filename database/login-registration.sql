-- Upgrade the earlier email-profile schema to login profiles.
-- For a FRESH installation schema.sql already contains this shape.
alter table public.profiles rename column email to login;
update public.profiles set login=case when login='admin26@banketika.example' then 'admin 26' else split_part(login,'@',1) end;
alter table public.profiles add constraint profiles_login_key unique(login);
create or replace function private.create_profile() returns trigger
language plpgsql security definer set search_path='' as $$
begin
  insert into public.profiles(id,full_name,phone,login)
  values(new.id,new.raw_user_meta_data->>'full_name',new.raw_user_meta_data->>'phone',new.raw_user_meta_data->>'login');
  return new;
end;
$$;
