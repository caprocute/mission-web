-- Mẫu SQL SAI để chứng minh luật Semgrep NEN-034 lớp 4 bắt được đặt tenant mức phiên.
-- ruleid: mission-no-session-set-app-param
SET app.tenant_id = '01927a00-0000-7000-8000-00000000000a';

-- ruleid: mission-no-session-set-app-param, mission-no-set-session
SET SESSION app.tenant_id = '01927a00-0000-7000-8000-00000000000a';

-- ruleid: mission-no-session-set-app-param
RESET app.tenant_id;

-- ruleid: mission-no-set-config-is-local-false
SELECT set_config('app.tenant_id', '01927a00-0000-7000-8000-00000000000a', false);
