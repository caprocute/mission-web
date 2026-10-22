-- DV-14-06 · SVC-14 ai-gateway · quyết định của người dùng với gợi ý AI và tỷ lệ chấp nhận theo ca dùng.

-- TL-03 không có cột lý do; SCR-0502 bắt buộc lý do khi bỏ nhiệm vụ trích (AI-422-REASON-REQUIRED) nên thêm cột
-- (cột bổ sung, suy luận, ghi wiki/14). Lý do đã che dữ liệu cá nhân trước khi ghi.
ALTER TABLE ai.suggestion_log ADD COLUMN action_reason varchar(1000);
ALTER TABLE ai.suggestion_log ADD COLUMN decided_at timestamptz;
COMMENT ON COLUMN ai.suggestion_log.action_reason IS '{"cls":"C1","desc":"Lý do người dùng bỏ gợi ý, đã che"}';
COMMENT ON COLUMN ai.suggestion_log.decided_at IS '{"cls":"C1","desc":"Thời điểm ghi quyết định"}';

CREATE INDEX ix_suggestion_log__week ON ai.suggestion_log (tenant_id, user_id, kind, target_ref)
    WHERE kind = 'TOM_TAT_TUAN';

-- Tỷ lệ chấp nhận theo tenant, ca dùng và ngày: chấp nhận = (CHAP_NHAN + SUA) / số gợi ý đã có quyết định
-- (TL-01 "Bốn tầng ứng dụng AI": mở rộng tầng 2 khi trên 70%). security_invoker để RLS của bảng nền áp lên view.
CREATE VIEW ai.v_suggestion_acceptance WITH (security_invoker = true) AS
SELECT tenant_id,
       use_case,
       created_day,
       count(*)                                             AS suggested,
       count(*) FILTER (WHERE user_action IS NOT NULL)      AS decided,
       count(*) FILTER (WHERE user_action = 'CHAP_NHAN')    AS accepted,
       count(*) FILTER (WHERE user_action = 'SUA')          AS edited,
       count(*) FILTER (WHERE user_action = 'BO_QUA')       AS dismissed,
       round((count(*) FILTER (WHERE user_action IN ('CHAP_NHAN', 'SUA')))::numeric
             / nullif(count(*) FILTER (WHERE user_action IS NOT NULL), 0), 4) AS acceptance_rate
  FROM ai.suggestion_log
 GROUP BY tenant_id, use_case, created_day;

GRANT SELECT ON ai.v_suggestion_acceptance TO svc_ai, ro_ai;
