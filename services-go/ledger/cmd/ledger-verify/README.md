# ledger-verify

`ledger-verify` là công cụ dòng lệnh dùng để kiểm chứng gói bằng chứng sổ cái của nền tảng Mission. Công cụ chạy hoàn toàn ngoại tuyến: không kết nối mạng, không cần cơ sở dữ liệu, chỉ đọc tệp gói và các tệp đối chiếu do người kiểm cung cấp. Công cụ được viết theo mục "Công cụ kiểm chứng độc lập" của TL-01 và quy tắc BR-13-12, và mã nguồn được công bố để cơ quan thanh tra, kiểm toán có thể tự đọc và tự dựng.

## Dựng tệp chạy

Tệp chạy là tệp tĩnh, không phụ thuộc thư viện của hệ điều hành. Cần Go 1.26 trở lên:

```bash
cd services-go/ledger
CGO_ENABLED=0 go build -o ledger-verify ./cmd/ledger-verify                          # Linux
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -o ledger-verify.exe ./cmd/ledger-verify # Windows
```

Ngoài thư viện chuẩn của Go, công cụ chỉ dùng `github.com/digitorus/timestamp` và `github.com/digitorus/pkcs7` để đọc token RFC 3161, `golang.org/x/text` để chuẩn hoá Unicode NFC, và `github.com/google/uuid`. Có thể kiểm danh sách này bằng `go version -m ledger-verify`.

## Cách dùng

```text
ledger-verify --bundle <gói.tar> [--anchors <bản-neo.json>] [--tsa-ca <tsa.pem>] [--seal-ca <ca.pem>]
```

| Tham số | Ý nghĩa |
|---|---|
| `--bundle` | Tệp gói tải về từ chức năng "Xuất bằng chứng" của màn hình tra cứu sổ cái (bắt buộc). |
| `--anchors` | Tệp JSON chứa bản neo mà người kiểm lấy trực tiếp từ bên độc lập (một bản neo hoặc một mảng bản neo, theo định dạng `AnchorDocument` ở mục dưới). |
| `--tsa-ca` | Tệp PEM chứa chứng thư gốc tin cậy của đơn vị cấp dấu thời gian. Khi có tham số này, công cụ kiểm thêm chuỗi chứng thư của TSA. |
| `--seal-ca` | Tệp PEM chứa chứng thư gốc tin cậy của khoá niêm phong và khoá ký gói. Khi có tham số này, công cụ kiểm thêm chuỗi chứng thư của hai khoá. |

Khi không có `--tsa-ca` hoặc `--seal-ca`, công cụ dùng chứng thư nằm sẵn trong gói và in dấu vân tay SHA-256 kèm một dòng "Lưu ý". Người kiểm cần tự so dấu vân tay đó với dấu vân tay mà đơn vị vận hành đã công bố.

Mã thoát của công cụ có ba giá trị. Mã 0 nghĩa là kết luận HỢP LỆ. Mã 1 nghĩa là phát hiện sai, và mỗi chỗ sai được in thành một dòng "Sai:" chỉ rõ chuỗi, `seq_no`, niêm phong hoặc tệp bị sai. Mã 2 nghĩa là tham số sai hoặc không đọc được gói.

Ví dụ:

```text
$ ledger-verify --bundle goi-mau.tar --anchors ban-neo.json
Gói: tenant d567711f-…, cell IT-EX, xuất lúc 2026-10-23T02:12:19.656545Z
Kê khai: 5 tệp, 0 tệp sai; chữ ký hợp lệ (Ledger Seal IT-EX, SHA-256 9b86…)
Chuỗi 49: 61 bản ghi (60 trong phạm vi), liên kết hợp lệ, không thiếu seq
Niêm phong: 3 lần, gốc khớp, chữ ký hợp lệ (CN=Ledger Seal IT-EX)
Dấu thời gian RFC 3161: 3 token hợp lệ (TSA: CN=Mission ledger TSA (dev)), sớm nhất 2026-10-23T02:12:15Z
Bản neo: 1/1 ngày khớp với bên độc lập (0 bản neo ngoài phạm vi gói)
Kết luận: HỢP LỆ
```

Gói mẫu dùng cho kiểm thử nằm ở `data/golden/ledger-canon/ledger-verify/`.

## Công cụ kiểm những gì

