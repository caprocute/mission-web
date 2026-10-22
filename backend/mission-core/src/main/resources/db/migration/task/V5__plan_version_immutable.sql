-- CORE-02-09: phiên bản kế hoạch đã duyệt bất biến (BR-04-09, baseline 9.1).
-- Sau khi phiên bản sang DA_DUYET, chỉ được bỏ cờ hiện hành khi phiên bản sau được duyệt (is_current cùng cột
-- người, thời điểm cập nhật và row_version); mọi thay đổi khác, việc xoá phiên bản và mọi thay đổi dòng kế hoạch
-- của phiên bản đó bị chặn ở CSDL, kể cả khi lỗi ứng dụng bỏ qua kiểm tra.

CREATE FUNCTION ${flyway:defaultSchema}.fn_freeze_approved_plan_version() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $fn$
BEGIN
  IF OLD.status = 'DA_DUYET' THEN
    IF TG_OP = 'DELETE' THEN
      RAISE EXCEPTION 'Phiên bản kế hoạch % đã duyệt, không được xoá', OLD.id
        USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['is_current', 'updated_at', 'updated_by', 'row_version'])
       IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['is_current', 'updated_at', 'updated_by', 'row_version']) THEN
      RAISE EXCEPTION 'Phiên bản kế hoạch % đã duyệt, nội dung bất biến', OLD.id
        USING ERRCODE = 'integrity_constraint_violation';
    END IF;
  END IF;
  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END
$fn$;
CREATE TRIGGER tg_plan_version__freeze BEFORE UPDATE OR DELETE ON ${flyway:defaultSchema}.plan_version
  FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_freeze_approved_plan_version();

CREATE FUNCTION ${flyway:defaultSchema}.fn_freeze_approved_plan_item() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
  r record;
BEGIN
  IF TG_OP = 'INSERT' THEN
    r := NEW;
  ELSE
    r := OLD;
  END IF;
  IF EXISTS (SELECT 1 FROM ${flyway:defaultSchema}.plan_version v
             WHERE v.period_bucket = r.period_bucket AND v.tenant_id = r.tenant_id
               AND v.id = r.plan_version_id AND v.status = 'DA_DUYET')
     OR (TG_OP = 'UPDATE' AND EXISTS (SELECT 1 FROM ${flyway:defaultSchema}.plan_version v
             WHERE v.period_bucket = NEW.period_bucket AND v.tenant_id = NEW.tenant_id
               AND v.id = NEW.plan_version_id AND v.status = 'DA_DUYET')) THEN
    RAISE EXCEPTION 'Dòng kế hoạch thuộc phiên bản đã duyệt, không được thay đổi'
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END
$fn$;
CREATE TRIGGER tg_plan_item__freeze BEFORE INSERT OR UPDATE OR DELETE ON ${flyway:defaultSchema}.plan_item
  FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_freeze_approved_plan_item();
