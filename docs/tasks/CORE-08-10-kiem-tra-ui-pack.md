# CORE-08-10 · QT-18 Kiểm tra tự động UI Pack khi gửi duyệt

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-08-10` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-08-08; đối chiếu với công cụ kiểm tương phản của WEB-004 (bổ sung so với khung) |
| Truy vết | QT-18 → SCR-1801 → `POST /api/v1/ui/packs/{id}/versions/{v}/submit` (job `TEST_RUN`) → MOD-08 → `config.ui_pack_version` → BR-18-01, BR-18-08, BR-18-09, MSG-18-01, MSG-18-05, lỗi `UI-422-CONTRAST` → NFR-07, NFR-11 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực bộ kiểm tra tĩnh chạy khi gửi duyệt UI Pack (tầng 2 của kiểm thử hồi quy theo tenant, TL-01): tương phản WCAG 2.1 AA ≥ 4,5 : 1 cho mọi cặp token chữ và nền, ≥ 7 : 1 ở chế độ cao tuổi, gói ≤ 30 KB gzip, tài sản an toàn (SVG làm sạch không script, logo ≤ 50 KB), giới hạn cỡ chữ và bo góc, chế độ cao tuổi đạt chữ ≥ 17 px và vùng chạm ≥ 48 px. Khi xong, phiên bản vi phạm bị trả về `NHAP` với danh sách lỗi đọc được, và kết quả kiểm được lưu dạng `UiPackCheck` để Config Studio hiển thị.

## 2. Đầu vào bắt buộc đọc

- `TL-02b-quy-trinh.md` QT-18 "Quy tắc nghiệp vụ" (dòng 2008: BR-18-01, BR-18-08, BR-18-09 và các ràng buộc logo, cỡ chữ, bo góc), SCR-1801 (dòng 2035), bảng MSG-18-01, MSG-18-05 (dòng 2099–2103).
- `TL-01-thiet-ke-kien-truc.md` G1 mục "Kiểm thử hồi quy giao diện theo tenant" (dòng 1795) và mục ngân sách hiệu năng của G1; ADR-25 (dòng 3632).
- `contracts/openapi/modules/config.yaml` schema `UiPackCheck`, mô tả `submitUiPackVersion`.
- `docs/tasks/WEB-004-bien-dich-token.md` (công cụ kiểm tương phản phía Node, để dùng chung bộ ca).
- Bản trích `extract/tl02b-may-chu.md` QT-18 (BE-Q18-03), `extract/tl01-xuyen-suot.md` X-G1-04.

## 3. Phạm vi công việc

- Kiểm tương phản: suy ra mọi cặp token chữ và nền theo quy ước đặt tên token (ghi quy ước vào wiki), tính tỷ lệ theo công thức WCAG 2.1, ngưỡng 4,5 : 1 ở chế độ thường và 7 : 1 sau khi áp `modes.elderly`; vi phạm trả MSG-18-01 với tên màu và tỷ lệ.
- Kiểm kích thước: đóng gói nội dung phiên bản như khi phát hành, nén gzip, so với 30 KB (MSG-18-05); ghi `bundleSizeGzipBytes`.
- Kiểm tài sản: logo SVG hoặc PNG ≤ 50 KB, SVG qua bộ làm sạch danh sách cho phép (bỏ `script`, thuộc tính `on*`, `href` ngoài, `foreignObject`), từ chối nếu sau làm sạch khác bản gốc; băm `sha256` khớp khai báo.
- Kiểm giới hạn token: cỡ chữ cơ sở 12–16 px, bo góc 0–16 px; chế độ cao tuổi chữ ≥ 17 px, `minTouchTarget` ≥ 48 px (BR-18-09).
- Kiểm tham chiếu biểu mẫu: mọi `schemaRef` giải được trong dải `requires.policyPack` và mọi trường ở `ui.sections` có trong schema (ADR-25). Vì `config` là module gốc, khai báo cổng `config.api.PolicySchemaPort` để MOD-05 hiện thực (đảo phụ thuộc); bản hiện thực thật làm ở CORE-05-03 hoặc task kế tiếp, ở đây dùng bản giả lập trong kiểm thử.
- Ghép các kiểm tra trên cùng `LayoutValidator` (CORE-08-09) và bộ test biểu mẫu (CORE-08-14) thành `UiPackChecker`, chạy trong job `TEST_RUN`, lưu kết quả từng mục kèm mã (`CONTRAST`, `SIZE`…).
- Dùng chung tệp ca kiểm tương phản với công cụ của WEB-004 để hai bên cho cùng kết quả.

## 4. Ngoài phạm vi

- Smoke test dựng trên trình duyệt không giao diện, axe-core và đo 50 trường ≤ 50 ms (kiểm thử phía web, WEB-005 và hạng mục WI-OPS-10).
- Biên dịch token ra CSS (CORE-08-11).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/config/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/config/**`
- `data/golden/ui-pack-checks/**` (ca kiểm thử dùng chung với WEB-004)
- `wiki/03-giao-dien-theo-tenant.md` (quy ước cặp token chữ và nền)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; bộ ca kiểm thử có ít nhất: tương phản 4,4 : 1 bị chặn, 4,5 : 1 qua, chế độ cao tuổi 6,9 : 1 bị chặn, gói 31 KB bị chặn, SVG có `onload` bị chặn, logo 51 KB bị chặn.
- Job `TEST_RUN` với gói mẫu 20 widget hoàn tất dưới 1 phút trên máy kiểm thử.
- Kết quả tương phản khớp công cụ WEB-004 trên cùng tệp ca (nếu WEB-004 đã có).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật.

## 7. Báo cáo

Theo AGENTS.md mục 6.
