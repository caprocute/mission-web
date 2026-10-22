const { withNativeFederation, shareAll } = require('@angular-architects/native-federation/config');

// Plugin chuyên ngành Giáo dục (ADR-05 lớp 4, WEB-051), dựng từ mẫu remote của WEB-015. Shell chỉ
// nạp module `./plugin` khi UI Pack của tenant có plugin này (VT_PLUGIN trong src/plugin.ts).
module.exports = withNativeFederation({
  name: 'fe-plugin-giaoduc',

  exposes: {
    './plugin': './projects/fe-plugin-giaoduc/src/plugin.ts',
  },

  shared: {
    ...shareAll({ singleton: true, strictVersion: true, requiredVersion: 'auto' }),
  },

  skip: [
    'rxjs/ajax',
    'rxjs/fetch',
    'rxjs/testing',
    'rxjs/webSocket',
    'json-schema',
    // Preset theme chỉ dùng khi khởi tạo, không cần singleton; đóng gói kèm thay vì chia sẻ
    (p) => p.startsWith('@primeuix/themes'),
    // WEB-052: icon và kiểu của PrimeNG không chia sẻ riêng từng gói con. Gói `primeng/icons` re-export
    // hơn 60 gói con, mỗi gói con thành một tệp nên trang chủ phải tải thêm khoảng 60 tệp nhỏ;
    // bỏ qua để mỗi component PrimeNG đóng gói kèm đúng icon nó dùng (icon không giữ trạng thái).
    (p) =>
      p === 'primeng/icons' || p.startsWith('primeng/icons/') || p.startsWith('primeng/types/'),
  ],

  features: {
    // Thư viện dùng chung mà remote cần thì shell cũng phải dùng ít nhất một lần (NG0203)
    ignoreUnusedDeps: true,
  },
});
