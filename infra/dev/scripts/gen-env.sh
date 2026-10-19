#!/bin/bash
# Sinh infra/dev/.env từ .env.example với mật khẩu ngẫu nhiên (chạy một lần trên server).
cd "$(dirname "$0")/.."
[ -f .env ] && { echo ".env đã tồn tại, không ghi đè"; exit 0; }
gen() { tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24; }
while IFS= read -r line; do
  if [[ "$line" =~ ^([A-Z_]+)=__GENERATE__$ ]]; then echo "${BASH_REMATCH[1]}=$(gen)"; else echo "$line"; fi
done < .env.example > .env
chmod 600 .env
echo "Đã tạo .env (chmod 600)"
