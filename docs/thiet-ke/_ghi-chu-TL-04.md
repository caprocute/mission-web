# Ghi chú TL-04: các điểm bổ sung và điều chỉnh so với baseline

Tệp này liệt kê những chỗ TL-04 (`src/TL-04-trien-khai-van-hanh.md`, dựng từ `src/TL-04/part-01.md` đến `part-10.md`) bổ sung hoặc diễn giải khác so với `00-co-so-thiet-ke-chung.md`, và những chỗ đã căn chỉnh theo `_ghi-chu-TL-01.md` và TL-02a, để các tài liệu khác đối chiếu.

## Mã do TL-04 cấp

- SEC-01 đến SEC-60 (danh mục ở mục 9.2), OPS-01 đến OPS-26 (mục 11.1), PERF-01 đến PERF-26 (mục 7.4), DR-01 đến DR-16 (mục 8.11). Dải dự phòng: SEC tới 99, OPS tới 99, PERF tới 99, DR tới 20.
- SLO-AVL, SLO-READ, SLO-WRITE, SLO-ASYNC, SLO-PAGE, SLO-LCP, SLO-SCORE, SLO-LEDGER (mục 7.1).

## Hạ tầng và topology

1. Mỗi cell có một cụm Kubernetes ở mỗi TTDL, gộp ứng dụng và dữ liệu trong cùng cụm trên các node pool có taint riêng (mục 3.2). Ngoài ra có cụm quản trị `mgmt-hl`, `mgmt-sn` và cụm vùng điều phối toàn cục `global-hl`, `global-sn`, tổng 20 cụm cho SaaS.
2. Kubernetes 1.36, theo chính sách n-1, nâng cấp minor mỗi quý (OPS-15).
3. Namespace dữ liệu nằm ngoài mesh Istio ambient và dùng TLS gốc với `verify-full`; vùng ứng dụng dùng mTLS STRICT (mục 3.7).
4. Mỗi cell có sáu cụm PostgreSQL: `pg-core`, `pg-flowable`, `pg-platform`, `pg-iam`, `pg-ledger`, `pg-secure` (mục 4.5).
5. Ceph được chia theo nhóm cell: `ceph-hl-1` cho HCM và HN, `ceph-hl-2` cho G1 đến G4 và SH, `ceph-dtw` cho DTW. Mỗi cell có một realm RGW riêng để chuyển vùng độc lập (mục 3.8, 8.4). Kho sao lưu `ceph-sn-bk` là một cụm Ceph riêng ở phía Nam, có miền quản trị riêng.
6. Cluster Autoscaler dùng nhà cung cấp Cluster API (không dùng Karpenter), cùng pod giữ chỗ ưu tiên thấp (mục 6.5). Đề xuất ký năng lực dự trữ 2.000 vCPU với Viettel Cloud cho chuyển vùng và đỉnh.
7. Workflow-service tách thành `workflow-api` (HPA) và `workflow-executor` (KEDA theo `flowable_async_jobs_due_unlocked`) (mục 6.6).
8. GeoDNS: chỉ tên miền vùng điều phối toàn cục tự chuyển (PowerDNS LUA `ifurlup`); điểm vào cell chỉ đổi qua cờ `active_site` trong SVC-03 và tác vụ `dns-sync`, quyết định chuyển vùng cell là của con người (mục 2.7, DR-10).
9. JWT có claim `cell`, APISIX kiểm claim này; plugin APISIX tuỳ biến `vt-claims` (mục 6.8).
10. Mission-core dùng một DataSource cho mỗi module; tài khoản `svc_<module>` và `mig_<module>` (thống nhất với TL-01).
11. Mức suy giảm 0 đến 3 khi bão hoà (mục 6.9); ai-gateway trả 503 kèm `Retry-After` khi quá tải và tạm dừng khi DR.
12. Khung tăng trước của KEDA và lịch đóng băng Argo CD được sinh tự động từ `config.period` (MOD-08) (mục 6.4, 11.4).
13. Đề xuất thêm cell thí điểm nội bộ `CELL-NB` làm đợt phát hành 0 trước CELL-G4 (mục 11.2).
14. Đề xuất cân nhắc đặt primary của CELL-HCM ở TTDL phía Nam để gần người dùng (mục 2.5).
15. Thứ tự đợt phát hành: CELL-G4, rồi G1 đến G3 và SH, rồi HN và DTW, cuối cùng HCM.

## Định cỡ

16. Tổng tài khoản định cỡ theo cell là 6,5 triệu (có dư địa so với 6 triệu của NFR-01). User task đỉnh ước tính quy theo tài khoản: HCM 134/s, HN 112/s, mỗi cell G và SH 89/s, DTW 34/s. Năng lực thiết kế workflow-service ở cell lớn 150/s, ngưỡng tách engine 300/s (theo TL-01; baseline ghi 80 đến 110/s).
17. Ngưỡng tách cell 1,5 triệu tài khoản hoặc 75.000 CCU (theo TL-01), dùng trong OPS-23.
18. Giả định AI: 1% CCU dùng trợ lý; khoảng 40 GPU toàn quốc (mục 4.9).
19. Thông lượng mỗi pod mission-core 250 RPS là giả định có cách tính, hiệu chuẩn lại bằng PERF-02.

