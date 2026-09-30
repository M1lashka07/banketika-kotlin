-- One-time bootstrap for a fresh EDUCATIONAL project. Run as postgres in SQL Editor.
-- First set the password in that same transaction, without saving it in this file:
-- select set_config('banketika.admin_password', 'YOUR_PASSWORD', true);
-- For regular accounts always use Supabase Auth signup / dashboard, not SQL inserts.
do $$
declare
  admin_id uuid := gen_random_uuid();
  password_text text := current_setting('banketika.admin_password');
begin
  if length(password_text) < 6 then raise exception 'Set admin password first'; end if;
  if exists(select 1 from auth.users where email='admin26@banketika.example') then
    raise exception 'Admin account already exists; bootstrap does not reset passwords';
  end if;
  insert into auth.users(instance_id,id,aud,role,email,encrypted_password,email_confirmed_at,
    raw_app_meta_data,raw_user_meta_data,created_at,updated_at,
    confirmation_token,recovery_token,email_change_token_new,email_change)
  values('00000000-0000-0000-0000-000000000000',admin_id,'authenticated','authenticated',
    'admin26@banketika.example',extensions.crypt(password_text,extensions.gen_salt('bf')),now(),
    '{"provider":"email","providers":["email"]}',
    '{"full_name":"Администратор Банкетики","phone":"+7 (000) 000-00-00","login":"admin 26"}',now(),now(),'','','','');
  insert into auth.identities(user_id,provider_id,identity_data,provider,created_at,updated_at)
  values(admin_id,admin_id::text,jsonb_build_object('sub',admin_id::text,'email','admin26@banketika.example','email_verified',true),'email',now(),now());
  insert into private.administrators(user_id) values(admin_id);
end;
$$;
