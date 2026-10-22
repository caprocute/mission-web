# Tác vụ ẩn danh hoá dữ liệu cho môi trường phi sản xuất (DL-15)

> Truy vết: SEC-52 → TL-04 mục 9.7 "Dữ liệu cá nhân (SEC-52)" → schema `org`, `product`, `evidence`
> (và `task`, `appraisal`, `oversight` khi có cột dữ liệu cá nhân) → hồ sơ đánh giá tác động VH-07.

TL-04 cấm môi trường phi sản xuất dùng dữ liệu thật. Khi cần dữ liệu gần thật để tái hiện lỗi, bản sao
CSDL phải được che trên cụm cách ly bằng tác vụ này rồi mới chuyển sang staging. Tác vụ thay họ tên, số
định danh, ngày sinh, số thẻ đảng viên, liên lạc, tên tệp và nhận xét bằng giá trị tổng hợp giữ phân
phối, mã hoá lại trường C2 bằng khoá của staging, loại bỏ bảng chứa tải trọng sự kiện và bản bọc khoá
E2EE, rồi quét lại toàn bộ để chứng minh không còn giá trị nguồn nào. Kết quả mỗi lần chạy là một biên
bản JSON không chứa giá trị dữ liệu, dùng cho hồ sơ VH-07.

## Thành phần

| Tệp | Vai trò |
|---|---|
| `data/anonymize/rules.yaml` | Bộ luật che theo từng cột và danh sách bảng loại bỏ (chỉ quy tắc, không có dữ liệu) |
| `anonymize/danh_muc.py` | Suy danh mục cột phải có luật từ migration thật: chú thích `cls` C2/C3, `"pii": true`, hậu tố `_enc`, `_bidx` |
| `anonymize/c2.py` | Phong bì AES-256-GCM và chỉ mục mù HMAC khớp từng byte với NEN-015, kiểm bằng `c2-test-vectors.json` |
| `anonymize/khoa.py` | Bọc và mở bọc DEK qua OpenBao Transit (nguồn, staging) hoặc KEK cục bộ cho kiểm thử |
| `anonymize/gia.py` | Giá trị giả (dùng lại `ten_gia` của DL-08) và ánh xạ nhất quán bằng HMAC với khoá dùng một lần |
| `anonymize/che.py` | Lõi che tại chỗ theo tenant, thay DEK, mã hoá lại, tính lại chỉ mục mù |
| `anonymize/quet.py` | Bước quét sau che trên mọi cột văn bản, JSON và bản rõ của mọi cột `_enc` |
| `anonymize/chay.py`, `__main__.py` | Một lần chạy đầy đủ và dòng lệnh |

## Cách chạy

```bash
cd infra/anonymize
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python -m anonymize kiem-luat          # cổng CI: mọi cột nhạy cảm đều có luật (mã thoát 3 nếu thiếu)

# Trên cụm cách ly, với bản sao CSDL đã khôi phục. Mật khẩu, token chỉ qua biến môi trường hoặc tệp.
export ANON_PG_DSN="host=<máy> port=5432 dbname=<csdl> user=<vai trò nạp có BYPASSRLS>"   # PGPASSWORD hoặc ~/.pgpass
export ANON_NGUON_ADDR=https://<openbao nguồn> ANON_NGUON_MOUNT=transit ANON_NGUON_TOKEN_FILE=/run/secrets/nguon
export ANON_DICH_ADDR=https://<openbao staging> ANON_DICH_MOUNT=transit ANON_DICH_TOKEN_FILE=/run/secrets/dich
.venv/bin/python -m anonymize chay --bien-ban bien-ban.json
```

Mã thoát 0 nghĩa là biên bản `DAT`; mã 2 nghĩa là bước quét còn trúng (`CHAN`), bản sao không được
chuyển sang staging; mã 3 nghĩa là còn cột chưa có luật. Quy trình chuyển bản sao (VH) phải kiểm mã
thoát hoặc trường `ket_luan` của biên bản trước khi xuất.

## Cách hoạt động

Mỗi tenant được che trong một giao dịch. Tác vụ mở bọc mọi DEK của tenant bằng KEK nguồn, sinh DEK mới
và bọc bằng KEK staging nhưng giữ nguyên `keyring.dek.id`, nên mọi cột `dek_id` và khoá ngoại không đổi.
Với từng bảng có luật, tác vụ đọc bằng con trỏ phía máy chủ, giải mã cột `_enc` với AAD
`<tenant>|<schema.bảng.cột>|<mã dòng>`, áp luật lên bản rõ, mã hoá lại bằng DEK mới, tính lại chỉ mục
mù bằng khoá gốc `BLIND_INDEX` mới, rồi ghi từng khối qua bảng tạm (chỉ chứa bản mã mới và giá trị giả)
bằng `UPDATE ... FROM` theo khoá chính. Khoá chính, khoá ngoại, `period_bucket`, trạng thái và mốc thời
gian không bị đụng tới, nên ràng buộc loại trừ hai trục thời gian và chính sách RLS vẫn đúng. Bản rõ
chỉ nằm trong bộ nhớ tiến trình; khoá ánh xạ dùng một lần, tập giá trị nguồn và DEK được xoá khi chạy
xong (Python không bảo đảm xoá sạch bộ nhớ, nên tiến trình phải chạy trên máy của cụm cách ly và kết
thúc ngay sau lần chạy).

