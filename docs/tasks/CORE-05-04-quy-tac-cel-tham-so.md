# CORE-05-04 · QT-15/SCR-1501 API quy tắc CEL và tham số

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-05-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-05-02, NEN-041 |
| Truy vết | QT-15 → SCR-1501 → `GET /api/v1/policies/packs/{id}/versions/{v}/rules` (`listPolicyRules`), `GET/PUT /api/v1/policies/packs/{id}/versions/{v}/rules/{key}` (`getPolicyRule`, `savePolicyRule`) → MOD-05 → `policy.policy_rule` → BR-15-01..BR-15-05, BR-15-13, MSG-15-01, MSG-15-02, MSG-15-03, lỗi `POLICY-422-CEL-TYPE`, `POLICY-422-GROUP-SUM`, `POLICY-409-VERSION-FROZEN` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cho người soạn chính sách (AC-03) lưu từng quy tắc CEL và tham số của phiên bản Policy Pack với kiểm kiểu đầy đủ theo khai báo biến ngữ cảnh, kiểm tổng điểm tối đa của nhóm và các ràng buộc tham số; sửa phiên bản đã đạt kiểm thử tự đưa phiên bản về nháp. Khi xong, SCR-1501 soạn công thức trên API thật và mọi lỗi biểu thức hiện đúng thông báo nghiệp vụ.

## 2. Đầu vào bắt buộc đọc

- `TL-02b-quy-trinh.md` QT-15 "Quy tắc nghiệp vụ" (dòng 1353: BR-15-01 đến BR-15-05, BR-15-13 và ràng buộc tham số), "SCR-1501. Config Studio – công thức" (dòng 1382), bảng MSG-15-01..03 (dòng 1446–1448).
- `TL-01-thiet-ke-kien-truc.md` "Ngôn ngữ biểu thức CEL và bảng quyết định DMN" (dòng 2448), "Cấu trúc gói" (dòng 2386, phần `inputs`, `definitions`, `rounding`).
- `contracts/openapi/modules/policies.yaml` các đường dẫn `/rules`, `/rules/{key}`.
- Thẻ CORE-05-01 (cách đổi `DAT_KIEM_THU` về `NHAP` trước khi sửa).
- Bản trích `extract/tl02b-may-chu.md` QT-15 (BE-Q15-02, BE-Q15-03).

## 3. Phạm vi công việc

- `savePolicyRule` (bắt buộc `If-Match`): biên dịch và kiểm kiểu biểu thức bằng `vt-cel` với môi trường khai báo từ `inputs` của gói; biến ngoài ngữ cảnh hoặc sai kiểu trả 422 `POLICY-422-CEL-TYPE` với `detail` theo MSG-15-01 (khối, vị trí lỗi) (BR-15-01).
- Kiểm tổng điểm tối đa các nhóm: 100 với luồng Đảng, 30/70 với luồng Nhà nước; sai trả `POLICY-422-GROUP-SUM` theo MSG-15-02 (BR-15-05).
- Kiểm tham số theo khai báo: cách tính B thuộc `NAM_MUC_PL3` hoặc `TRU_25_MOI_LAN` (BR-15-02), loại ngày `NGAY_LAM_VIEC` hoặc `NGAY_LICH` (BR-15-03), ngưỡng và cách đếm 30% là tham số (BR-15-04), trừ tiến độ 0,00–0,20.
- Phiên bản `DAT_KIEM_THU`: trong cùng giao dịch đổi trạng thái về `NHAP` rồi lưu, phản hồi kèm cảnh báo MSG-15-03; phiên bản từ `CHO_PHE_DUYET` trở đi trả 409 `POLICY-409-VERSION-FROZEN`.
- Gói tenant kế thừa: chỉ quy tắc `overridable` được sửa (BR-15-13).
- `listPolicyRules`, `getPolicyRule` trả `ETag`; mọi lần lưu ghi sổ cái qua outbox.

## 4. Ngoài phạm vi

- Hàm mở rộng `sum`, `ngay_lam_viec`, `min_muc` (NEN-041); DMN (CORE-05-05); tham số luồng duyệt `wf_binding` (nhóm DV-06).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/policy/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/policy/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; kiểm thử cho: biến chưa khai báo, sai kiểu kết quả, tổng nhóm 99, tham số ngoài khoảng, sửa phiên bản `DAT_KIEM_THU` về `NHAP`, sửa phiên bản `CHO_PHE_DUYET` bị 409.
- Kiểm thử hợp đồng ba thao tác khớp `policies.yaml`.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
