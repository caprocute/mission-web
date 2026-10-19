# WEB-002 · Pipeline Jenkins cho frontend có cổng chất lượng

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-002` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | GĐ0 – Nền tảng phát triển |
| Cỡ việc | M (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-001 |
| Truy vết | TL-04 SEC-23, SEC-37..SEC-39, SEC-49, SEC-55, ADR-16 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Tạo Jenkinsfile build và kiểm tra frontend theo cổng chất lượng của TL-04, xuất image web đã ký.

## 2. Đầu vào bắt buộc đọc

- wiki/11-kiem-thu-va-chat-luong.md
- wiki/12-ha-tang-dev-va-trien-khai.md
- TL-04 mục "Chuỗi cung ứng phần mềm: SLSA, Jenkins, Argo CD"

## 3. Phạm vi công việc

- Các stage: npm ci → lint → test (báo cáo coverage tệp thay đổi) → build:all → kiểm tra ngân sách bundle → Semgrep → gitleaks → Trivy (fs + image) → SBOM CycloneDX → docker build `deploy/web/Dockerfile` → cosign ký.
- Thất bại ở bất kỳ cổng nào làm pipeline dừng; ghi rõ biến/credential Jenkins cần có (không ghi giá trị).

## 4. Ngoài phạm vi

- Cấu hình Argo CD và môi trường K8s.

## 5. Tệp được phép sửa

- Jenkinsfile
- deploy/web/**
- docs/tasks/README.md

## 6. Tiêu chí hoàn thành

- Jenkinsfile hợp lệ (kiểm bằng `jenkins-cli declarative-linter` nếu có, hoặc rà soát cú pháp) và mô tả cách chạy trong wiki/11.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-002 trong `docs/tasks/README.md`.
