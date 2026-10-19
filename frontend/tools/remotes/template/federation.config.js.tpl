const { withNativeFederation, shareAll } = require('@angular-architects/native-federation/config');

// Sinh bởi scripts/new-remote.sh (WEB-015) theo mẫu fe-task.
module.exports = withNativeFederation({
  name: '__PROJECT__',

  exposes: {
    './routes': './projects/__PROJECT__/src/app/__SHORT__.routes.ts',
    './widgets': './projects/__PROJECT__/src/widgets/index.ts',
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
  ],

  features: {
    // Thư viện dùng chung mà remote cần thì shell cũng phải dùng ít nhất một lần (NG0203)
    ignoreUnusedDeps: true,
  },
});
