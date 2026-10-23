# OPS-11 · Phát hành UI Pack trong một ngày

> **Truy vết:** TL-04 mục 11.6 · TL-01 chương 7 · ADR-05 · NFR-11 · MOD-08 (`createUiPackVersion`, `updateUiPackVersion`, `submitUiPackVersion`, `publishUiPackVersion`) · EVT-23 · INF-06 `ci/vt-pipeline/vars/vtUiPackPipeline.groovy` · DL-06.
> **Chu kỳ:** theo yêu cầu · **Chịu trách nhiệm:** đội triển khai · **Tự động hoá:** pipeline UI Pack.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes). Gói mẫu để thử: `frontend/projects/fe-shell/public/ui-packs/default/1.0.0/pack.json`.

## Điều kiện kích hoạt

Tenant cần token thiết kế, lược đồ SDUI hoặc biểu mẫu riêng, hoặc gói mặc định của khối cần sửa.

## Tác động

UI Pack chỉ đổi giao diện, không đổi mã. Gói sai có thể làm hỏng khả năng tiếp cận hoặc chế độ Đảng viên cao tuổi của cả tenant, nên kiểm tự động chặn trước, và người duyệt xem ảnh chụp so sánh.

## Kiểm tra nhanh

Kết quả các bước pipeline trong artifact `.vt-ui/**` của bản build Jenkins; trạng thái phiên bản gói qua `GET /api/v1/ui/packs/{id}/versions/{v}`.

## Các bước

Một lượt pipeline dưới 60 phút:

1. Mở merge request thư mục gói (`dir`) và khai `uiPackId` trong Jenkinsfile của gói.
2. Pipeline chạy: "Lược đồ, registry, kích thước" (validator không sinh mã lúc chạy, component chỉ trong registry, CSS ≤ 30 KB gzip), "Khả năng tiếp cận (axe-core)" ở hai chế độ thường và cao tuổi, "Ảnh chụp so sánh trên staging".
3. Gộp vào main: "Ký gói", "Tải lên và gửi kiểm tra" (máy chủ chạy lại bộ kiểm của MOD-08: schema, registry, tương phản AA, ngân sách CSS).
4. Người duyệt (nhóm `approverGroup`, khác người soạn) xem ảnh chụp so sánh và kết quả, bấm "Phát hành" có ý kiến.
5. "Phát hành" gọi `publishUiPackVersion`; máy chủ phát EVT-23 để vô hiệu cache CDN theo khoá phiên bản.

**Nếu axe-core hoặc tương phản không đạt:** sửa token màu theo bộ ca `data/golden/ui-pack-checks/tuong-phan.json` (ngưỡng 4,5 : 1, chế độ cao tuổi 7 : 1), không hạ ngưỡng.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Xem phiên bản gói | (chưa có) | `GET /api/v1/ui/packs/{id}/versions/{v}` |
| Phát hành | (chưa có) | `POST /api/v1/ui/packs/{id}/versions/{v}/publish` với `If-Match` |

## Kiểm sau khi làm

Mở trang của tenant ở chế độ thường và cao tuổi: giao diện đúng phiên bản mới, không lỗi console; tải gói không vượt 30 KB gzip.

## Quay lui

Phát hành lại phiên bản trước của gói (cùng pipeline, cùng duyệt); EVT-23 làm CDN tải lại.

## Leo thang

Giao diện tenant hỏng sau phát hành: SEV-3 (một tenant), phát hành lại phiên bản trước ngay rồi điều tra.

## Liên quan

`wiki/03-giao-dien-theo-tenant.md`, `data/golden/ui-pack-checks/README.md`, `ci/vt-pipeline/README.md`.
