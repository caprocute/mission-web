# Ghi chú TL-01: các điểm bổ sung và điều chỉnh so với baseline

Tệp này liệt kê những chỗ TL-01 (`src/TL-01-thiet-ke-kien-truc.md`) bổ sung hoặc diễn giải khác so với `00-co-so-thiet-ke-chung.md`, để TL-02, TL-03, TL-04 đối chiếu và thống nhất.

## Số liệu tải và định cỡ

1. Tải luồng duyệt BPMN ở cell lớn: baseline ghi 80 đến 110 user task/giây; tính theo tỉ lệ tài khoản của CELL-HCM (1,2/6 triệu × 670/s) được khoảng 133/s, nên TL-01 chọn năng lực thiết kế 150 user task/giây cho cell lớn (chương 2, chương 8).
2. Giải thích chênh lệch giữa ước tính của bản đánh giá (70.000 phiên, 3.100 rps cho một cell) và baseline (15.000 rps toàn quốc, 30.000 khi đột biến): hai con số đo hai phạm vi khác nhau, không mâu thuẫn (mục 2.3).
3. Ngưỡng chia cell bổ sung: 1,5 triệu tài khoản hoặc 75.000 người dùng đồng thời; ngưỡng tách workflow-service theo cell con: 300 user task/giây (chương 3, ADR-29).
4. Ước tính bổ sung ở chương 9: đỉnh khoảng 167 phiên ký/giây và 667 tệp minh chứng/giây toàn quốc (33 và 133 ở cell lớn), dựa trên giả định 30% thao tác trong 3 ngày cuối kỳ với hệ số giờ cao điểm 4.

## Đa khách thuê và CSDL

5. Baseline viết `SET app.tenant_id`; vì PgBouncer chạy chế độ giao dịch, TL-01 bắt buộc dùng `set_config('app.tenant_id', ?, true)` (tương đương `SET LOCAL`) trong mỗi giao dịch, RLS `FORCE`, tài khoản ứng dụng `NOBYPASSRLS` (ADR-22).
6. Mỗi module trong mission-core có DataSource và hai tài khoản riêng `svc_<module>` (chạy) và `mig_<module>` (Flyway); mỗi schema có connector Debezium và replication slot riêng.
7. Bảng bổ sung đề xuất cho TL-03: `<schema>.idempotency_key` (ví dụ `task.idempotency_key`), schema `modulith` với bảng `event_publication` (Spring Modulith Event Publication Registry), `keyring.dek` (DEK đã bọc theo tenant, tháng, mục đích), `registry.tenant_quota`; cột chỉ mục mù `<cột>_bidx` cho trường C2 cần tra cứu; đặc tả cột của `flowable.wf_task_projection`.
8. `ledger.ledger_entry` không có cột cần cập nhật; liên kết niêm phong lưu ở `ledger.merkle_seal` theo khoảng `seq` từng chuỗi; bảng phân vùng theo `tenant_id`, trigger chặn UPDATE, DELETE, TRUNCATE.
9. Bảng hai trục thời gian được nêu cụ thể: `org.unit`, `org.position_assignment`, `org.reporting_line`, `task.assignment` (baseline chỉ nói "tổ chức").
10. Chuyển tenant giữa cell bằng sao chép logic PostgreSQL có bộ lọc dòng, chỉ đọc 5 đến 15 phút; chuỗi băm ở cell đích nối tiếp từ `entry_hash` cuối của cell nguồn.

## Sự kiện, topic, API

11. Topic hệ thống bổ sung (tiền tố `sys.`): `sys.ledger.append.v1`, `sys.audit.v1`, `sys.evidence.object.stored.v1`, `sys.tenant.provisioned.v1`; topic `iam.auth.event.v1` cho sự kiện xác thực Keycloak; quy ước hàng đợi thư chết `<topic>.dlq.<consumer>`.
12. Sự kiện trong tiến trình giữa module dùng Event Publication Registry của Spring Modulith; sự kiện ra ngoài luôn qua outbox và Kafka (ADR-23).
13. API bổ sung chưa có trong baseline: `/api/v1/appraisals/decisions/batch` (ký lô), `/api/v1/appraisals/{id}/recompute`, `/api/v1/policies/resolve`, `/api/v1/oversight/ledger/proofs`, `/api/v1/registry/tenants/{id}/move`, `/api/v1/appraisals/inputs` (đọc lô cho scoring-service). Danh mục đầy đủ ở Phụ lục C.
14. Lỗi theo RFC 9457 với trường mở rộng `code` (mã lỗi ổn định dạng `<MODULE>-<HTTP>-<TÊN>`) và `traceId`; xung đột ghi đồng thời trả 412 qua `If-Match`.

