# Hành trình đầu cuối trên hệ thống thật

Thư mục này chứa bộ hành trình đầu cuối của Mission, tức khoảng 40 luồng nghiệp vụ chạy trên trình duyệt thật với hệ thống thật (TL-01 chương 18, tiêu chí J5). Chiến lược kiểm thử, các môi trường, tài khoản thử và tiêu chí "hành trình đạt" nằm ở `wiki/11-kiem-thu-va-chat-luong.md`, mục "Chiến lược kiểm thử toàn hệ thống".

## Danh mục

`danh-muc.json` là danh mục máy đọc được do QA-01 chốt. Mỗi phần tử của `hanhTrinh` có các trường sau:

| Trường                | Ý nghĩa                                                                              |
| --------------------- | ------------------------------------------------------------------------------------ |
| `ma`                  | mã cục bộ `HT-xx` của bộ kiểm thử; không phải mã thiết kế, không in lên màn hình     |
| `ten`                 | tên nghiệp vụ của hành trình                                                         |
| `boKiemThu`           | task viết mã: `QA-04`, `QA-05`, `QA-06`, `QA-07`, hoặc `MOB` cho bộ kiểm thử di động |
| `nenTang`             | `web` hoặc `di-dong`                                                                 |
| `qt`, `scr`           | quy trình và màn hình theo baseline mục 11                                           |
| `tacNhan`, `taiKhoan` | tác nhân AC và tài khoản thử của realm dev `mission`                                 |
| `operationIds`        | thao tác API chính trong `contracts/openapi/modules/`                                |
| `suKien`              | sự kiện EVT phải xuất hiện trên Kafka                                                |
| `trangThaiCuoi`       | đối tượng và trạng thái chuẩn (baseline mục 9.1) cần đọc lại qua API                 |
| `caPhuDinh`           | ca bị máy chủ từ chối phải kiểm kèm                                                  |
| `cheDoCaoTuoi`        | hành trình phải chạy được ở chế độ Đảng viên cao tuổi                                |
| `trangThai`           | `chua_viet`, `dang_viet`, `dat` hoặc `tam_hoan`                                      |

QA-04 đến QA-07 chỉ đổi `trangThai` khi hành trình đã viết và chạy đạt. Nếu cần thêm, bỏ hay đổi hành trình, phải sửa cả `danh-muc.json` lẫn bảng trong wiki 11.

## Cấu trúc thư mục

| Thư mục    | Nội dung                                                               | Task  |
| ---------- | ---------------------------------------------------------------------- | ----- |
| `ho-tro/`  | đăng nhập theo vai trò, client API dựng dữ liệu, bộ kiểm sự kiện Kafka | QA-03 |
| `qt00-03/` | hành trình QT-00 đến QT-03                                             | QA-04 |
| `qt04-07/` | hành trình QT-04 đến QT-07                                             | QA-05 |
| `qt08-10/` | hành trình QT-08 đến QT-10                                             | QA-06 |
| `qt11-18/` | hành trình QT-11 đến QT-18                                             | QA-07 |

Mỗi tệp hành trình ghi mã `HT-xx` trong tên `test(...)` để reporter đối chiếu được với danh mục. Dữ liệu đầu vào được nạp bằng API từ `data/seed/e2e/`, không chèn thẳng vào CSDL, và không dùng dữ liệu cá nhân thật (SEC-52). Mật khẩu và khoá OTP của tài khoản thử chỉ đọc từ biến môi trường.

## Kiểm danh mục khớp hợp đồng

Chạy lệnh sau từ gốc repo. Lệnh kiểm mỗi `operationId` có trong hợp đồng, mỗi QT-00 đến QT-18 có hành trình web, và mỗi QT có ít nhất một hành trình chế độ cao tuổi. Lệnh cũng in các EVT chưa có hành trình nào phủ.

```bash
python3 - <<'PY'
import json, glob, yaml
d = json.load(open('frontend/e2e/hanh-trinh/danh-muc.json'))
ops = {o['operationId'] for f in glob.glob('contracts/openapi/modules/*.yaml')
       for m in (yaml.safe_load(open(f)).get('paths') or {}).values()
       for o in m.values() if isinstance(o, dict) and 'operationId' in o}
H = d['hanhTrinh']; web = [h for h in H if h['nenTang'] == 'web']; loi = []
loi += [f"{h['ma']}: không có {o}" for h in H for o in h['operationIds'] if o not in ops]
for q in (f'QT-{i:02d}' for i in range(19)):
    w = [h for h in web if q in h['qt']]
    if not w: loi.append(f'{q}: không có hành trình web')
    elif not any(h['cheDoCaoTuoi'] for h in w): loi.append(f'{q}: thiếu hành trình chế độ cao tuổi')
evt = {e for h in H for e in h['suKien']}
print(len(H), 'hành trình; EVT chưa phủ:', [f'EVT-{i:02d}' for i in range(1, 27) if f'EVT-{i:02d}' not in evt])
print('ĐẠT' if not loi else 'LỖI', *loi, sep='\n')
PY
```
