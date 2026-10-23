#!/usr/bin/env bash
# INF-18 · Kho Git (GitOps, mã nguồn, Policy Pack; TL-04 bảng lịch sao lưu "Gương sang phía Nam + bundle hằng ngày",
# "Clone thử hằng tuần"). CHE_DO=guong: clone --mirror từng kho ở GIT_NGUON rồi push --mirror sang GIT_GUONG (chạy dày,
# thay cho đồng bộ liên tục khi máy chủ Git không tự đẩy gương). CHE_DO=bundle: git bundle create --all, bundle verify,
# đẩy lên s3://<BUCKET>/bundle/<kho>/<ngày>.bundle (Object Lock 1 năm); chủ nhật thêm clone thử từ bản gương và từ bundle,
# so toàn bộ ref với kho gốc.
# Biến: GIT_NGUON, GIT_GUONG, KHO ("nhóm/kho …"), GIT_USER, GIT_TOKEN_FILE, GIT_TOKEN_GUONG_FILE, CHE_DO, BUCKET, TAM,
# S3_ENDPOINT, S3_REGION, AWS_*, CELL, PUSHGATEWAY.
set -euo pipefail
. "${CFG:-/cfg}/chung.sh"
: "${GIT_NGUON:?cần GIT_NGUON}" "${KHO:?cần KHO}" "${CHE_DO:?cần CHE_DO (guong|bundle)}"
TAM=${TAM:-/tam}
ngay=$(ngay_hien_tai)
thu=$(date -u -d "$ngay" +%u)

# Mật khẩu (token) đưa cho git qua credential helper đọc tệp, không nằm trên dòng lệnh hay trong URL.
if [ -n "${GIT_TOKEN_FILE:-}" ]; then
  cat > /tmp/git-mat-khau <<'X'
#!/usr/bin/env bash
h=""
while IFS='=' read -r k v; do [ "$k" = host ] && h=$v; done
f=$GIT_TOKEN_FILE
[ -n "${GIT_TOKEN_GUONG_FILE:-}" ] && [ "$h" = "${GIT_GUONG#*://}" ] && f=$GIT_TOKEN_GUONG_FILE
echo "username=${GIT_USER:-sao-luu}"
echo "password=$(cat "$f")"
X
  chmod 0700 /tmp/git-mat-khau
  export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=credential.helper GIT_CONFIG_VALUE_0=/tmp/git-mat-khau
fi
export GIT_TERMINAL_PROMPT=0
refs() { git -C "$1" for-each-ref --format='%(objectname) %(refname)' | sort; }

loi=0 chi_so=""
for k in $KHO; do
  ten=${k//\//_}
  d="$TAM/$ten.git"
  rm -rf "$d"
  echo "== $k"
  if ! git clone -q --mirror "$GIT_NGUON/$k.git" "$d"; then
    echo "LỖI: không clone được $k" >&2; loi=1; chi_so+="backup_git_success{kho=\"$k\",che_do=\"$CHE_DO\"} 0"$'\n'; continue
  fi
  ok=1
  if [ "$CHE_DO" = guong ]; then
    git -C "$d" push -q --mirror "$GIT_GUONG/$k.git" || { echo "LỖI: không đẩy được gương $k" >&2; ok=0; }
  else
    b="$TAM/$ten.bundle"
    if git -C "$d" bundle create -q "$b" --all && git -C "$d" bundle verify -q "$b" >/dev/null; then
      s3 s3 cp --only-show-errors "$b" "s3://$BUCKET/bundle/$k/$ngay.bundle" || { echo "LỖI: không đẩy được bundle $k" >&2; ok=0; }
      if [ "$thu" = 7 ]; then
        # Clone thử hằng tuần: từ bundle và từ gương phía Nam, ref phải trùng kho gốc.
        rm -rf "$TAM/thu-b" "$TAM/thu-g"
        if ! git clone -q --mirror "$b" "$TAM/thu-b" || [ "$(refs "$TAM/thu-b")" != "$(refs "$d")" ]; then
          echo "LỖI: clone thử từ bundle $k không khớp kho gốc" >&2; ok=0
        fi
        if [ -n "${GIT_GUONG:-}" ] && { ! git clone -q --mirror "$GIT_GUONG/$k.git" "$TAM/thu-g" || [ "$(refs "$TAM/thu-g")" != "$(refs "$d")" ]; }; then
          echo "LỖI: clone thử từ gương $k không khớp kho gốc" >&2; ok=0
        fi
        rm -rf "$TAM/thu-b" "$TAM/thu-g"
        [ "$ok" = 1 ] && chi_so+="backup_git_clone_check_last_success_timestamp_seconds{kho=\"$k\"} $(bay_gio)"$'\n'
      fi
    else
      echo "LỖI: bundle $k hỏng" >&2; ok=0
    fi
    rm -f "$b"
  fi
  rm -rf "$d"
  [ "$ok" = 1 ] || loi=1
  chi_so+="backup_git_success{kho=\"$k\",che_do=\"$CHE_DO\"} $ok"$'\n'
  [ "$ok" = 1 ] && chi_so+="backup_git_last_success_timestamp_seconds{kho=\"$k\",che_do=\"$CHE_DO\"} $(bay_gio)"$'\n'
done
printf '# TYPE backup_git_success gauge\n%s' "$chi_so" | day_chi_so "sao-luu-git-$CHE_DO"
exit "$loi"
