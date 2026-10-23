#!/usr/bin/env bash
# =============================================================================
# Sinh và bổ sung infra/dev/.env của máy chủ dev (INF-22). Không bao giờ in giá trị, không đè giá trị đã có.
#
#   scripts/gen-env.sh             # chưa có .env: tạo từ .env.example, sinh ngẫu nhiên mọi bí mật (<FILL_IN>,
#                                  # __GENERATE__); đã có .env: không làm gì (giữ hành vi cũ)
#   scripts/gen-env.sh --kiem      # chỉ in TÊN khoá sẽ được bổ sung, không ghi
#   scripts/gen-env.sh --bo-sung   # nối vào .env đã có các khoá của .env.example còn thiếu (hoặc còn để
#                                  # <FILL_IN>): bí mật sinh ngẫu nhiên, khoá thường lấy giá trị mẫu
#
# Quy tắc bổ sung:
# - Bí mật (tên chứa PASSWORD, SECRET, hoặc kết thúc bằng _PIN, TOKEN) sinh ngẫu nhiên 32 ký tự chữ số; *OTP_SECRET
#   sinh base32 (Keycloak TOTP). Mật khẩu chỉ nằm trong .env (AGENTS.md quy tắc 4), không có mặc định trong compose.
# - Thông tin của hệ thống ngoài (ESIGN_MYSIGN_*, ESIGN_S3_*, ESIGN_HSM_LIBRARY, ESIGN_VIETTEL_CA_*, ESIGN_GOV_CA_*,
#   SMTP_*, SMS_*, PUSH_*) không sinh được: để trống, dịch vụ dùng chúng giữ chế độ giả lập cho tới khi điền tay.
# - Khoá trùng trong .env.example chỉ được bổ sung một lần.
# - POSTGRES_USER, POSTGRES_PASSWORD thiếu mà .env có PG_SUPERUSER, PG_SUPERUSER_PASSWORD (tên khoá của .env máy
#   chủ cũ) thì lấy cùng giá trị đó, vì Postgres đã khởi tạo với mật khẩu ấy; sinh mới sẽ khoá ngoài chính mình.
# - DB_ROLE_PASSWORD_DEFAULT thiếu mà .env có MISSION_DB_PASSWORD (compose cũ dùng khoá này cho role apicurio,
#   registry…) thì lấy cùng giá trị, vì các role đó đã tạo với mật khẩu ấy.
# - OPA_CONNECT_TIMEOUT, OPA_READ_TIMEOUT còn để 2s (mặc định cũ) được nâng lên 5s: qua đường hầm OPA mất
#   1,8–2,5 s nên 2s vẫn bị 403 fail-closed.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

MODE="${1:-tao}"
# trien-khai-lai.sh plan chạy bản script của máy trạm trên .env máy chủ: GEN_ENV_FILE, GEN_ENV_EXAMPLE chỉ đường.
EXAMPLE="${GEN_ENV_EXAMPLE:-.env.example}"
ENV="${GEN_ENV_FILE:-.env}"

gen() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32 || true; }
gen_b32() { LC_ALL=C tr -dc 'A-Z2-7' </dev/urandom | head -c 32 || true; }

is_secret() {
  case "$1" in
    ESIGN_MYSIGN_* | ESIGN_S3_* | ESIGN_HSM_LIBRARY | ESIGN_VIETTEL_CA_* | ESIGN_GOV_CA_* | SMTP_* | SMS_* | PUSH_*) return 1 ;;
    *PASSWORD* | *SECRET* | *_PIN | *TOKEN | *_C2_KEY) return 0 ;;
    *) return 1 ;;
  esac
}

# Giá trị cho khoá $1 với giá trị mẫu $2 (đọc .env hiện có để ánh xạ PG_SUPERUSER*).
value_for() {
  local key="$1" sample="$2" mapped
  case "${key}" in
    POSTGRES_USER) mapped="PG_SUPERUSER" ;;
    POSTGRES_PASSWORD) mapped="PG_SUPERUSER_PASSWORD" ;;
    DB_ROLE_PASSWORD_DEFAULT) mapped="MISSION_DB_PASSWORD" ;;
    *) mapped="" ;;
  esac
  if [[ -n "${mapped}" && -f "${ENV}" ]] && grep -qE "^${mapped}=.+" "${ENV}"; then
    grep -E "^${mapped}=" "${ENV}" | tail -1 | cut -d= -f2-
  elif [[ "${sample}" == "<FILL_IN>" || "${sample}" == "__GENERATE__" ]]; then
    if [[ "${key}" == *OTP_SECRET ]]; then
      gen_b32
    elif [[ "${key}" == *_C2_KEY ]]; then
      # Khoá mã hoá dạng base64 của đúng 32 byte (REGISTRY_C2_KEY, REGISTRY_ROUTING_COOKIE_C2_KEY của tenant-registry)
      head -c 32 /dev/urandom | base64
    elif is_secret "${key}"; then
      gen
    fi
  else
    printf '%s' "${sample}"
  fi
}

