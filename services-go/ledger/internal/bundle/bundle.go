// Package bundle định nghĩa gói xuất bằng chứng sổ cái (DV-09-05, BR-13-12) và thuật toán kiểm chứng ngoại
// tuyến năm bước của TL-01 "Công cụ kiểm chứng độc lập". Gói là tệp tar gồm các tệp JSON chuẩn hoá RFC 8785:
//
//	manifest.json   kê khai: định dạng, tenant, cell, phạm vi lọc, danh sách tệp kèm SHA-256, khoá ký
//	manifest.sig    chữ ký ECDSA P-256 (ASN.1 DER) trên SHA-256 của manifest.json bằng khoá của ledger-service
//	entries.json    các đoạn chuỗi liên tục: mỗi bản ghi trong phạm vi tới đầu chuỗi của lần niêm phong chứa nó
//	seals.json      niêm phong mức tenant (khoảng seq, đầu chuỗi, gốc tenant, đường đi cell) và niêm phong cell
//	anchors.json    biên nhận neo của các niêm phong cell
//	verify-run.json lần kiểm tra toàn vẹn đính kèm (có thể rỗng)
//	certs/*.pem     chứng thư khoá niêm phong
//
// Gói chỉ chứa bản ghi đã niêm phong; bản ghi trong phạm vi chưa niêm phong được đếm ở kê khai.
// Package chỉ phụ thuộc canon và proof, không gọi mạng hay CSDL.
package bundle

import (
	"archive/tar"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"path"
	"sort"
	"time"

	"mission/services-go/ledger/internal/canon"
	"mission/services-go/ledger/internal/proof"
)

// Format là mã định dạng gói.
const Format = "mission-ledger-bundle/1"

// Tên các tệp trong gói.
const (
	FileManifest  = "manifest.json"
	FileSignature = "manifest.sig"
	FileEntries   = "entries.json"
	FileSeals     = "seals.json"
	FileAnchors   = "anchors.json"
	FileVerifyRun = "verify-run.json"
)

// Record là một bản ghi sổ cái trong gói. Bản ghi ngoài phạm vi lọc (inScope=false) chỉ có mặt để nối
// chuỗi tới đầu chuỗi đã niêm phong và không mang metadata; MetadataSHA256 (băm cột metadata) luôn có để tính lại
// metadata_hash, và với bản ghi trong phạm vi, công cụ kiểm còn đối chiếu nó với metadata đi kèm.
type Record struct {
	SeqNo          int64          `json:"seqNo"`
	ID             string         `json:"id"`
	EventID        string         `json:"eventId"`
	EventType      string         `json:"eventType"`
	AggregateType  string         `json:"aggregateType"`
	AggregateID    string         `json:"aggregateId"`
	ActorID        *string        `json:"actorId"`
	ActorKind      string         `json:"actorKind"`
	OccurredAt     string         `json:"occurredAt"`
	RecordedAt     string         `json:"recordedAt"`
	PayloadHash    string         `json:"payloadHash"`
	MetadataSHA256 string         `json:"metadataSha256"`
	MetadataHash   string         `json:"metadataHash"`
	PrevHash       string         `json:"prevHash"`
	EntryHash      string         `json:"entryHash"`
	InScope        bool           `json:"inScope"`
	Metadata       map[string]any `json:"metadata,omitempty"`
}

// Segment là các bản ghi liên tục seq_no của một chuỗi.
type Segment struct {
	Records []Record `json:"records"`
}

// Chain là các đoạn của một phân vùng chuỗi, sắp theo seq_no.
type Chain struct {
	ChainPartition int16     `json:"chainPartition"`
	Segments       []Segment `json:"segments"`
}

// Entries là nội dung entries.json.
type Entries struct {
	Chains []Chain `json:"chains"`
}

// Step là một bước đường đi Merkle: side là vị trí nút anh em (TRAI, PHAI) như hợp đồng MerkleProofStep.
type Step struct {
	Side string `json:"side"`
	Hash string `json:"hash"`
}

// CellSeal là niêm phong cell trong gói.
type CellSeal struct {
	ID               string  `json:"id"`
	CellCode         string  `json:"cellCode"`
	WindowStart      string  `json:"windowStart"`
	WindowEnd        string  `json:"windowEnd"`
	CellRoot         string  `json:"cellRoot"`
	PrevCellRoot     string  `json:"prevCellRoot"`
	SignerKeyID      string  `json:"signerKeyId"`
	SignerCertSHA256 string  `json:"signerCertSha256"`
	Signature        []byte  `json:"signature"`
	TSAToken         []byte  `json:"tsaToken"`
	TSAGenTime       *string `json:"tsaGenTime"`
	TSALate          bool    `json:"tsaLate"`
}

