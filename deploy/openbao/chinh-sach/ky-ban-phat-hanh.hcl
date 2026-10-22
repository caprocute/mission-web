# Jenkins ký ảnh phát hành bằng cosign qua Transit (khoá không rời OpenBao, SEC-55).
path "transit/sign/cosign-release/*"   { capabilities = ["update"] }
path "transit/keys/cosign-release"     { capabilities = ["read"] }
