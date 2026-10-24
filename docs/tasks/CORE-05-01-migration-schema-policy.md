# CORE-05-01 · MOD-05 Migration schema `policy` và trigger đóng băng phiên bản

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-05-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-00-02, NEN-033 |
| Truy vết | MOD-05 → schema `policy`: `policy.policy_pack`, `policy.policy_pack_version`, `policy.policy_rule`, `policy.decision_table`, `policy.golden_test_case`, `policy.test_run`, `policy.test_case_result`, `policy.policy_binding` → EVT-13 (qua outbox) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Tạo schema `policy` của MOD-05 bằng Flyway đúng TL-03, trong đó tính bất biến của Policy Pack được bảo đảm ngay ở tầng CSDL: phiên bản từ `DAT_KIEM_THU` trở đi bị đóng băng, phiên bản phát hành bắt buộc có chữ ký, băm và người duyệt khác người soạn, `policy_binding` của kỳ đã khoá không đổi được. Khi xong, mọi task CORE-05 khác có nền dữ liệu, và kiểm thử chứng minh không thể sửa quy tắc đã ký kể cả khi gọi SQL trực tiếp bằng tài khoản dịch vụ.

## 2. Đầu vào bắt buộc đọc

- `TL-03-thiet-ke-csdl.md` mục "Schema policy (MOD-05)" (dòng 1675), "DDL mẫu: policy.policy_pack_version" (dòng 1687) và các mục "Bảng policy.*" từ dòng 1749 đến 1863.
- `TL-01-thiet-ke-kien-truc.md` chương "Policy Pack: quy tắc chấm điểm như mã có phiên bản" (dòng 2380), mục "Phát hành, ký số và vòng đời" (dòng 2511), "Hiệu lực theo kỳ" (dòng 2517).
- Baseline mục 9.1 (trạng thái `policy_pack_version`).
- Bản trích `extract/tl03-phan1.md` mục C5.

## 3. Phạm vi công việc

- Migration `V1__policy_pack.sql`: `policy_pack` (UQ `(tenant_id, code)`, `origin` `GOC_TOAN_CUC` hoặc `TENANT`, `parent_pack_id` tự tham chiếu), `policy_pack_version` (UQ `(policy_pack_id, version)`, CHECK semver, sáu trạng thái, `golden_pass_rate` CHECK = 1 khi `DAT_KIEM_THU`, `CHO_PHE_DUYET`, `DA_PHAT_HANH`, CHECK `ck_policy_pack_version__signed`), `test_run` (CHECK `passed + failed = total`, `engine` `CEL_JAVA` hoặc `CEL_JS`); tạo FK vòng `last_test_run_id ↔ policy_pack_version_id` sau cùng (suy luận).
- Trigger `tg_policy_pack_version__freeze` gọi `policy.fn_freeze_policy_version()` đúng DDL mẫu TL-03: khi `OLD.status` thuộc `DAT_KIEM_THU`, `CHO_PHE_DUYET`, `DA_PHAT_HANH`, `NGUNG` thì chặn đổi `manifest`, `content_hash`, `version`.
- Điểm lệch cần lưu ý: TL-02b cho sửa phiên bản `DAT_KIEM_THU` (sửa xong về `NHAP`, MSG-15-03), còn trigger TL-03 đóng băng từ `DAT_KIEM_THU`. Hai nguồn khớp được nếu ứng dụng đổi trạng thái về `NHAP` trước (chỉ đổi cột `status`, trigger cho qua) rồi mới sửa nội dung trong cùng giao dịch; giữ nguyên DDL TL-03, ghi cách làm vào `wiki/14` để CORE-05-04 áp dụng.
- Migration `V2__policy_rules.sql`: `policy_rule` (UQ `(policy_pack_version_id, rule_key)`, `overridable`), `decision_table` (`hit_policy` ba giá trị, `dmn_hash`), trigger chặn UPDATE và DELETE khi phiên bản cha đã đóng băng; `golden_test_case` (UQ `(policy_pack_id, case_code)`, trạng thái `NHAP`, `DA_DUYET`, `NGUNG`), `test_case_result` (PK ghép), `policy_binding` (UQ `NULLS NOT DISTINCT (tenant_id, period_id, flow, subject_group_id, org_unit_id)`, trigger chặn đổi phiên bản khi `is_frozen`).
- Ràng buộc chống chồng khoảng hiệu lực của cùng định danh gói và luồng theo TL-01 "Hiệu lực theo kỳ" nếu TL-03 có cột tương ứng; nếu không có, ghi điểm lệch vào `wiki/14` và kiểm ở tầng ứng dụng (CORE-05-03).
- Outbox, inbox, idempotency của schema; RLS mọi bảng có `tenant_id` (gói gốc toàn cục xử lý theo cách TL-03 quy định cho `origin = GOC_TOAN_CUC`); chú thích phân loại cột.
- Kiểm thử Testcontainers: sửa `policy_rule` của phiên bản `DA_PHAT_HANH` bị từ chối; phát hành thiếu chữ ký bị CHECK chặn; `approved_by = created_by` bị chặn; đổi `policy_binding` có `is_frozen` bị chặn.

## 4. Ngoài phạm vi

- Mã Java domain (CORE-05-02 trở đi); bảng `flowable.wf_template`, `wf_binding` (nhóm DV-06).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/resources/db/migration/policy/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/policy/**` (chỉ kiểm thử migration)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh với bốn kiểm thử ràng buộc ở mục 3.
- Lint đặt tên và kiểm tra chú thích phân loại cột của NEN chạy qua với schema `policy`.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
