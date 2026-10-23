> Nguồn: TL-04 mục 9.6 (SEC-23), mục 9.7; task INF-05, INF-06. Ngày đánh giá: 23/10/2026.

# Bằng chứng 07: SBOM (SEC-23)

## Yêu cầu

Mỗi ảnh container có SBOM theo chuẩn CycloneDX, được lưu và theo dõi lỗ hổng trong Dependency-Track. TL-04 yêu cầu bằng chứng là danh sách SBOM theo các phiên bản đang chạy.

## Biện pháp đã có

Thư viện pipeline Jenkins dùng chung (`ci/vt-pipeline`) có bước `sbomVaQuetAnh` trong `ci/vt-pipeline/vars/vtBuoc.groovy`. Bước này sinh SBOM CycloneDX bằng syft từ ảnh đã đẩy lên registry và quét ảnh bằng Trivy; pipeline bị chặn khi có lỗ hổng mức HIGH hoặc CRITICAL đã có bản vá. Sau đó SBOM được đẩy lên Dependency-Track. Các pipeline Java, Go, Python và Flutter đều gọi bước này (`ci/vt-pipeline/vars/vtJavaPipeline.groovy`, `ci/vt-pipeline/vars/vtGoPipeline.groovy`, `ci/vt-pipeline/vars/vtPythonPipeline.groovy`). Cách cấu hình được mô tả ở `ci/vt-pipeline/README.md`.

## Cách thu bằng chứng

Script `thu-bang-chung.sh` đọc danh sách ảnh đang chạy theo digest (`deploy/environments/production/digests.yaml` hoặc tệp tương ứng của môi trường thử), rồi tải SBOM đã gắn vào ảnh bằng `cosign download sbom` hoặc `cosign verify-attestation --type cyclonedx`, và ghi mỗi ảnh một tệp vào thư mục `sbom/`.

## Trạng thái và điểm còn thiếu

Trạng thái: **Đáp ứng (giả lập)**. Pipeline đã sinh SBOM và có kiểm thử thư viện pipeline. Còn thiếu máy chủ Jenkins, Harbor và Dependency-Track thật để có danh sách SBOM của các phiên bản đang chạy.
