# Sổ tay lưu trữ, xoá và khôi phục dữ liệu theo lớp lưu giữ

> Nguồn: TL-03 "Lưu giữ, lưu trữ và xoá dữ liệu" (bảy lớp, cơ chế lưu trữ, xoá và giữ pháp lý), chương 9 (xoá bằng mật mã), "Sổ cái bất biến › Lưu trữ dài hạn"; baseline NFR-09; NĐ 13/2023/NĐ-CP; DL-13.
> Mã: `backend/libs/retention` (lớp `RetentionMain`); cấu hình thời hạn: `config.tenant_setting`, khoá `retention.<LỚP>`; chart: `deploy/charts/mission-core/templates/retention.yaml`.

Sổ tay này dành cho SRE trực cell và cán bộ phụ trách lưu trữ hồ sơ. Dữ liệu nghiệp vụ được chia thành bảy lớp lưu giữ; mỗi lớp có thời hạn nóng (còn trong PostgreSQL), tổng thời hạn và cách kết thúc. Giá trị mặc định là đề xuất của TL-03, thời hạn chính thức do cơ quan chủ quản quyết định.

| Lớp | Nóng | Tổng | Kết thúc | Job |
|---|---|---|---|---|
| `HO_SO_CAN_BO` | 5 năm | 20 năm | Chuyển lưu trữ lịch sử | `luu-tru-<schema>` |
| `KHIEU_NAI` | 5 năm | 20 năm | Chuyển lưu trữ lịch sử | `luu-tru-<schema>` |
| `THUC_HIEN` | 3 năm | 10 năm | Xoá bằng mật mã | `luu-tru-<schema>`, `xoa-mat-ma` |
| `SO_CAI` | 2 năm | 20 năm (sàn 10 năm) | Theo hồ sơ dài nhất | SVC-09 `svc_ledger_archiver`, hai người duyệt |
| `VET_TINH` | 2 năm | 10 năm | Xoá bằng mật mã | `luu-tru-<schema>`, `xoa-mat-ma` |
| `VAN_HANH` | đến 1 năm | 1 năm | Xoá phân vùng | `xoa-phan-vung-<schema>` |
| `KY_THUAT` | 3 đến 14 ngày | 14 ngày | Xoá phân vùng | `xoa-phan-vung-<schema>` |

## Đặt thời hạn cho một tenant

Thời hạn của tenant ghi ở `config.tenant_setting` với `setting_key = 'retention.<LỚP>'`, `value_schema = 'retention-class.v1'`, giá trị dạng `{"hot":"P5Y","total":"P20Y"}` (chu kỳ ISO 8601). Giá trị sai lược đồ, có thời hạn nóng dài hơn tổng, hoặc tổng của `SO_CAI` dưới 10 năm bị bỏ qua và hệ thống dùng giá trị đề xuất. Một phân vùng ở CSDL bridge hay pool chứa nhiều tenant, nên job dùng thời hạn giữ lâu nhất của các tenant trên CSDL đó; muốn rút ngắn thời hạn của một tenant thì mọi tenant cùng CSDL phải cùng rút.

## Lưu trữ phân vùng kỳ quá hạn nóng

CronJob `luu-tru-<schema>` chạy 01:30 ngày 2 hằng tháng. Với mỗi `period_bucket` mà mọi bảng cùng lớp đã quá hạn nóng, job tách phân vùng khỏi bảng cha, xuất từng tenant sang Parquet (zstd) vào bucket Object Lock (`data-archive`, tiền tố `luu-tru/<schema>/<kỳ>/`), đọc lại so băm, ghi tệp kê khai `manifest.json` (băm, số dòng, kiểu cột), ghi sự kiện kiểm toán `RETENTION_ARCHIVE` cho từng tenant, rồi xoá phân vùng. Cột C2 vẫn là bản mã trong Parquet. Hạn Object Lock COMPLIANCE bằng ngày kết thúc kỳ cộng tổng thời hạn; tệp của tenant có dòng `legal_hold` được bật thêm giữ pháp lý.

Kiểm chứng sau mỗi lần chạy:

1. Nhật ký job ghi các nhóm đã lưu trữ và số tenant; không có dòng lỗi.
2. Phân vùng `<bảng>_p<kỳ>` không còn trong schema: `\dt <schema>.*_p<kỳ>*`.
3. Tệp kê khai có trên bucket và số dòng mỗi bảng khớp với số dòng trước khi lưu trữ (lấy từ báo cáo dung lượng hoặc `pg_class.reltuples` trước kỳ chạy).
4. Hằng quý, chọn ngẫu nhiên 5% tệp, tải về và so SHA-256 với tệp kê khai (cùng nhịp với kiểm tra lưu trữ của sổ cái).

**Quay lui.** Nếu job lỗi trước bước xoá, nó tự gắn lại phân vùng và bật lại `FORCE ROW LEVEL SECURITY`; dữ liệu nằm nguyên trong PostgreSQL. Nếu job bị dừng đột ngột (pod bị xoá) sau khi tách, phân vùng còn ở dạng bảng rời `<bảng>_p<kỳ>`; lần chạy sau nhận ra và xử lý tiếp. Muốn gắn lại tay: `ALTER TABLE <schema>.<bảng>_p<kỳ> FORCE ROW LEVEL SECURITY; ALTER TABLE <schema>.<bảng> ATTACH PARTITION <schema>.<bảng>_p<kỳ> FOR VALUES IN (<kỳ>);` bằng `mig_<schema>`. Tệp đã ghi lên bucket không xoá được (COMPLIANCE) và không gây hại: lần chạy sau ghi đè bằng phiên bản mới cùng khoá.

