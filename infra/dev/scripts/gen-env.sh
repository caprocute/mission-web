#!/bin/bash
# Sinh infra/dev/.env từ .env.example với mật khẩu ngẫu nhiên (chạy một lần trên server).
cd "$(dirname "$0")/.."
[ -f .env ] && { echo ".env đã tồn tại, không ghi đè"; exit 0; }
# Khoá *_B64 là base64 của 32 byte ngẫu nhiên (khoá AES-256, HMAC), các khoá bí mật khác là chuỗi chữ số 24 ký tự.
gen() { tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24; }
while IFS= read -r line; do
  # Khoá bí mật (PASSWORD, SECRET, TOKEN, PIN, API_KEY) để trống <FILL_IN> thì sinh ngẫu nhiên; địa chỉ, client id
  # của hệ ngoài để rỗng cho người dựng tự điền (giá trị <FILL_IN> thô làm `source .env` của các script lỗi cú pháp).
  if [[ "$line" =~ ^([A-Z0-9_]+)=(__GENERATE__|\<FILL_IN\>)$ ]]; then
    key="${BASH_REMATCH[1]}" val="${BASH_REMATCH[2]}"
    if [[ "${key}" == *_B64 ]]; then
      echo "${key}=$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
    elif [[ "${val}" == "__GENERATE__" || "${key}" =~ (PASSWORD|SECRET|SECRET_KEY|TOKEN|PIN|API_KEY)(_DEFAULT)?$ ]]; then
      echo "${key}=$(gen)"
    else
      echo "${key}="
    fi
  else
    echo "$line"
  fi
done < .env.example > .env
chmod 600 .env
echo "Đã tạo .env (chmod 600)"
