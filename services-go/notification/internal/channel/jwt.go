package channel

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"math/big"
)

var b64 = base64.RawURLEncoding

// signJWT ký JWT RS256 (FCM, khoá RSA của tài khoản dịch vụ) hoặc ES256 (APNs, khoá P-256 tệp .p8).
func signJWT(key crypto.Signer, header, claims map[string]any) (string, error) {
	h, _ := json.Marshal(header)
	c, _ := json.Marshal(claims)
	input := b64.EncodeToString(h) + "." + b64.EncodeToString(c)
	sum := sha256.Sum256([]byte(input))
	var sig []byte
	switch k := key.(type) {
	case *rsa.PrivateKey:
		s, err := rsa.SignPKCS1v15(rand.Reader, k, crypto.SHA256, sum[:])
		if err != nil {
			return "", err
		}
		sig = s
	case *ecdsa.PrivateKey:
		r, s, err := ecdsa.Sign(rand.Reader, k, sum[:])
		if err != nil {
			return "", err
		}
		sig = append(pad32(r), pad32(s)...)
	default:
		return "", errors.New("channel: kiểu khoá không hỗ trợ")
	}
	return input + "." + b64.EncodeToString(sig), nil
}

func pad32(n *big.Int) []byte {
	b := n.Bytes()
	out := make([]byte, 32)
	copy(out[32-len(b):], b)
	return out
}

// ParsePrivateKey đọc khoá PEM PKCS#8 (FCM, APNs) hoặc PKCS#1 RSA.
func ParsePrivateKey(pemText string) (crypto.Signer, error) {
	blk, _ := pem.Decode([]byte(pemText))
	if blk == nil {
		return nil, errors.New("channel: khoá không phải PEM")
	}
	if k, err := x509.ParsePKCS8PrivateKey(blk.Bytes); err == nil {
		if s, ok := k.(crypto.Signer); ok {
			return s, nil
		}
	}
	if k, err := x509.ParsePKCS1PrivateKey(blk.Bytes); err == nil {
		return k, nil
	}
	if k, err := x509.ParseECPrivateKey(blk.Bytes); err == nil {
		return k, nil
	}
	return nil, errors.New("channel: không đọc được khoá")
}
