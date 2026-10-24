#!/bin/bash
# Đặt theme Mission tiếng Việt cho realm master (trang quản trị Keycloak), YC-TK-13.
# Realm master không nạp từ tệp JSON nên cấu hình bằng kcadm sau khi Keycloak chạy.
# Chạy trên server dev, trong thư mục infra/dev:  ./keycloak/cau-hinh-master.sh
# Chỉ đụng container mission-dev-keycloak; mật khẩu quản trị đọc từ .env (không in ra).
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
C=mission-dev-keycloak
docker exec "$C" /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 \
  --realm master --user "${KEYCLOAK_ADMIN:-admin}" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null
docker exec "$C" /opt/keycloak/bin/kcadm.sh update realms/master \
  -s adminTheme=mission -s accountTheme=mission -s loginTheme=mission -s emailTheme=mission \
  -s internationalizationEnabled=true -s 'supportedLocales=["vi","en"]' -s defaultLocale=vi
echo "Đã đặt theme mission và tiếng Việt cho realm master"
