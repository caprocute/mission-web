-- CORE-01-13 · QT-17, BR-17-08: dấu vết nội dung chèn từ gợi ý AI. Mỗi lần lưu mục tiêu hoặc KR có
-- nguồn AI ghi một dòng cho mỗi đối tượng: gợi ý đã dùng, danh sách trường giữ nguyên và trường đã
-- sửa so với đầu ra của gợi ý (ai.suggestion_log.output_text). Lịch sử mục tiêu (SCR-1701 "Lịch sử
-- nháp") và payload EVT-01 đọc từ bảng này để người duyệt và sổ cái truy được nguồn gốc nội dung.
-- Bảng bổ sung ngoài TL-03 (suy luận), không phân vùng, chỉ thêm; AI (ai_readonly) không ghi bảng
-- này, chỉ mission-core ghi dưới danh tính người soạn.

CREATE TABLE ${flyway:defaultSchema}.ai_content_trace (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version integer NOT NULL DEFAULT 0,
    objective_id uuid NOT NULL,
    version_no integer NOT NULL,
    key_result_id uuid,
    suggestion_id uuid NOT NULL,
    content_source varchar(12) NOT NULL,
    kept_fields varchar(32)[] NOT NULL DEFAULT '{}',
    edited_fields varchar(32)[] NOT NULL DEFAULT '{}',
    CONSTRAINT pk_ai_content_trace PRIMARY KEY (id),
    CONSTRAINT ck_ai_content_trace__content_source CHECK (content_source IN ('AI_ACCEPTED', 'AI_EDITED'))
);
CREATE INDEX ix_ai_content_trace__objective ON ${flyway:defaultSchema}.ai_content_trace (tenant_id, objective_id, created_at);
COMMENT ON TABLE ${flyway:defaultSchema}.ai_content_trace IS 'Dấu vết nội dung chèn từ gợi ý AI theo từng lần lưu: trường giữ nguyên, trường đã sửa (CORE-01-13, BR-17-08, suy luận)';
COMMENT ON COLUMN ${flyway:defaultSchema}.ai_content_trace.id IS '{"cls": "C1", "desc": "Mã dòng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ai_content_trace.tenant_id IS '{"cls": "C1", "desc": "Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ai_content_trace.created_at IS '{"cls": "C1", "desc": "Thời điểm lưu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ai_content_trace.created_by IS '{"cls": "C1", "desc": "Người soạn đã chèn gợi ý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ai_content_trace.updated_at IS '{"cls": "C1", "desc": "Thời điểm cập nhật gần nhất (dòng chỉ thêm)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ai_content_trace.updated_by IS '{"cls": "C1", "desc": "Người cập nhật gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ai_content_trace.row_version IS '{"cls": "C1", "desc": "Khoá lạc quan"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ai_content_trace.objective_id IS '{"cls": "C1", "desc": "Mục tiêu (tham chiếu logic okr.objective)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ai_content_trace.version_no IS '{"cls": "C1", "desc": "Phiên bản mục tiêu lúc lưu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ai_content_trace.key_result_id IS '{"cls": "C1", "desc": "KR khi dấu vết thuộc KR, rỗng khi thuộc mục tiêu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ai_content_trace.suggestion_id IS '{"cls": "C1", "desc": "Gợi ý đã dùng (tham chiếu logic ai.suggestion_log)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ai_content_trace.content_source IS '{"cls": "C1", "desc": "AI_ACCEPTED khi giữ nguyên mọi trường so sánh được, AI_EDITED khi đã sửa"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ai_content_trace.kept_fields IS '{"cls": "C1", "desc": "Trường giữ nguyên so với gợi ý", "type_exception": "Danh sách tên trường ngắn, chỉ thêm, đọc cả mảng; không cần bảng con"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ai_content_trace.edited_fields IS '{"cls": "C1", "desc": "Trường đã sửa so với gợi ý", "type_exception": "Danh sách tên trường ngắn, chỉ thêm, đọc cả mảng; không cần bảng con"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.ai_content_trace');