## Bảo mật và mật mã

15. OPA chạy là container trong cùng pod với service (localhost) vì Istio ambient không có sidecar; dùng đánh giá từng phần cho danh sách; lỗi thì từ chối (ADR-21).
16. Phân loại dữ liệu: baseline ADR-06 liệt kê "kết quả đánh giá" ở C2, trong khi phần G3 của baseline nói điểm và mức xếp loại không mã hoá trường. TL-01 thống nhất: điểm số và mức xếp loại là C1 bảo vệ bằng RLS, ABAC và sổ cái; nhận xét đánh giá, nội dung khiếu nại, góp ý kiểm điểm là C2 mã hoá trường. Cần TL-03 đánh dấu cột theo cách này.
17. DEK theo tổ hợp tenant, tháng, mục đích (baseline: theo tenant, tháng); AAD gồm tenant, cột và id dòng; bản mã có byte phiên bản thuật toán (ADR-30).
18. Mã hoá đầu cuối C3: bổ sung khoá ECDH P-256 theo thiết bị không trích xuất được, danh bạ khoá công khai có ký và ghi sổ cái, khoá phục hồi tổ chức chia Shamir 3 trên 5, sinh PDF chứa C3 tại thiết bị và ký trên băm (ADR-27). Lưu ý chứng thư ký số từ xa không dùng giải mã được.
19. Cây Merkle hai cấp (gốc tenant làm lá cho gốc cell), ký và lấy dấu thời gian một lần mỗi cell mỗi giờ; chuẩn hoá RFC 8785 với chuỗi NFC; tiền tố miền 0x01 cho lá, 0x02 cho nút (ADR-26). Bản neo gửi tới ít nhất hai bên độc lập hằng ngày. Niêm phong rút xuống 5 phút trong 3 ngày cuối kỳ.
20. Ký lô quyết định xếp loại tới 1.000 phiếu mỗi phiên (ADR-31).
21. Saga khoá kỳ chờ hết thời hạn bộ đệm trạng thái kỳ (30 giây) trước khi tính điểm chính thức; trong `DANG_KHOA` thao tác ghi kiểm lại trạng thái kỳ trong CSDL.

## Công nghệ

22. Bổ sung lý do không chọn: Camunda 7 bản cộng đồng hết hỗ trợ 10/2025; MinIO (AGPL); biên dịch CEL sang WebAssembly (chưa có trình biên dịch đủ trưởng thành, trình duyệt dùng `cel-js`); Drools.
23. pgvector có ngưỡng chuyển sang OpenSearch k-NN (khoảng 50 triệu vector mỗi cell) (ADR-28).
24. Các công cụ AGPL (Grafana, Loki, Tempo, k6) chỉ chạy như công cụ nội bộ, không phân phối kèm bản cài; cần pháp chế đánh giá nếu bản cài tại chỗ phải kèm.
25. Thư viện nền `vt-platform-starter` gom cấu hình Resilience4j, timeout, bulkhead, ProblemDetail, idempotency, che log (ADR-24).

## Mã mới

26. ADR mới ADR-21 đến ADR-31 (trong dải ADR-21 đến ADR-40 dành cho TL-01), viết đầy đủ ở Phụ lục A.
27. Không dùng mã SEC (để TL-04). Vai trò dùng trong Phụ lục C: `staff`, `manager`, `appraiser`, `decider`, `second_approver`, `inspector`, `org_officer`, `policy_author`, `policy_approver`, `tenant_admin`, `platform_admin` (mở rộng danh sách vai trò MFA của SVC-02).