Trước tiên, công cụ kiểm phần kê khai. Mỗi tệp trong gói phải có SHA-256 đúng như `manifest.json` ghi, và chữ ký `manifest.sig` phải hợp lệ với chứng thư của khoá ký. Sau đó công cụ chạy năm bước của TL-01:

1. **Tính lại chuỗi băm.** Với từng bản ghi, công cụ tính lại `metadata_hash` theo RFC 8785 và Unicode NFC, rồi tính `entry_hash = SHA-256(prev_hash ‖ payload_hash ‖ metadata_hash)`. Trong mỗi đoạn, `seq_no` phải liên tục và `prev_hash` của bản ghi sau phải bằng `entry_hash` của bản ghi trước.
2. **Dựng lại gốc tenant.** Với mỗi lần niêm phong, công cụ dựng lại gốc tenant từ đầu chuỗi của các phân vùng. Lá là `SHA-256(0x01 ‖ entry_hash cuối)`, nút là `SHA-256(0x02 ‖ trái ‖ phải)`, và cây được chia theo RFC 6962. Đầu chuỗi ghi trong niêm phong phải trùng `entry_hash` của bản ghi tương ứng trong gói. Mỗi đoạn chuỗi phải kết thúc đúng ở một đầu chuỗi đã niêm phong.
3. **Kiểm đường đi Merkle.** Từ lá `SHA-256(0x01 ‖ gốc tenant)`, đường đi Merkle phải dẫn đúng tới gốc cell.
4. **Kiểm chữ ký và dấu thời gian.** Chữ ký ECDSA P-256 trên `gốc cell ‖ gốc cell trước ‖ window_end` phải hợp lệ. Token RFC 3161 phải có chữ ký CMS hợp lệ và message imprint bằng `SHA-256(gốc cell)`. Nếu có `--tsa-ca`, chuỗi chứng thư của TSA cũng được kiểm.
5. **Đối chiếu bản neo.** Nếu người kiểm cung cấp `--anchors`, gốc cell trong bản neo của bên độc lập phải trùng gốc cell trong gói.

## Định dạng gói `mission-ledger-bundle/1`

Gói là một tệp tar. Mọi tệp JSON trong gói đều được chuẩn hoá theo RFC 8785, nên có thể tính lại băm của chúng một cách tất định.

| Tệp | Nội dung |
|---|---|
| `manifest.json` | Định dạng, tenant, cell, thời điểm xuất, phạm vi lọc, số bản ghi trong phạm vi, số bản ghi chưa niêm phong (không đưa vào gói), khoá ký, danh sách tệp kèm SHA-256 và kích thước. |
| `manifest.sig` | Chữ ký ECDSA P-256 dạng ASN.1 DER trên SHA-256 của `manifest.json`. |
| `entries.json` | `chains[]`, mỗi phần tử có `chainPartition` và `segments[]`. Mỗi đoạn là các bản ghi liên tục `seq_no`, đi từ một bản ghi trong phạm vi tới đầu chuỗi của lần niêm phong chứa bản ghi đó. Bản ghi có `inScope: false` chỉ có mặt để nối chuỗi và không mang `metadata`. |
| `seals.json` | Các niêm phong mức tenant, gồm `ranges[]` (`p`, `from`, `to`, `tip`), `tenantRoot`, `cellLeafIndex`, `cellPath[]` (`side` là TRAI hoặc PHAI, `hash`), và `cellSeal` (gốc cell, gốc cell trước, chữ ký, chứng thư, token TSA). |
| `anchors.json` | Biên nhận neo của các niêm phong cell có trong gói. |
| `verify-run.json` | Lần kiểm tra toàn vẹn đính kèm. Đó là lần người dùng chỉ định, hoặc lần hoàn tất gần nhất. |
| `certs/<sha256>.pem` | Chứng thư của khoá niêm phong và của khoá ký kê khai. |

Băm được ghi dạng hex chữ thường. Chữ ký và token được ghi dạng base64. Thời điểm có dạng `YYYY-MM-DDThh:mm:ss.ffffffZ`.

Một số giới hạn hiện tại cần lưu ý. Thứ nhất, `payload_hash` chỉ được đối chiếu như một giá trị có sẵn, vì tải trọng sự kiện nằm ở module nguồn và không có trong gói. Muốn kiểm tải trọng, người kiểm phải đối chiếu với dữ liệu nguồn. Thứ hai, cột `metadata` (trạng thái trước và sau rút gọn) không nằm trong `metadata_hash` theo TL-03, nên công cụ không chứng minh được rằng cột này chưa bị sửa.
