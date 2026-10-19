// @ts-check
// WEB-001: cấu hình ESLint (flat config) cho toàn workspace Angular 21.
// Quy tắc dự án bám AGENTS.md quy tắc 5 (an toàn giao diện theo TL-01 mục CSP) và quy tắc 8 (Angular hiện đại).
const eslint = require('@eslint/js');
const tseslint = require('typescript-eslint');
const angular = require('angular-eslint');

/** Cấm API vô hiệu hoá sanitizer, cấm gán innerHTML/outerHTML, cấm NgModule mới (TS). */
const tsRestricted = [
  {
    selector: 'MemberExpression[property.name=/^bypassSecurityTrust/]',
    message: 'Cấm bypassSecurityTrust* (AGENTS.md quy tắc 5, TL-01 mục CSP).',
  },
  {
    selector: 'MemberExpression[property.name=/^(innerHTML|outerHTML)$/]',
    message:
      'Cấm dùng innerHTML/outerHTML (AGENTS.md quy tắc 5); dùng binding văn bản của Angular.',
  },
  {
    selector: 'CallExpression[callee.property.name="insertAdjacentHTML"]',
    message: 'Cấm insertAdjacentHTML (AGENTS.md quy tắc 5).',
  },
  {
    selector: 'Property[key.value=/^\\[(innerHTML|outerHTML)\\]$/]',
    message: 'Cấm host binding [innerHTML]/[outerHTML] (AGENTS.md quy tắc 5).',
  },
  {
    selector: 'Decorator[expression.callee.name="NgModule"]',
    message: 'Không tạo NgModule mới; dùng standalone component (AGENTS.md quy tắc 8).',
  },
];

/** Cấm binding [innerHTML]/[outerHTML] trong template. */
const templateRestricted = [
  {
    selector: 'BoundAttribute[name=/^(innerHTML|outerHTML)$/]',
    message: 'Cấm binding [innerHTML]/[outerHTML] (AGENTS.md quy tắc 5, TL-01 mục CSP).',
  },
];

module.exports = tseslint.config(
  {
    ignores: ['dist/**', 'out-tsc/**', '.angular/**', 'node_modules/**', 'coverage/**'],
  },
  {
    files: ['**/*.ts'],
    extends: [
      eslint.configs.recommended,
      ...tseslint.configs.recommended,
      ...tseslint.configs.stylistic,
      ...angular.configs.tsRecommended,
    ],
    processor: angular.processInlineTemplates,
    rules: {
      '@angular-eslint/directive-selector': [
        'error',
        { type: 'attribute', prefix: 'vt', style: 'camelCase' },
      ],
      '@angular-eslint/component-selector': [
        'error',
        { type: 'element', prefix: 'vt', style: 'kebab-case' },
      ],
      // Quy tắc 8: bắt buộc standalone, ưu tiên inject()
      '@angular-eslint/prefer-standalone': 'error',
      '@angular-eslint/prefer-inject': 'error',
      // Quy tắc 5: không eval, không new Function
      'no-eval': 'error',
      'no-implied-eval': 'error',
      'no-new-func': 'error',
      'no-restricted-syntax': ['error', ...tsRestricted],
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
    },
  },
  {
    files: ['**/*.html'],
    extends: [...angular.configs.templateRecommended, ...angular.configs.templateAccessibility],
    rules: {
      'no-restricted-syntax': ['error', ...templateRestricted],
      // pButton dựng nhãn từ thuộc tính label; nút chỉ có biểu tượng phải có aria-label
      '@angular-eslint/template/elements-content': ['error', { allowList: ['label', 'ariaLabel'] }],
    },
  },
  {
    // Tệp cấu hình Node (CommonJS) của Native Federation và chính tệp này
    files: ['**/*.js', '**/*.cjs'],
    extends: [eslint.configs.recommended],
    languageOptions: {
      sourceType: 'commonjs',
      globals: {
        require: 'readonly',
        module: 'writable',
        __dirname: 'readonly',
        process: 'readonly',
      },
    },
  },
);