## Sao lưu và DR

20. Giữ dòng outbox 72 giờ (xoá theo phân vùng ngày) để phát lại sau chuyển vùng; baseline chưa nêu thời gian giữ (mục 8.10).
21. Nguyên tắc 3-2-1-1-0; minh chứng giữ 10 năm Object Lock compliance; xoá dữ liệu cá nhân bằng xoá mật mã.
22. Slot logic của Debezium dùng failover slot của PostgreSQL 17 trong TTDL; sang TTDL khác thì tạo slot mới và phát lại outbox.
23. Vùng điều phối toàn cục dùng `synchronous_commit = remote_apply` qua hai TTDL (chỉ riêng cụm này).
24. AI và các tác vụ nền không thiết yếu tạm dừng khi chạy DR ở phía Nam.

## Quan sát

25. Cách hiểu NFR-10: "≥ 99% request có trace" là độ đầy đủ của ngữ cảnh trace (`trace_completeness_ratio`), còn lưu trữ dùng tail sampling: 100% lỗi, 100% chậm, 10% bình thường. Cần chủ đầu tư xác nhận.
26. Lưu giữ: Loki 90 ngày nóng, 1 năm lạnh; Tempo 14 ngày; Thanos thô 30 ngày, 5 phút 180 ngày, 1 giờ 2 năm; kiểm toán 10 năm qua `sys.audit.v1` sang Object Lock và SIEM.
27. Công cụ AGPL (Grafana, Loki, Tempo, k6) chỉ dùng nội bộ; bản cài tại chỗ dùng phương án Apache-2.0 nếu pháp chế chưa duyệt (theo TL-01).

## Căn chỉnh theo TL-01 và TL-02a

28. OPA chạy là container cùng pod, đóng khi lỗi (SEC-18).
29. Niêm phong sổ cái: cây Merkle hai cấp, một chữ ký và một dấu thời gian mỗi cell mỗi giờ, 5 phút trong 3 ngày cuối kỳ; bảng `ledger.merkle_seal`; bản neo hằng ngày tới ít nhất hai bên độc lập (mục 8.8, OPS-22, PERF-16).
30. Ký lô tối đa 1.000 phiếu mỗi phiên; PERF-17 dùng 100 phiên cho 100.000 phiếu.
31. PgBouncer chế độ giao dịch với `set_config('app.tenant_id', ?, true)`, RLS `FORCE`, người dùng CSDL động có `NOBYPASSRLS` (mục 9.4).
32. DEK theo tenant, tháng và mục đích, lưu ở `keyring.dek`; C3 dùng khoá ECDH P-256 theo thiết bị và khoá phục hồi chia Shamir 3 trên 5 (SEC-11, SEC-25).
33. Topic hệ thống: `sys.audit.v1`, `sys.ledger.append.v1`, `sys.evidence.object.stored.v1`, `sys.tenant.provisioned.v1`, `iam.auth.event.v1`; DLQ `<topic>.dlq.<consumer>`, thử lại `<topic>.retry.<consumer>`. TL-04 tự đặt thêm `sys.siem.v1` và `sys.security.runtime.v1`.
34. Chuyển tenant giữa cell: chỉ đọc 5 đến 15 phút; chuỗi băm ở cell đích nối tiếp từ `entry_hash` cuối của cell nguồn (OPS-08).
35. Trạng thái vòng đời tenant trong OPS-07 dùng danh sách của TL-02 (`KHOI_TAO` đến `DA_CHAM_DUT`). Saga khoá kỳ (bước 0 đến 5, EVT-09, 10, 17, 18, 19, huỷ qua EVT-26 chỉ trước khi bước 4 xong) được đưa vào danh mục chuẩn bị OPS-17.

## Điểm cần các tài liệu khác lưu ý

- TL-03: các bảng được TL-04 nhắc tới gồm `flowable.wf_task_projection`, `flowable.wf_binding`, `scoring.reconcile_diff`, `ledger.verify_run`, `ledger.merkle_seal`, `keyring.dek`, bảng `outbox_event` (phân vùng theo ngày, giữ 72 giờ) và `processed_event` của mỗi service.
- Bản đánh giá ánh xạ nhóm TCVN "Sao lưu dự phòng" vào mã C5, trong khi tiêu chí C5 của trục C là "tự động co giãn". TL-04 giữ nguyên ánh xạ của bản đánh giá ở bảng TCVN và ghi chú rõ ở chương 8 và chương 13.
