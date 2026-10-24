# CORE-05-09 · QT-15/SCR-1503 Trình và phê duyệt Policy Pack có ký số

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-05-09` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-05-07, DV-10-01 |
| Truy vết | QT-15 → SCR-1503 → `POST /api/v1/policies/packs/{id}/versions/{v}/submit` (`submitPolicyPackVersion`), `POST .../approve` (`approvePolicyPackVersion`), `POST .../reject` (`rejectPolicyPackVersion`) → MOD-05, SVC-10, SVC-09 → `policy.policy_pack_version` (`content_hash`, `signature`, `signer_cert_ref`, `signed_at`, `tsa_token`, `approved_by/at`) → BR-15-07, BR-15-09, BR-15-10, BR-15-14, lỗi `POLICY-409-NOT-TESTED`, `POLICY-403-SAME-AUTHOR`, `POLICY-409-NOT-PENDING`, `POLICY-409-NOT-SIGNED` → EVT-21 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực bước trình và phê duyệt của QT-15: người soạn gửi phiên bản đã đạt kiểm thử kèm kỳ hiệu lực tương lai và phạm vi; người phê duyệt (AC-08) khác người soạn xem khác biệt, xác thực bước lên và ký số CAdES trên băm nội dung; phiên bản được ký thì chuyển sang saga phát hành (CORE-05-10). Khi xong, không có đường nào đưa một phiên bản chưa đạt kiểm thử hay do chính người soạn duyệt tới trạng thái phát hành.

## 2. Đầu vào bắt buộc đọc

- `TL-02b-quy-trinh.md` QT-15 "Mô tả các bước" (dòng 1315), "Quy tắc nghiệp vụ" (BR-15-07, BR-15-09, BR-15-10, BR-15-14, dòng 1353), "Ngoại lệ và xử lý" (dòng 1372), "SCR-1503" (dòng 1495).
- `TL-01-thiet-ke-kien-truc.md` "Phát hành, ký số và vòng đời" (dòng 2511: ký CAdES tách rời trên SHA-256 của gói, luồng duyệt Flowable tầng 2).
- `contracts/openapi/modules/policies.yaml` `/submit`, `/approve`, `/reject`, schema `PolicyPackSubmit`.
- Thẻ DV-10-01 (`SignatureProvider`).
- Bản trích `extract/tl02b-may-chu.md` QT-15 (BE-Q15-08).

## 3. Phạm vi công việc

- Tính `content_hash` SHA-256 trên dạng chuẩn hoá (JSON RFC 8785) của toàn bộ quy tắc, bảng quyết định, tham số, tham số luồng duyệt; tính lại được và ổn định giữa các lần chạy.
- `submitPolicyPackVersion` (Idempotency-Key, `If-Match`): chỉ từ `DAT_KIEM_THU` với `golden_pass_rate = 1` và lần chạy gần nhất khớp hai engine (`POLICY-409-NOT-TESTED`, BR-15-07); kỳ hiệu lực phải ở tương lai và chưa khoá (BR-15-10, kiểm qua `PeriodApi`); chuyển `CHO_PHE_DUYET`, khởi động mẫu duyệt phát hành ở SVC-06 (tầng 2), phát EVT-21 cho người phê duyệt.
- `approvePolicyPackVersion`: OPA kiểm vai trò AC-08 và khác người soạn (`POLICY-403-SAME-AUTHOR`, BR-15-09); step-up AAL2; ký qua SVC-10 trên `content_hash`, lưu `signature`, `signer_cert_ref`, `signed_at`, `tsa_token`, `approved_by/at`; ghi sổ cái qua outbox (BR-15-14); sau đó gọi điểm khởi chạy saga của CORE-05-10 và trả 202 job `POLICY_PUBLISH`. Ký lỗi thì giữ `CHO_PHE_DUYET` (`POLICY-409-NOT-SIGNED`).
- `rejectPolicyPackVersion`: chỉ khi `CHO_PHE_DUYET` (`POLICY-409-NOT-PENDING`), ý kiến bắt buộc, về `NHAP`.
- Kiểm thử bốn mắt ở cả OPA và CHECK CSDL `ck_policy_pack_version__signed`.

## 4. Ngoài phạm vi

- Saga phát hành, `policy_binding`, EVT-13 (CORE-05-10); luồng duyệt chi tiết trong Flowable (nhóm DV-06).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/policy/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/policy/**`
- `deploy/policies/**` (quy tắc OPA người duyệt khác người soạn nếu NEN-040 chưa phủ)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; kiểm thử tích hợp Testcontainers (PostgreSQL, Kafka, Keycloak, SVC-10 giả lập) cho: gửi khi chưa đạt kiểm thử bị 409, kỳ hiệu lực đã khoá bị chặn, tự duyệt bị 403, thiếu step-up bị chặn, ký xong có đủ cột chữ ký.
- `content_hash` giống nhau khi tính hai lần trên cùng nội dung xáo thứ tự khoá JSON.
- `opa test` xanh nếu sửa chính sách; kiểm thử hợp đồng ba thao tác khớp `policies.yaml`.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