// TenantSeal là niêm phong mức tenant trong gói.
type TenantSeal struct {
	ID             string             `json:"id"`
	WindowStart    string             `json:"windowStart"`
	WindowEnd      string             `json:"windowEnd"`
	EntryCount     int64              `json:"entryCount"`
	TenantRoot     string             `json:"tenantRoot"`
	PrevTenantRoot *string            `json:"prevTenantRoot"`
	Ranges         []proof.ChainRange `json:"ranges"`
	CellLeafIndex  int                `json:"cellLeafIndex"`
	CellPath       []Step             `json:"cellPath"`
	Cell           CellSeal           `json:"cellSeal"`
}

// Anchor là một biên nhận neo.
type Anchor struct {
	CellSealID string `json:"cellSealId"`
	Target     string `json:"anchorTarget"`
	Receipt    []byte `json:"receipt"`
	AnchoredAt string `json:"anchoredAt"`
}

// FileEntry là một tệp trong kê khai.
type FileEntry struct {
	Path   string `json:"path"`
	SHA256 string `json:"sha256"`
	Size   int64  `json:"size"`
}

// Manifest là nội dung manifest.json.
type Manifest struct {
	Format           string         `json:"format"`
	TenantID         string         `json:"tenantId"`
	CellCode         string         `json:"cellCode"`
	CreatedAt        string         `json:"createdAt"`
	Filter           map[string]any `json:"filter"`
	EntriesInScope   int64          `json:"entriesInScope"`
	UnsealedExcluded int64          `json:"unsealedExcluded"`
	SignerKeyID      string         `json:"signerKeyId"`
	SignerCertSHA256 string         `json:"signerCertSha256"`
	Files            []FileEntry    `json:"files"`
}

// Bundle là gói đã dựng hoặc đã đọc.
type Bundle struct {
	Manifest  Manifest
	Signature []byte
	Entries   Entries
	Seals     []TenantSeal
	Anchors   []Anchor
	VerifyRun map[string]any
	Certs     map[string][]byte // tên tệp → DER
	files     map[string][]byte
}

// StepsOf đổi đường đi Merkle sang dạng của gói.
func StepsOf(steps []proof.Step) []Step {
	out := make([]Step, len(steps))
	for i, s := range steps {
		side := "PHAI"
		if s.Left {
			side = "TRAI"
		}
		out[i] = Step{Side: side, Hash: hex.EncodeToString(s.Sibling[:])}
	}
	return out
}

func stepsFrom(steps []Step) ([]proof.Step, error) {
	out := make([]proof.Step, len(steps))
	for i, s := range steps {
		h, err := hash32(s.Hash)
		if err != nil {
			return nil, err
		}
		if s.Side != "TRAI" && s.Side != "PHAI" {
			return nil, fmt.Errorf("vị trí nút %q không hợp lệ", s.Side)
		}
		out[i] = proof.Step{Sibling: h, Left: s.Side == "TRAI"}
	}
	return out, nil
}

func hash32(s string) ([32]byte, error) {
	b, err := hex.DecodeString(s)
	if err != nil || len(b) != 32 {
		return [32]byte{}, fmt.Errorf("băm %q không phải 32 byte hex", s)
	}
	return [32]byte(b), nil
}

// ManifestSigner ký kê khai bằng khoá của ledger-service (khoá niêm phong ở dev; HSM ở production).
type ManifestSigner struct {
	KeyID   string
	CertDER []byte
	Sign    func(msg []byte) ([]byte, error)
}

