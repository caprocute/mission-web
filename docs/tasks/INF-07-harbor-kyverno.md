# INF-07 · SEC-28, SEC-38 Harbor và chính sách Kyverno: chỉ ảnh có ký, không root, có giới hạn và probe, `vt-require-ha`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-07` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0c (Kyverno Enforce); Harbor dựng cùng cụm quản trị đợt 0b |
| Luồng đề xuất | B8 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | INF-05 |
| Truy vết | TL-04 mục 9.3 "Bảo mật nền tảng Kubernetes", mục 9.6 → SEC-26, SEC-28, SEC-38, SEC-49, SEC-55 → tiêu chí C1, C3, C7, C10 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng kho ảnh Harbor của cụm quản trị và bộ chính sách Kyverno bắt buộc của TL-04 để chỉ ảnh đã ký, theo digest, có chứng thực SLSA, chạy không root, có tài nguyên và probe, đủ độ sẵn sàng mới được chạy trong cụm. Khi xong, trên cụm kind có Kyverno, pod dùng ảnh chưa ký hoặc thiếu probe bị từ chối, và bộ chính sách được dùng lại trong CI của INF-02 và INF-06.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục "Bảo mật nền tảng Kubernetes" (2603–2864, chính sách Kyverno 2705–2856) và "Chuỗi cung ứng phần mềm: SLSA, Jenkins, Argo CD" (3022–3138).
- Cùng tệp mục "Vùng điều phối toàn cục và các cell" và "Số cụm và lựa chọn gộp hay tách cụm dữ liệu" (220–232: Harbor thuộc cụm `mgmt-hl`/`mgmt-sn`); bảng lịch sao lưu (2309–2328, dòng Harbor).
- `docs/quy-dinh/HA-TANG.md` mục 2, chart `vt-service` (INF-01), pipeline ký ảnh (INF-05).

## 3. Phạm vi công việc

- Viết values triển khai Harbor cho cụm quản trị (chart Harbor chính thức, ghim phiên bản) trong `deploy/clusters/mgmt/harbor/`: project theo nhóm ảnh, robot account riêng cho Jenkins (chỉ đẩy) và cho mỗi cụm (chỉ kéo), bật quét Trivy, chặn kéo ảnh có lỗ hổng Critical; cấu hình nhân bản sang `harbor-sn` để INF-18 bật.
- Viết `ClusterPolicy vt-require-signed-images` (Enforce, `webhookTimeoutSeconds: 15`) cho namespace có nhãn `vt.io/zone` thuộc `app`, `data`, `secure`, `edge`, `egress`: ảnh từ Harbor nội bộ, `verifyDigest`, khoá công khai `hashivault://cosign-release`, attestation `https://slsa.dev/provenance/v1` đúng repo và builder.
- Viết `ClusterPolicy vt-pod-hardening` gồm bốn quy tắc: chỉ registry nội bộ theo digest; `runAsNonRoot`, `readOnlyRootFilesystem`, cấm leo quyền, UID ≥ 10000, drop ALL; bắt buộc `resources` và probe trên cổng 8081 cho vùng `app`; không gắn token ServiceAccount nếu không cần.
- Viết `ClusterPolicy vt-require-ha`: replica ≥ 3 kèm `topologySpreadConstraints` theo vùng `DoNotSchedule`; quy tắc `require-pdb` để ở chế độ Audit cho tới khi chart sinh PDB trước Deployment.
- Thêm quy tắc phát hiện workload có cả HPA và ScaledObject (TL-04 mục 6.1; đặt Audit, suy luận về cách hiện thực).
- Đặt quy trình ngoại lệ: mẫu `PolicyException` có hạn trong `deploy/policies/exceptions/`, chính sách mới ở Audit ≥ 2 tuần trước khi Enforce, ghi trong README của thư mục.
- Viết bộ kiểm thử `kyverno test` với tài nguyên đạt và không đạt cho từng quy tắc.

## 4. Ngoài phạm vi

- Pod Security Admission, Falco, audit API (INF-19); chính sách mạng (INF-08, INF-09).
- Đóng gói Harbor nội bộ cho bản cách ly (INF-23).

## 5. Tệp được phép sửa

- `deploy/policies/**`, `deploy/clusters/mgmt/harbor/**` (suy luận về vị trí).
- `deploy/environments/kind/**` (cài Kyverno và registry thử cho kind).
- `wiki/12-ha-tang-dev-va-trien-khai.md`.

## 6. Tiêu chí hoàn thành

- `kyverno test deploy/policies` xanh, mỗi quy tắc có ít nhất một ca đạt và một ca vi phạm.
- Trên kind có Kyverno và registry thử: ảnh ký bằng cosign (khoá thử tạo lúc chạy, không commit) chạy được; ảnh chưa ký, ảnh dùng thẻ `latest`, pod chạy root, pod thiếu probe đều bị từ chối với thông báo rõ.
- `helm lint`, `helm template` values Harbor không lỗi; nếu tài nguyên máy cho phép, cài Harbor lên kind và đẩy, kéo một ảnh theo digest (ghi rõ nếu không chạy được).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki 12 ghi cách xin ngoại lệ chính sách.

## 7. Báo cáo

Theo AGENTS.md mục 6.
