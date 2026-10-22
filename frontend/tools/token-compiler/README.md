# Bộ biên dịch token UI Pack (WEB-004)

Công cụ dòng lệnh biên dịch token của UI Pack thành CSS custom properties (`compiledCss`) và chạy kiểm tra tĩnh trước khi đưa gói lên `public/ui-packs/` hoặc gửi duyệt. Công cụ dùng đúng mã `projects/design-system/src/lib/token-compiler.ts`; Node 22 chạy thẳng tệp TypeScript nhờ type stripping, nên công cụ, trình duyệt (`tokensToCss`) và kiểm thử Vitest cho cùng một kết quả. Không dùng thêm thư viện nào (Style Dictionary không cần cho phép biến đổi này).

```bash
node tools/token-compiler/compile.mjs --tat-ca                    # kiểm mọi gói trong projects/fe-shell/public/ui-packs
node tools/token-compiler/compile.mjs --ghi --tat-ca              # kiểm rồi ghi lại compiledCss vào từng gói
node tools/token-compiler/compile.mjs duong-dan/pack.json         # kiểm một gói
node tools/token-compiler/compile.mjs tools/token-compiler/fixtures/tuong-phan-thap.json   # gói mẫu bị từ chối
```

Mã thoát 1 khi một gói không đạt hoặc khi `compiledCss` đã ghi trong gói lệch với token hiện tại. Sửa token thì chạy lại với `--ghi`.

## Kiểm tra

Quy tắc trùng bộ kiểm của máy chủ (`UiPackStaticChecks`, CORE-08-10), xem wiki trang 03 mục "Kiểm tra tĩnh UI Pack phía máy chủ":

- Tương phản chữ trên nền ≥ 4,5 : 1. Cặp suy ra từ tên token trong `tokens.color`: `onX`/`on-X` trên `X`; `text`, `textX`, `text-…` trên mọi `surface*`, `background*` cùng nhóm.
- Chế độ cao tuổi (`modes.elderly` hoặc `tokens.$extensions.modes.elderly`) sau khi gộp ghi đè: tương phản ≥ 7 : 1, chữ thân ≥ 17 px, vùng chạm khai báo ≥ 48 px.
- Chữ thân chế độ thường 12–16 px, bo góc ≤ 16 px.
- Gói kèm `compiledCss` nén gzip ≤ 30 KB.

## Bộ token mẫu

| Tenant thử | Mục đích |
|---|---|
| `default` | Bộ mặc định theo nhận diện Mission |
| `tuong-phan-cao` | Tương phản cao: chữ đen trên nền trắng, màu nhấn đậm |
| `cao-tuoi` | Dành cho đơn vị có nhiều đảng viên cao tuổi: chữ 16 px, chế độ cao tuổi chữ 20 px, vùng chạm 56 px |
| `thuong-hieu-toi` | Màu thương hiệu tối (xanh đậm) thay màu nhận diện |

Muốn xem một bộ trên shell dev, đặt `"tenant": "<tên>"` trong `public/config.json`.
