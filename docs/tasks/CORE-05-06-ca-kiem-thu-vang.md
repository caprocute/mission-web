# CORE-05-06 · QT-15/SCR-1503 Ca kiểm thử vàng và ký duyệt ca

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-05-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-05-02; DV-10-01 cho ký số ca (bổ sung so với khung) |
| Truy vết | QT-15 → SCR-1503 → `GET/POST /api/v1/policies/test-cases` (`listGoldenTestCases`, `createGoldenTestCase`), `GET /api/v1/policies/test-cases/{id}` (`getGoldenTestCase`), `POST /api/v1/policies/test-cases/{id}/approve` (`approveGoldenTestCase`) → MOD-05 → `policy.golden_test_case` → BR-15-08, lỗi `POLICY-422-CASE-CODE-EXISTS` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Quản lý bộ ca mẫu vàng của từng Policy Pack: BA hoặc người soạn tạo ca (đầu vào, kết quả mong đợi, căn cứ), người có thẩm quyền phía khách hàng ký duyệt ca, và sau khi ký thì kỳ vọng của ca không ai sửa được. Khi xong, bộ vàng là đầu vào tin cậy cho chạy kiểm thử (CORE-05-07) và cho tiêu chí "đạt 100% trước mỗi lần phát hành".

## 2. Đầu vào bắt buộc đọc

- `TL-02b-quy-trinh.md` QT-15 "Quy tắc nghiệp vụ" (BR-15-08) và "SCR-1503. Chạy test case vàng" (dòng 1495).
- `TL-01-thiet-ke-kien-truc.md` "Bộ ca mẫu vàng" (dòng 2482: tệp YAML `bo_ca_mau`, ký số bởi Ban Tổ chức).
- `TL-03-thiet-ke-csdl.md` "Bảng policy.golden_test_case" (dòng 1811).
- `contracts/openapi/modules/policies.yaml` nhóm `/policies/test-cases`.
- Bản trích `extract/tl01-xuyen-suot.md` X-PP-05.

## 3. Phạm vi công việc

- `createGoldenTestCase` (Idempotency-Key): mã ca duy nhất trong gói (`POLICY-422-CASE-CODE-EXISTS`), `input` và `expected` là dữ liệu giả lập (không dữ liệu thật, không C2, C3), `legal_ref`, `source` (`KHACH_HANG`, `BA`, `VAN_BAN`), trạng thái `NHAP`.
- Kiểm `input` khớp khai báo `inputs` của phiên bản mới nhất của gói, `expected` có đủ trường đầu ra (điểm, mức).
- `approveGoldenTestCase`: người duyệt khác người tạo (OPA), step-up theo ma trận, ký số qua SVC-10 trên băm chuẩn hoá RFC 8785 của ca, ghi `approved_by`, `approved_at`, `signature`, chuyển `DA_DUYET`; ghi sổ cái qua outbox.
- Sau khi `DA_DUYET`, mọi sửa `input`, `expected` bị từ chối (BR-15-08); thay đổi phải tạo ca mới và chuyển ca cũ `NGUNG`.
- Nhập, xuất bộ ca dạng YAML `bo_ca_mau` theo TL-01 để trao đổi với khách hàng (suy luận định dạng chi tiết, ghi wiki).
- `listGoldenTestCases` lọc theo gói, trạng thái, phân trang con trỏ.

## 4. Ngoài phạm vi

- Chạy ca (CORE-05-07); ca biên tự sinh và kiểm thử thuộc tính (CORE-05-07).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/policy/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/policy/**`
- `data/golden/policy-pack/**` (bộ ca mẫu giả lập)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; kiểm thử cho: mã trùng 422, tự duyệt bị chặn, sửa ca đã duyệt bị chặn, nhập rồi xuất YAML cho nội dung tương đương.
- Kiểm thử hợp đồng bốn thao tác khớp `policies.yaml`.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
