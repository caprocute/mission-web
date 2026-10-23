# OPS-10 · Phát hành Policy Pack trong năm ngày

> **Truy vết:** TL-04 mục 11.6 · NFR-07 · tiêu chí J6 · MOD-05 · EVT-13 · INF-06 `ci/vt-pipeline/vars/vtPolicyPackPipeline.groovy`, `data/policy-pack/Jenkinsfile` · DL-01, DL-03 (`data/golden/`).
> **Chu kỳ:** theo yêu cầu · **Chịu trách nhiệm:** chủ sản phẩm, nghiệp vụ · **Tự động hoá:** pipeline Policy Pack.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes). Kiểm thử tự động của bước tĩnh chạy được ngay trên máy: `python3 -I data/policy-pack/kiem_goi.py .` và `python3 -I data/golden/test_chay_ca_mau.py`.

## Điều kiện kích hoạt

Quy định đánh giá thay đổi (văn bản mới, hướng dẫn của cơ quan có thẩm quyền) hoặc khách hàng yêu cầu điều chỉnh tham số trong miền cho phép.

## Tác động

Policy Pack không phát hành lại mã; gói mới áp cho kỳ được chỉ định (`effectivePeriodId`) và phạm vi (`scopes`), máy chủ phát EVT-13 và ghi sổ cái khi phát hành. Sai quy tắc làm sai điểm và xếp loại của cả kỳ, nên ca vàng 100% là điều kiện cứng.

## Kiểm tra nhanh

```bash
python3 -I data/policy-pack/kiem_goi.py .                   # YAML, biến CEL, miền tham số, DMN phủ kín
python3 -I data/golden/test_chay_ca_mau.py                  # bộ tính tham chiếu với ca vàng
```

Kết quả pipeline: tệp `ca-vang.txt` và `chenh-lech-*.json` lưu ở artifact của bản build Jenkins.

## Các bước

| Ngày | Việc | Ai | Kết quả |
|---|---|---|---|
| 1 | Soạn thay đổi trong công cụ soạn quy tắc, tạo merge request vào `data/policy-pack/<khối>/<gói>` | Chuyên viên nghiệp vụ | Merge request |
| 2 | Pipeline chạy "Cú pháp quy tắc", "Kiểm thử đơn vị quy tắc", "Ca vàng 100%"; sửa tới khi xanh, gộp | Nghiệp vụ, đội phát triển | `ca-vang.txt` đạt 100% |
| 3 | Nhánh main: "Đóng gói và ký" (cosign), "So sánh bóng" trên dữ liệu đã che của kỳ trước (`POST /policies/packs/{id}/versions/{v}/test-runs`) | Pipeline | `chenh-lech-<gói>.json` theo tenant |
| 4 | Đọc báo cáo chênh lệch, bấm duyệt ở bước "Duyệt phát hành" của Jenkins (chỉ nhóm `approverGroup`) | Chủ sản phẩm, đại diện nghiệp vụ khách hàng | Ý kiến duyệt có tên người |
| 5 | "Gửi phát hành" gọi `POST .../submit` với kỳ và phạm vi; mẫu duyệt phát hành trên máy chủ chạy, EVT-13 phát khi duyệt xong | Pipeline, cấp quyết định | Phiên bản gói `DA_PHAT_HANH` |

Khai `packIds`, `effectivePeriodId`, `scopes` trong `data/policy-pack/Jenkinsfile` cho đợt phát hành (ghi ở đầu tệp).

**Nếu ca vàng dưới 100%:** không được gộp; nghiệp vụ xác định ca vàng sai hay quy tắc sai. Sửa ca vàng phải có chữ ký của nghiệp vụ như lần đầu.

**Nếu chênh lệch bóng lớn bất thường:** dừng ở ngày 4, không duyệt; nghiệp vụ giải trình từng nhóm tenant chênh lệch.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Kiểm tĩnh gói | (chưa có) | `python3 -I data/policy-pack/kiem_goi.py .` |
| So sánh bóng | (chưa có) | `POST /api/v1/policies/packs/{id}/versions/{v}/test-runs` |
| Gửi phát hành | (chưa có) | `POST /api/v1/policies/packs/{id}/versions/{v}/submit` với `If-Match` |

## Kiểm sau khi làm

Phiên bản gói đã phát hành và gắn đúng kỳ, phạm vi; ca vàng chạy lại trên máy chủ đạt 100%; sổ cái có bản ghi phát hành; với kỳ đang mở, OPS-21 đêm đó không có hồ sơ `LOI`.

## Quay lui

Phát hành lại phiên bản trước cho cùng kỳ qua cùng pipeline (một lượt mới, cũng qua duyệt). Kỳ đã khoá không đổi gói; điểm đã chốt chỉ sửa qua mở lại kỳ có hai người duyệt (QT-14).

## Leo thang

Sai điểm phát hiện sau phát hành trên kỳ đang mở: SEV-2, gọi chủ sản phẩm và chủ service scoring; tạm hoãn khoá kỳ của tenant bị ảnh hưởng.

## Liên quan

`data/policy-pack/`, `data/golden/README.md`, [OPS-21](ops-21-tinh-lai-doi-chieu-diem.md), `ci/vt-pipeline/README.md`.