## Xoá bằng mật mã

CronJob `xoa-mat-ma` chạy 03:00 ngày 3 hằng tháng. Với lớp `THUC_HIEN` (mục đích DEK `TEP_MINH_CHUNG`) và `VET_TINH` (chưa gắn mục đích, đặt bằng `retention.classes`), DEK của tháng đã hết tổng thời hạn bị huỷ: bản bọc bị xoá, trạng thái `DESTROYED`. Từ đó mọi bản mã của tháng đó, kể cả trong Parquet và bản sao lưu, không mở được nữa; ứng dụng nhận lỗi có kiểm soát `DekDestroyedException`. Bộ đệm DEK trong pod còn giữ khoá tối đa 15 phút.

DEK đang được dữ liệu có `legal_hold` dùng (tệp ở `evidence.object_meta` hoặc bảng khai trong `retention.holdTables`) bị bỏ qua; job ghi sự kiện `RETENTION_CRYPTO_ERASE` với kết quả `BO_QUA_GIU_PHAP_LY` và gia hạn Object Lock của các phiên bản tệp liên quan thêm `retention.holdExtension` (mặc định một năm) mỗi lần chạy.

Kiểm chứng: nhật ký job liệt kê DEK đã huỷ và DEK bỏ qua; `SELECT status, length(wrapped_dek) FROM keyring.dek WHERE id = '<dek>'` trả `DESTROYED`, `0`; sự kiện kiểm toán có đủ. **Không có quay lui**: huỷ DEK là không thể đảo ngược theo thiết kế. Trước khi bật job lần đầu cho một tenant, đối chiếu danh sách DEK sẽ huỷ (chạy job với ConfigMap tenant chỉ chứa tenant đó trên môi trường thử nghiệm có bản sao cấu hình) và xác nhận bằng văn bản với cơ quan chủ quản.

Yêu cầu của chủ thể dữ liệu theo NĐ 13/2023 không làm huỷ sớm dữ liệu bắt buộc lưu; dữ liệu đó chỉ bị hạn chế xử lý.

## Xoá phân vùng lớp vận hành và kỹ thuật

CronJob `xoa-phan-vung-<schema>` chạy hằng ngày, đặt `retention` của pg_partman cho các bảng lớp `VAN_HANH`, `KY_THUAT` không dài hơn tổng thời hạn (thời hạn ngắn hơn đã có, như outbox 3 ngày, idempotency 2 ngày, được giữ), rồi gọi `fn_run_partition_maintenance()`. Bảng có cột `legal_hold` không bao giờ bị xoá theo cách này. Kiểm chứng: `SELECT parent_table, retention FROM partman.part_config`. Quay lui: đặt lại `retention` bằng migration; phân vùng đã gỡ không khôi phục được ngoài bản sao lưu.

## Khôi phục dữ liệu đã lưu trữ

Truy cập dữ liệu lưu trữ cần phiếu yêu cầu có hai người: người đề nghị và người duyệt khác nhau.

1. Xác định tệp kê khai (`luu-tru/<schema>/<kỳ>/<LỚP>/manifest.json`) và tenant.
2. Mở PR GitOps đặt `retention.restore` gồm `keKhai`, `tenant`, `phieu`, `nguoiDeNghi`, `nguoiDuyet`, `soNgay` (mặc định 7). Argo CD tạo Job `khoi-phuc-<phiếu>`. Công cụ từ chối chạy khi thiếu phê duyệt hoặc hai người trùng nhau.
3. Job tạo CSDL `khoi_phuc_<phiếu>` trên cụm khôi phục cô lập (`retention.restoreDbUrl`, không phải cụm vận hành), kiểm băm từng tệp, nạp dữ liệu của đúng tenant đó, ghi hạn dùng vào chú thích CSDL và ghi sự kiện `RETENTION_RESTORE` mang số phiếu và người duyệt.
4. Kiểm chứng: số dòng trong nhật ký job khớp tệp kê khai; cán bộ được duyệt truy cập CSDL tạm bằng tài khoản có thời hạn. Cột C2 vẫn là bản mã; đọc nội dung cần quy trình phá kính SEC-58.
5. CronJob `don-khoi-phuc` tự xoá CSDL tạm quá hạn mỗi ngày. Gỡ `retention.restore` khỏi values bằng PR tiếp theo.

**Quay lui.** Khôi phục lỗi giữa chừng để lại CSDL tạm dở dang: xoá bằng `DROP DATABASE khoi_phuc_<phiếu> WITH (FORCE)` trên cụm khôi phục rồi chạy lại Job. Không thao tác nào của công cụ ghi vào cụm vận hành.

## Liên quan

- `xoay-vong-khoa-du-lieu.md` (DEK, KEK)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md` mục ghi chú DL-13
