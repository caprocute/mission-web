#!/usr/bin/env bash
# =============================================================================
# Kiểm các script bash của hạ tầng dev (INF-22) trước khi đẩy: máy chủ dev là CentOS 7 với bash 4.2, nơi
# `set -u` coi mảng rỗng là biến chưa gán ("${x[@]}", "${x[*]}" báo unbound variable; bash 4.4 trở lên thì không).
# Bash trên máy Mac (3.2) cũng lỗi như vậy. Script chạy được trên máy trạm, không cần máy chủ:
#
#   (1) shellcheck mọi infra/dev/scripts/*.sh (SHELLCHECK chỉ đường tới shellcheck, mặc định lệnh trong PATH,
#       không có thì dùng ảnh koalaman/shellcheck:stable);
#   (2) tìm mở rộng mảng chưa có dạng an toàn: phải viết ${x[@]+"${x[@]}"} hoặc ${x[*]-};
#   (3) `bash -n` từng script bằng bash 4.2;
#   (4) chạy thật bằng bash 4.2 các nhánh không cần máy chủ: gen-env.sh (tạo, --kiem khi đủ khoá và khi thiếu
#       khoá, --bo-sung), deploy-backend.sh IN_MO_DUN=1 (có và không có tham số, service lạ).
#
# Bash 4.2 lấy từ BASH42 (đường dẫn tới tệp bash 4.2 đã dựng sẵn), không có thì dùng ảnh docker bash:4.2.
#
# Cách dùng:
#   infra/dev/scripts/kiem-script.sh
#   BASH42=/duong/dan/bash-4.2/bash SHELLCHECK=/duong/dan/shellcheck infra/dev/scripts/kiem-script.sh
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${INFRA_DIR}/../.." && pwd)"

loi=0
fail() {
  echo "  LỖI: $*"
  loi=$((loi + 1))
}

scripts=()
for f in "${SCRIPT_DIR}"/*.sh; do
  scripts+=("${f}")
done

tmp="$(mktemp -d)"
trap 'rm -rf "${tmp:?}"' EXIT

# --- bash 4.2 -------------------------------------------------------------------------------------------------------
if [[ -n "${BASH42:-}" ]]; then
  RUN=(env)
  BASH_BIN="${BASH42}"
elif docker info >/dev/null 2>&1; then
  RUN=(docker run --rm -i -u "$(id -u):$(id -g)" -v "${REPO_ROOT}:${REPO_ROOT}" -v "${tmp}:${tmp}" bash:4.2 env)
  BASH_BIN=bash
else
  echo "Lỗi: cần BASH42 (đường dẫn tới bash 4.2) hoặc Docker để chạy ảnh bash:4.2" >&2
  exit 1
fi
ver="$(${RUN[@]+"${RUN[@]}"} "${BASH_BIN}" -c 'echo "${BASH_VERSINFO[0]}.${BASH_VERSINFO[1]}"')"
[[ "${ver}" == "4.2" ]] || { echo "Lỗi: bash dùng để kiểm là ${ver}, không phải 4.2" >&2; exit 1; }
# run42 [BIẾN=giá_trị...] <script|tuỳ chọn bash> [tham số...]
run42() {
  local vars=()
  while [[ $# -gt 0 && "$1" == *=* ]]; do
    vars+=("$1")
    shift
  done
  ${RUN[@]+"${RUN[@]}"} ${vars[@]+"${vars[@]}"} "${BASH_BIN}" "$@"
}

# --- (1) shellcheck -------------------------------------------------------------------------------------------------
echo "==> shellcheck ${#scripts[@]} script"
if [[ -n "${SHELLCHECK:-}" ]] || command -v shellcheck >/dev/null 2>&1; then
  SC=("${SHELLCHECK:-shellcheck}")
elif docker info >/dev/null 2>&1; then
  SC=(docker run --rm -v "${SCRIPT_DIR}:${SCRIPT_DIR}:ro" -w "${SCRIPT_DIR}" koalaman/shellcheck:stable)
else
  echo "Lỗi: không có shellcheck (đặt SHELLCHECK hoặc cài shellcheck) và không có Docker" >&2
  exit 1
fi
(cd "${SCRIPT_DIR}" && ${SC[@]+"${SC[@]}"} -x -S warning ${scripts[@]+"${scripts[@]}"}) || fail "shellcheck báo lỗi"

# --- (2) mở rộng mảng chưa an toàn với set -u của bash 4.2 ---------------------------------------------------------
echo "==> Mở rộng mảng có thể rỗng"
# Bỏ qua ${#x[@]} (độ dài), ${!x[@]} (khoá), dạng có toán tử như ${x[*]-}, ${x[*]:-…}, và phần trong dạng
# ${x[@]+"${x[@]}"}; mọi chỗ còn lại phải sửa.
bad="$(grep -nE '\$\{[A-Za-z_][A-Za-z0-9_]*\[[@*]\]\}' ${scripts[@]+"${scripts[@]}"} | grep -vE '^[^:]+:[0-9]+:[[:space:]]*#' |
  sed -E 's/\$\{([A-Za-z_][A-Za-z0-9_]*)\[@\]\+"\$\{\1\[@\]\}"\}//g' |
  grep -E '\$\{[A-Za-z_][A-Za-z0-9_]*\[[@*]\]\}' || true)"
if [[ -n "${bad}" ]]; then
  echo "${bad}" | sed "s|^${SCRIPT_DIR}/|    |"
  fail "viết lại theo dạng an toàn ở đầu tệp này"
fi

# --- (3) cú pháp với bash 4.2 ---------------------------------------------------------------------------------------
echo "==> bash ${ver} -n"
for f in ${scripts[@]+"${scripts[@]}"}; do
  run42 -n "${f}" || fail "cú pháp: ${f##*/}"
