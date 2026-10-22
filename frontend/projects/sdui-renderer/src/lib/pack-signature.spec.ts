import { canonicalJson, signedContent, verifyPackSignature } from './pack-signature';
import { UiPack } from './ui-pack';

/**
 * Mẫu vàng WEB-009: gói do `UiPackCompiler.canonical` của mission-core chuẩn tắc hoá và ký bằng
 * `SHA256withECDSAinP1363Format` (khoá thử sinh một lần, không phải khoá nền tảng thật). Dùng để
 * chứng minh trình duyệt dựng lại đúng từng byte mà máy chủ đã ký.
 */
const GOLDEN_SIGNED_PACK = {
  keyId: 'k-gold',
  publicKey:
    'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEiOzfPCB6vP7sdwbhpo+IG11N+RSSq6+EYhCf49Q9Qjze/E4jzJX9DLl64dG/Afwyq/k2GZ26X3o2CP0eUsispQ==',
  canonical:
    '{"forms":{},"i18n":{"vi":{"home.notice":"Hạn nộp hồ sơ quý IV: 10/01/2027 \\"gấp\\" \\\\ tab\\there\\u001F 😀 / </>"}},"layouts":{"home":{"children":[{"component":"vt.widget.myTasksToday","props":{"enabled":true,"limit":7,"none":null,"ratio":1.5}}],"component":"vt.layout.grid"}},"pack":"tinh-mau/ui","plugins":[],"requires":{"registry":"^2.4.0"},"tokens":{"color":{"$type":"color","Zeta":{"$value":"#000"},"alpha":{"$value":"#fff"},"primary":{"$value":"#1a4fa0"}},"space":{"$value":8}},"version":"1.2.0"}',
  pack: {
    pack: 'tinh-mau/ui',
    version: '1.2.0',
    requires: {
      registry: '^2.4.0',
    },
    tokens: {
      color: {
        $type: 'color',
        primary: {
          $value: '#1a4fa0',
        },
        Zeta: {
          $value: '#000',
        },
        alpha: {
          $value: '#fff',
        },
      },
      space: {
        $value: 8,
      },
    },
    layouts: {
      home: {
        component: 'vt.layout.grid',
        children: [
          {
            component: 'vt.widget.myTasksToday',
            props: {
              limit: 7,
              enabled: true,
              ratio: 1.5,
              none: null,
            },
          },
        ],
      },
    },
    i18n: {
      vi: {
        'home.notice': 'Hạn nộp hồ sơ quý IV: 10/01/2027 "gấp" \\ tab\there\u001f 😀 / </>',
      },
    },
    plugins: [],
    forms: {},
    contentHash: '780894d12a762508ec364011fafa8fe1e64bb245e41b62e541c255ce04a47979',
    signature:
      'Bu8iBdWT0hJqs8dJvjMHnxq6AYRzvwvyEeNS/mz8SnITQ8/5+ba7N76AZI65uDO9OGiDeK481GlCko2UAW59hw==',
    publishedAt: '2026-10-22T10:00:00Z',
  },
} as const;

const golden = GOLDEN_SIGNED_PACK.pack as unknown as UiPack;
const keys = { [GOLDEN_SIGNED_PACK.keyId]: GOLDEN_SIGNED_PACK.publicKey };

describe('Chữ ký UI Pack (WEB-009)', () => {
  it('dựng lại đúng từng byte JSON chuẩn tắc mà máy chủ đã ký', () => {
    expect(new TextDecoder().decode(signedContent(golden))).toBe(GOLDEN_SIGNED_PACK.canonical);
  });

  it('JSON chuẩn tắc sắp khoá ở mọi mức và bỏ khoảng trắng', () => {
    expect(canonicalJson({ b: [{ z: 1, a: '\u001f' }], a: null, c: undefined })).toBe(
      '{"a":null,"b":[{"a":"\\u001F","z":1}]}',
    );
  });

  it('chấp nhận gói do máy chủ ký', async () => {
    expect(await verifyPackSignature(golden, keys, 'k-gold')).toBe('ok');
    expect(await verifyPackSignature(golden, keys)).toBe('ok');
  });

  it('từ chối gói bị sửa sau khi ký', async () => {
    const tampered = structuredClone(golden);
    tampered.layouts['home'].component = 'vt.layout.stack';
    expect(await verifyPackSignature(tampered, keys)).toBe('invalid');
    // sửa cả băm nội dung cho khớp vẫn không qua được chữ ký
    const rehashed = { ...tampered, contentHash: undefined };
    expect(await verifyPackSignature(rehashed, keys)).toBe('invalid');
  });

  it('từ chối chữ ký của khoá khác và gói không có chữ ký', async () => {
    const other = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, [
      'sign',
      'verify',
    ]);
    const spki = btoa(
      String.fromCharCode(
        ...new Uint8Array(await crypto.subtle.exportKey('spki', other.publicKey)),
      ),
    );
    expect(await verifyPackSignature(golden, { khac: spki })).toBe('invalid');
    expect(await verifyPackSignature({ ...golden, signature: undefined }, keys)).toBe('missing');
    expect(await verifyPackSignature({ ...golden, signature: 'không phải base64' }, keys)).toBe(
      'invalid',
    );
  });
});
