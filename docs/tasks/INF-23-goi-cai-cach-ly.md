# INF-23 · OPS-12, SEC-45, SEC-46 Gói cài cách ly LTS: đóng gói Helm ngoại tuyến, Harbor nội bộ, kiểm chữ ký

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-23` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 |
| Luồng đề xuất | B8 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | INF-02, INF-07 |
| Truy vết | TL-04 mục 2.4, mục 9.9 "Cài đặt cách ly cho lực lượng vũ trang và Công an", mục 11 "Phát hành Policy Pack, UI Pack và bản LTS" → OPS-12 → SEC-45, SEC-46, SEC-38 → ADR-06 (`crypto.provider: bcy`) → TL-05 "Bản cài cách ly" |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Có pipeline đóng gói bản LTS cho mô hình cách ly (mô hình 3) và bộ công cụ cài đặt phía khách hàng đúng TL-04 mục 9.9: gói ngoại tuyến chứa ảnh theo digest dạng OCI, chart, chữ ký, SBOM, provenance, Git bundle của kho GitOps, cơ sở dữ liệu Trivy và ClamAV, công cụ và tài liệu, kèm bản kê băm có ký. Khi xong, một gói thử tạo ra từ bản phát hành nạp được vào Harbor nội bộ của một cụm kind không có mạng ra ngoài, Kyverno chỉ cho chạy ảnh ký bằng khoá phát hành, và bộ kiểm tra sau cài đặt chạy đạt.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` "Mô hình 3: cài cách ly cho lực lượng vũ trang, Công an" (127–131), "Cài đặt cách ly cho lực lượng vũ trang và Công an" (3208–3251, script đóng gói và kiểm tra), "Phát hành Policy Pack, UI Pack và bản LTS" (3826–3833).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-05-dinh-co-he-thong.md` "Lựa chọn hồ sơ cho bản cài đơn cell" (634–646), "Ngoại lệ và biện pháp bù ở hồ sơ compact" (647–656), "Bản cài cách ly" (657–659).
- `values-airgap.yaml` (INF-02), chính sách Kyverno (INF-07), pipeline ký ảnh (INF-05).

## 3. Phạm vi công việc

- Viết tệp kê ảnh của bản phát hành `deploy/release/<phiên-bản>/images.yaml` sinh tự động từ `helm template` với `values-airgap.yaml` (mọi ảnh theo digest), kiểm tra không còn ảnh tham chiếu theo thẻ.
- Viết script đóng gói `infra/airgap/dong-goi.sh` theo trình tự TL-04: `oras copy --to-oci-layout`, `cosign save` (chữ ký, SBOM, provenance), `helm package`, `git bundle create`, tải cơ sở dữ liệu Trivy và tệp `.cvd` ClamAV, sinh `MANIFEST.sha256`, ký bằng `cosign sign-blob` với khoá phát hành ngoại tuyến (tham chiếu `hsm://release-offline`, không có khoá trong repo), nén `tar --zstd`.
- Viết bộ công cụ phía khách hàng trong gói: `cosign verify-blob` với khoá công khai phát hành, `sha256sum -c`, `tools/load-images.sh` nạp ảnh vào Harbor nội bộ theo quy ước tên kho của khách hàng, cài chart từ thư mục cục bộ, đổi khoá kiểm của Kyverno sang khoá công khai phát hành.
- Bảo đảm `values-airgap.yaml` tắt VNeID, MySign, LGSP, ứng dụng di động công khai, `egress.allowInternet: false`, `crypto.provider: bcy` (lớp nhà cung cấp trong `vt-crypto`), AI tắt mặc định; ngăn xếp quan sát chọn phương án Apache-2.0 khi chưa có duyệt pháp chế cho thành phần AGPL.
- Viết bộ kiểm tra sau cài đặt `infra/airgap/kiem-tra-sau-cai.sh`: kube-bench, Hubble không có luồng ra ngoài, kiểm chữ ký mọi ảnh đang chạy, kiểm thử khói, PERF-01 rút gọn, diễn tập khôi phục một cụm PostgreSQL; xuất biên bản theo mẫu.
- Viết quy trình bản LTS (OPS-12): chọn bản đã chạy ổn định ≥ 1 quý ở SaaS, nhánh LTS, hỗ trợ vá an ninh 18 tháng, gói cập nhật cơ sở dữ liệu lỗ hổng hằng tuần.
- Gợi ý tách: (a) kê ảnh và đóng gói; (b) công cụ phía khách hàng và Kyverno; (c) kiểm tra sau cài đặt và quy trình LTS.

## 4. Ngoài phạm vi

- Hiện thực thuật toán mật mã Ban Cơ yếu trong `vt-crypto` (task NEN; cần thư viện và thiết bị do Ban Cơ yếu cấp).
- Trạm kiểm định một chiều và hạ tầng của khách hàng (bên ngoài).

## 5. Tệp được phép sửa

- `infra/airgap/**`, `deploy/release/**` (suy luận về vị trí), `deploy/charts/vt-platform/values-airgap.yaml`, `deploy/policies/**` phần khoá kiểm cho bản cách ly.
- `ci/vt-pipeline/**` (bước đóng gói LTS), `docs/quan-tri-cai-dat/cach-ly.md` (nếu VH-06 chưa tạo thư mục thì tạo), `wiki/12-ha-tang-dev-va-trien-khai.md`.

## 6. Tiêu chí hoàn thành

- `shellcheck` sạch; script đóng gói chạy trên bản phát hành thử, gói tạo ra có `MANIFEST.sha256` và chữ ký kiểm được bằng khoá công khai thử (khoá thử tạo lúc chạy, không commit).
- Sửa một byte trong gói thì `sha256sum -c` báo lỗi và quy trình nạp dừng.
- Trên kind tắt mạng ra ngoài (chặn egress của node): nạp gói vào registry hoặc Harbor nội bộ, cài `vt-platform` bản thu nhỏ với `values-airgap.yaml`, mọi pod chạy bằng ảnh nội bộ; ảnh không ký bằng khoá phát hành bị Kyverno từ chối.
- `kiem-tra-sau-cai.sh` chạy hết và sinh biên bản (ghi rõ mục nào không chạy được trên kind).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
