#!/bin/bash
# Sinh infra/dev/.env từ .env.example với mật khẩu ngẫu nhiên (chạy một lần trên server hoặc máy thử).
# Giá trị chờ điền (<FILL_IN> hoặc __GENERATE__): khoá bí mật (tên chứa PASSWORD, SECRET, TOKEN, API_KEY, ACCESS_KEY
# hoặc tận cùng PIN) được sinh ngẫu nhiên chữ và số; KC_TEST_OTP_SECRET sinh theo base32 (TOTP); khoá tận cùng _C2_KEY sinh base64 của 32 byte; khoá còn lại (URL, đường
# dẫn, client id của dịch vụ ngoài đang tắt) để trống, vì giá trị "<FILL_IN>" làm `source .env` báo lỗi cú pháp.
cd "$(dirname "$0")/.."
[ -f .env ] && { echo ".env đã tồn tại, không ghi đè"; exit 0; }
gen() { tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24; }
gen32() { tr -dc 'A-Z2-7' </dev/urandom | head -c 32; }
while IFS= read -r line; do
  if [[ "$line" =~ ^([A-Z0-9_]+)=(__GENERATE__|\<FILL_IN\>)$ ]]; then
    k=${BASH_REMATCH[1]}
    if [[ "$k" == KC_TEST_OTP_SECRET ]]; then echo "$k=$(gen32)"
    elif [[ "$k" =~ _C2_KEY$ ]]; then echo "$k=$(head -c 32 /dev/urandom | base64)"
    elif [[ "$k" =~ _URL$ ]]; then echo "$k="
    elif [[ "$k" =~ (PASSWORD|SECRET|TOKEN|PIN$|API_KEY|ACCESS_KEY) ]]; then echo "$k=$(gen)"
    else echo "$k="; fi
  else
    echo "$line"
  fi
done < .env.example > .env
chmod 600 .env
echo "Đã tạo .env (chmod 600)"