# Khoá của .env.example mà .env thiếu hoặc còn để <FILL_IN>/__GENERATE__/trống khi mẫu là bí mật cần sinh.
missing_keys() {
  local key sample cur
  while IFS= read -r line; do
    [[ "${line}" =~ ^([A-Z0-9_]+)=(.*)$ ]] || continue
    key="${BASH_REMATCH[1]}"
    sample="${BASH_REMATCH[2]}"
    if ! grep -qE "^${key}=" "${ENV}"; then
      echo "${key}"
      continue
    fi
    cur="$(grep -E "^${key}=" "${ENV}" | tail -1 | cut -d= -f2-)"
    if [[ "${cur}" == "<FILL_IN>" || "${cur}" == "__GENERATE__" ]]; then
      echo "${key}"
    elif [[ -z "${cur}" && ( "${sample}" == "<FILL_IN>" || "${sample}" == "__GENERATE__" ) ]] && is_secret "${key}"; then
      echo "${key}"
    fi
  done <"${EXAMPLE}"
}

stale_opa() {
  grep -E '^OPA_(CONNECT|READ)_TIMEOUT=2s$' "${ENV}" | cut -d= -f1 || true
}

case "${MODE}" in
  tao)
    [[ -f "${ENV}" ]] && { echo ".env đã tồn tại, không ghi đè (dùng --bo-sung để thêm khoá còn thiếu)"; exit 0; }
    umask 077
    while IFS= read -r line; do
      if [[ "${line}" =~ ^([A-Z0-9_]+)=(.*)$ ]]; then
        echo "${BASH_REMATCH[1]}=$(value_for "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}")"
      else
        echo "${line}"
      fi
    done <"${EXAMPLE}" >"${ENV}"
    chmod 600 "${ENV}"
    echo "Đã tạo .env (chmod 600)"
    ;;
  --kiem | --bo-sung)
    [[ -f "${ENV}" ]] || { echo "chưa có .env; chạy scripts/gen-env.sh để tạo" >&2; exit 1; }
    mapfile -t keys < <(missing_keys | awk '!seen[$0]++')
    mapfile -t opa < <(stale_opa)
    if [[ ${#keys[@]} -eq 0 && ${#opa[@]} -eq 0 ]]; then
      echo "  .env đủ khoá."
      exit 0
    fi
    for k in ${keys[@]+"${keys[@]}"}; do
      sample="$(grep -E "^${k}=" "${EXAMPLE}" | head -1 | cut -d= -f2-)"
      how="giá trị mẫu"
      if [[ "${k}" == POSTGRES_USER || "${k}" == POSTGRES_PASSWORD ]] && grep -qE "^PG_SUPERUSER(_PASSWORD)?=.+" "${ENV}"; then
        how="lấy từ PG_SUPERUSER*"
      elif [[ "${k}" == DB_ROLE_PASSWORD_DEFAULT ]] && grep -qE "^MISSION_DB_PASSWORD=.+" "${ENV}"; then
        how="lấy từ MISSION_DB_PASSWORD"
      elif [[ "${sample}" == "<FILL_IN>" || "${sample}" == "__GENERATE__" ]]; then
        if is_secret "${k}"; then how="sinh ngẫu nhiên"; else how="để trống, điền tay khi dùng hệ thống ngoài"; fi
      fi
      printf '  %s: %s\n' "${k}" "${how}"
    done
    for k in ${opa[@]+"${opa[@]}"}; do
      printf '  %s: nâng 2s lên 5s\n' "${k}"
    done
    [[ "${MODE}" == "--kiem" ]] && exit 0
    tmp="$(mktemp "${ENV}.XXXXXX")"
    chmod 600 "${tmp}"
    # Bỏ dòng <FILL_IN>/__GENERATE__ còn sót và dòng OPA 2s; mọi dòng khác giữ nguyên thứ tự.
    # Khoá bí mật cần sinh mà đang để trống cũng bị bỏ dòng cũ để không trùng khoá.
    drop='^[A-Z0-9_]+=(<FILL_IN>|__GENERATE__)$|^OPA_(CONNECT|READ)_TIMEOUT=2s$'
    for k in ${keys[@]+"${keys[@]}"}; do
      drop+="|^${k}=$"
    done
    grep -vE "${drop}" "${ENV}" >"${tmp}" || true
    {
      echo ""
      echo "# Bổ sung bởi scripts/gen-env.sh --bo-sung ngày $(date +%F)"
      for k in ${keys[@]+"${keys[@]}"}; do
        sample="$(grep -E "^${k}=" "${EXAMPLE}" | head -1 | cut -d= -f2-)"
        echo "${k}=$(value_for "${k}" "${sample}")"
      done
      for k in ${opa[@]+"${opa[@]}"}; do
        echo "${k}=5s"
      done
    } >>"${tmp}"
    mv "${tmp}" "${ENV}"
    chmod 600 "${ENV}"
    echo "  Đã bổ sung ${#keys[@]} khoá, nâng ${#opa[@]} khoá OPA (không in giá trị)."
    ;;
  *)
    echo "Cách dùng: $0 [--kiem|--bo-sung]" >&2
    exit 1
    ;;
esac