Ánh xạ nhất quán: cùng một giá trị nguồn nhận cùng một giá trị giả ở mọi bảng và mọi phiên bản dòng
(ví dụ họ tên trong `org.person` và `org.scim_resource`), hai giá trị nguồn khác nhau không nhận cùng
giá trị giả ở cột cần duy nhất, và giá trị giả không bao giờ trùng chính giá trị gốc. Ngày sinh được
dịch trong cùng nhóm tuổi 5 năm tại ngày mốc của bộ luật; số định danh giả dùng mã tỉnh 999 (không
cấp cho tỉnh nào), khớp giới và năm sinh giả; điện thoại giả dùng đầu số 0100; thư dùng miền
`.invalid`. Giới tính và cây tổ chức được giữ, nên phân phối nhóm tuổi, giới và khối không đổi.

Cột `_enc` trong danh mục mà thiếu luật vẫn được giải mã và mã hoá lại nguyên giá trị (luật `giu` ngầm
định, ghi trong biên bản), nếu không bản mã sẽ hỏng khi DEK được thay; vì vậy lệnh `chay` từ chối chạy
khi còn cột thiếu luật, và nếu cố tình cho phép thì bước quét sẽ chặn. Giá trị không giải được (DEK
đã huỷ bằng mật mã) được thay bằng giá trị giả và đếm trong biên bản.

Bước quét đối chiếu với tập giá trị nguồn thu được lúc che: chuỗi chữ số 9 đến 12 ký tự (số định danh,
số thẻ đảng, điện thoại, chuẩn hoá `+84`), địa chỉ thư, cụm 2 đến 5 từ (họ tên) và văn bản tự do trùng
nguyên giá trị. Họ tên nguồn nằm trong kho tên giả của DL-08 (ví dụ "Nguyễn Văn An") không được đưa vào
danh sách quét vì trùng như vậy là ngẫu nhiên; thay vào đó luật bảo đảm mỗi dòng nhận tên khác tên gốc,
và biên bản ghi số tên bị bỏ qua.

## Điểm chưa rõ và giả định (suy luận)

- TL-04 nêu "cụm cách ly" nhưng chưa có thẻ dựng cụm; tác vụ giả định chạy trên một CSDL bản sao riêng
  với vai trò có BYPASSRLS và hai địa chỉ OpenBao (nguồn chỉ cần quyền decrypt, staging chỉ cần encrypt).
- Nhật ký kiểm toán và sổ cái băm chuỗi chưa được TL quy định che hay loại bỏ; bộ luật loại bỏ mọi bảng
  khớp `*audit*`, `*ledger*` cùng outbox, inbox, idempotency, `org.scim_sync_log` và sự kiện modulith.
- Tệp C3 trong kho đối tượng không thuộc phạm vi CSDL: khi bản bọc khoá (`*.e2ee_key_wrap`) bị xoá, các
  tệp này thành bản mã không ai mở được. Bản sao kho đối tượng cho staging phải xử lý riêng (VH).
- AAD của `org.person_contact` và `product.acceptance.comment_enc` lấy mã dòng `id` vì chưa có mã Java
  ghi hai cột này; `evidence.upload_session.file_name_enc` dùng toạ độ cột của `object_meta` đúng như
  `UploadService`.
- Chưa có bản Python dùng chung của NEN-018; `anonymize/c2.py` là bản cài đặt riêng được kiểm bằng cùng
  vector với Java, nên khi NEN-018 có thì nên chuyển sang dùng thư viện đó.
- Chưa có luật dịch mốc thời gian theo độ lệch chung; khi cần sẽ thêm luật mới.
- Thời gian chạy ở quy mô cell chưa đo (YC-PT-19, đo ở giai đoạn hiệu năng).

## Kiểm thử

```bash
.venv/bin/python -m pytest            # kiểm thử đơn vị luôn chạy; kiểm thử CSDL bỏ qua khi chưa dựng
../perf/datagen/scripts/chuan-bi-csdl.sh && .venv/bin/python -m pytest -m csdl
.venv/bin/pip-audit -r requirements.txt
```

Kiểm thử CSDL dùng chung PostgreSQL 17 đã áp migration thật của DL-08 (tệp `../perf/datagen/.datagen-pg.env`,
hoặc trỏ tệp khác bằng `ANON_TEST_ENV`), sinh và nạp dữ liệu DL-08 cell L hệ số 0,001, biến bản mã giả
thành nguồn có khoá thật, rồi kiểm: che thành công, quét sạch, RLS đúng, phân phối nhóm tuổi, giới,
khối lệch không quá 5%, chỉ mục mù tính lại đúng; cố ý để sót một số định danh hoặc bỏ luật một cột C2
thì biên bản là `CHAN`.
