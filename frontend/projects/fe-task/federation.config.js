const { withNativeFederation, shareAll } = require('@angular-architects/native-federation/config');

module.exports = withNativeFederation({
  name: 'fe-task',

  exposes: {
    './routes': './projects/fe-task/src/app/task.routes.ts',
    './plan-routes': './projects/fe-task/src/app/plan.routes.ts',
    './widgets': './projects/fe-task/src/widgets/index.ts',
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
    // Add further packages you don't need at runtime
  ],

  // Please read our FAQ about sharing libs:
  // https://shorturl.at/jmzH0

  features: {
    // New feature for more performance and avoiding
    // issues with node libs. Comment this out to
    // get the traditional behavior:
    ignoreUnusedDeps: true,
  },
});