// Write dựng gói tar: chuẩn hoá từng tệp JSON, kê khai SHA-256, ký kê khai. certs là chứng thư DER của
// các khoá niêm phong; chứng thư khoá ký kê khai luôn được thêm.
func Write(w io.Writer, b *Bundle, signer ManifestSigner, certs [][]byte) error {
	files := map[string][]byte{}
	add := func(name string, v any) error {
		raw, err := canon.CanonicalizeValue(v)
		if err != nil {
			return fmt.Errorf("bundle: chuẩn hoá %s: %w", name, err)
		}
		files[name] = raw
		return nil
	}
	if err := add(FileEntries, b.Entries); err != nil {
		return err
	}
	seals := b.Seals
	if seals == nil {
		seals = []TenantSeal{}
	}
	if err := add(FileSeals, seals); err != nil {
		return err
	}
	anchors := b.Anchors
	if anchors == nil {
		anchors = []Anchor{}
	}
	if err := add(FileAnchors, anchors); err != nil {
		return err
	}
	vr := b.VerifyRun
	if vr == nil {
		vr = map[string]any{}
	}
	if err := add(FileVerifyRun, vr); err != nil {
		return err
	}
	for _, der := range append(certs, signer.CertDER) {
		sum := sha256.Sum256(der)
		files["certs/"+hex.EncodeToString(sum[:])+".pem"] = pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	}
	names := make([]string, 0, len(files))
	for n := range files {
		names = append(names, n)
	}
	sort.Strings(names)
	m := b.Manifest
	m.Format = Format
	m.Files = nil
	for _, n := range names {
		sum := sha256.Sum256(files[n])
		m.Files = append(m.Files, FileEntry{Path: n, SHA256: hex.EncodeToString(sum[:]), Size: int64(len(files[n]))})
	}
	certSHA := sha256.Sum256(signer.CertDER)
	m.SignerKeyID, m.SignerCertSHA256 = signer.KeyID, hex.EncodeToString(certSHA[:])
	raw, err := canon.CanonicalizeValue(m)
	if err != nil {
		return err
	}
	sig, err := signer.Sign(raw)
	if err != nil {
		return fmt.Errorf("bundle: ký kê khai: %w", err)
	}
	b.Manifest = m
	b.Signature = sig
	tw := tar.NewWriter(w)
	mtime := time.Unix(0, 0)
	if t, err := time.Parse(time.RFC3339Nano, m.CreatedAt); err == nil {
		mtime = t
	}
	put := func(name string, body []byte) error {
		if err := tw.WriteHeader(&tar.Header{Name: name, Mode: 0o444, Size: int64(len(body)), ModTime: mtime,
			Typeflag: tar.TypeReg, Format: tar.FormatPAX}); err != nil {
			return err
		}
		_, err := tw.Write(body)
		return err
	}
	if err := put(FileManifest, raw); err != nil {
		return err
	}
	if err := put(FileSignature, sig); err != nil {
		return err
	}
	for _, n := range names {
		if err := put(n, files[n]); err != nil {
			return err
		}
	}
	return tw.Close()
}

// MaxFileSize giới hạn một tệp trong gói khi đọc (chống gói độc làm cạn bộ nhớ).
const MaxFileSize = 4 << 30

// Read đọc gói tar. Lỗi cấu trúc (thiếu tệp, JSON hỏng) trả lỗi; sai lệch nội dung để Verify báo.
func Read(r io.Reader) (*Bundle, error) {
	b := &Bundle{Certs: map[string][]byte{}, files: map[string][]byte{}}
	tr := tar.NewReader(r)
	for {
		h, err := tr.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return nil, fmt.Errorf("gói không đọc được: %w", err)
		}
		if h.Typeflag != tar.TypeReg {
			continue
		}
		name := path.Clean(h.Name)
		if h.Size > MaxFileSize {
			return nil, fmt.Errorf("tệp %s quá lớn", name)
		}
		body, err := io.ReadAll(io.LimitReader(tr, MaxFileSize))
		if err != nil {
			return nil, err
		}
		b.files[name] = body
	}
	need := func(name string, v any) error {
		raw, ok := b.files[name]
		if !ok {
			return fmt.Errorf("gói thiếu tệp %s", name)
		}
		if v == nil {
			return nil
		}
		dec := json.NewDecoder(bytes.NewReader(raw))
		dec.UseNumber()
		if err := dec.Decode(v); err != nil {
			return fmt.Errorf("tệp %s không phải JSON hợp lệ: %w", name, err)
		}
		return nil
	}
	if err := need(FileManifest, &b.Manifest); err != nil {
		return nil, err
	}
	if b.Manifest.Format != Format {
		return nil, fmt.Errorf("định dạng gói %q không được hỗ trợ (cần %s)", b.Manifest.Format, Format)
	}
	b.Signature = b.files[FileSignature]
	if err := need(FileEntries, &b.Entries); err != nil {
		return nil, err
	}
	if err := need(FileSeals, &b.Seals); err != nil {
		return nil, err
	}
	if err := need(FileAnchors, &b.Anchors); err != nil {
		return nil, err
	}
	if raw, ok := b.files[FileVerifyRun]; ok {
		_ = json.Unmarshal(raw, &b.VerifyRun)
	}
	for name, body := range b.files {
		if path.Dir(name) != "certs" {
			continue
		}
		for rest := body; ; {
			var blk *pem.Block
			blk, rest = pem.Decode(rest)
			if blk == nil {
				break
			}
			if blk.Type == "CERTIFICATE" {
				sum := sha256.Sum256(blk.Bytes)
				b.Certs[hex.EncodeToString(sum[:])] = blk.Bytes
			}
		}
	}
	return b, nil
}
