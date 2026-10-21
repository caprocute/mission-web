-- Mẫu SQL ĐÚNG quy ước NEN-034 lớp 4.
SET LOCAL app.tenant_id = '01927a00-0000-7000-8000-00000000000a';
SELECT set_config('app.tenant_id', '01927a00-0000-7000-8000-00000000000a', true);
ALTER DATABASE td_pool SET app.tenancy_model = 'pool';
