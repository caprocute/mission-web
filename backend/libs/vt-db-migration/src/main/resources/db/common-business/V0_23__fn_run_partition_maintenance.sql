-- NEN-032 · TL-03 L4259 (dọn outbox), L877 (idempotency 48 giờ), L4562 (bảng nhật ký theo ngày).
-- Bảo trì phân vùng pg_partman của riêng schema: tạo trước phân vùng ngày mới, gỡ phân vùng hết hạn,
-- rồi bảo vệ hai lớp phân vùng mới (fn_protect_partitions). SECURITY DEFINER nên phân vùng do
-- mig_<schema> sở hữu; svc_<schema> có EXECUTE để ứng dụng hoặc CronJob gọi định kỳ (lịch chạy
-- ngoài phạm vi NEN-032). Không dùng partman.run_maintenance_proc() toàn CSDL vì thủ tục đó chạy
-- bằng quyền bên gọi trên mọi schema, và phân vùng nó tạo không có RLS lớp thứ hai (suy luận).
-- Trả số bảng cha đã bảo trì.
CREATE FUNCTION ${flyway:defaultSchema}.fn_run_partition_maintenance()
    RETURNS int
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
    v_parent text;
    v_count  int := 0;
BEGIN
    FOR v_parent IN
        SELECT parent_table FROM partman.part_config
         WHERE split_part(parent_table, '.', 1) = '${flyway:defaultSchema}'
         ORDER BY parent_table
    LOOP
        PERFORM partman.run_maintenance(p_parent_table => v_parent, p_analyze => false, p_jobmon => false);
        PERFORM ${flyway:defaultSchema}.fn_protect_partitions(v_parent::regclass);
        v_count := v_count + 1;
    END LOOP;
    RETURN v_count;
END
$fn$;
REVOKE EXECUTE ON FUNCTION ${flyway:defaultSchema}.fn_run_partition_maintenance() FROM PUBLIC;