done

# --- (4) chạy thật các nhánh không cần máy chủ ----------------------------------------------------------------------
# expect <mô tả> <mã thoát mong đợi> <chuỗi phải có trong đầu ra> lệnh...
expect() {
  local mota="$1" ma="$2" chuoi="$3" out rc=0
  shift 3
  out="$("$@" 2>&1)" || rc=$?
  if [[ "${rc}" != "${ma}" ]]; then
    fail "${mota}: mã thoát ${rc}, mong đợi ${ma}"
    echo "${out}" | tail -5 | sed 's/^/    /'
  elif [[ "${out}" == *"unbound variable"* ]]; then
    fail "${mota}: unbound variable"
    echo "${out}" | grep 'unbound variable' | sed 's/^/    /'
  elif [[ -n "${chuoi}" && "${out}" != *"${chuoi}"* ]]; then
    fail "${mota}: không thấy \"${chuoi}\" trong đầu ra"
    echo "${out}" | tail -5 | sed 's/^/    /'
  else
    echo "  ok  ${mota}"
  fi
}

echo "==> Chạy bằng bash ${ver}"
ex="${INFRA_DIR}/.env.example"
envf="${tmp}/env"
expect "gen-env.sh tạo .env mới" 0 "Đã tạo .env" \
  run42 GEN_ENV_FILE="${envf}" GEN_ENV_EXAMPLE="${ex}" "${SCRIPT_DIR}/gen-env.sh"
expect "gen-env.sh --kiem khi đủ khoá (mảng rỗng)" 0 "đủ khoá" \
  run42 GEN_ENV_FILE="${envf}" GEN_ENV_EXAMPLE="${ex}" "${SCRIPT_DIR}/gen-env.sh" --kiem
# Ba trường hợp để mỗi mảng trong gen-env.sh lần lượt rỗng: chỉ thiếu một khoá bí mật, chỉ còn OPA 2s, cả hai.
key="$(grep -m1 -E '^[A-Z0-9_]*PASSWORD=' "${ex}" | cut -d= -f1)"
grep -vE "^${key}=" "${envf}" >"${tmp}/thieu-khoa"
grep -vE "^OPA_CONNECT_TIMEOUT=" "${envf}" >"${tmp}/opa-2s"
echo "OPA_CONNECT_TIMEOUT=2s" >>"${tmp}/opa-2s"
grep -vE "^OPA_CONNECT_TIMEOUT=" "${tmp}/thieu-khoa" >"${tmp}/ca-hai"
echo "OPA_CONNECT_TIMEOUT=2s" >>"${tmp}/ca-hai"
for tc in "thieu-khoa|${key}: sinh ngẫu nhiên|Đã bổ sung 1 khoá, nâng 0 khoá OPA" \
  "opa-2s|OPA_CONNECT_TIMEOUT: nâng 2s lên 5s|Đã bổ sung 0 khoá, nâng 1 khoá OPA" \
  "ca-hai|${key}: sinh ngẫu nhiên|Đã bổ sung 1 khoá, nâng 1 khoá OPA"; do
  IFS='|' read -r ten kiem bosung <<<"${tc}"
  expect "gen-env.sh --kiem (${ten})" 0 "${kiem}" \
    run42 GEN_ENV_FILE="${tmp}/${ten}" GEN_ENV_EXAMPLE="${ex}" "${SCRIPT_DIR}/gen-env.sh" --kiem
  expect "gen-env.sh --bo-sung (${ten})" 0 "${bosung}" \
    run42 GEN_ENV_FILE="${tmp}/${ten}" GEN_ENV_EXAMPLE="${ex}" "${SCRIPT_DIR}/gen-env.sh" --bo-sung
  expect "gen-env.sh --kiem sau khi bổ sung (${ten})" 0 "đủ khoá" \
    run42 GEN_ENV_FILE="${tmp}/${ten}" GEN_ENV_EXAMPLE="${ex}" "${SCRIPT_DIR}/gen-env.sh" --kiem
done
expect "deploy-backend.sh IN_MO_DUN=1 không tham số" 0 "mission-core" \
  run42 IN_MO_DUN=1 "${SCRIPT_DIR}/deploy-backend.sh"
expect "deploy-backend.sh IN_MO_DUN=1 chỉ service Go (không mô-đun Maven)" 0 "" \
  run42 IN_MO_DUN=1 "${SCRIPT_DIR}/deploy-backend.sh" ledger-service
expect "deploy-backend.sh service lạ" 1 "service lạ" \
  run42 IN_MO_DUN=1 "${SCRIPT_DIR}/deploy-backend.sh" khong-co

if [[ "${loi}" -gt 0 ]]; then
  echo "==> ${loi} lỗi"
  exit 1
fi
echo "==> Tất cả đạt"
