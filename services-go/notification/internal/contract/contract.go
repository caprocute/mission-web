// Package contract kiểm phản hồi HTTP với hợp đồng OpenAPI 3.1 trong contracts/openapi (API-first, ADR-32):
// thao tác phải có trong hợp đồng ở đúng đường dẫn và phương thức, mã trạng thái phải được khai (hoặc rơi vào
// default), và thân phản hồi phải hợp lược đồ JSON Schema 2020-12 của hợp đồng (có kiểm format).
// Dùng trong kiểm thử hợp đồng; không sửa hợp đồng.
package contract

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"

	"github.com/santhosh-tekuri/jsonschema/v6"
	"gopkg.in/yaml.v3"
)

// Spec là bộ hợp đồng đã nạp.
type Spec struct {
	root     string
	docs     map[string]any // url tệp → tài liệu
	compiler *jsonschema.Compiler
	mu       sync.Mutex
	cache    map[string]*jsonschema.Schema
}

// Load nạp mọi tệp YAML dưới dir (contracts/openapi).
func Load(dir string) (*Spec, error) {
	s := &Spec{root: dir, docs: map[string]any{}, cache: map[string]*jsonschema.Schema{}}
	c := jsonschema.NewCompiler()
	c.DefaultDraft(jsonschema.Draft2020)
	c.AssertFormat()
	err := filepath.Walk(dir, func(p string, info os.FileInfo, err error) error {
		if err != nil || info.IsDir() || filepath.Ext(p) != ".yaml" {
			return err
		}
		raw, err := os.ReadFile(p)
		if err != nil {
			return err
		}
		var y any
		if err := yaml.Unmarshal(raw, &y); err != nil {
			return fmt.Errorf("%s: %w", p, err)
		}
		js, err := json.Marshal(normalize(y))
		if err != nil {
			return fmt.Errorf("%s: %w", p, err)
		}
		doc, err := jsonschema.UnmarshalJSON(bytes.NewReader(js))
		if err != nil {
			return err
		}
		u := fileURL(p)
		s.docs[u] = doc
		return c.AddResource(u, doc)
	})
	if err != nil {
		return nil, err
	}
	s.compiler = c
	return s, nil
}

func fileURL(p string) string {
	abs, _ := filepath.Abs(p)
	return (&url.URL{Scheme: "file", Path: filepath.ToSlash(abs)}).String()
}

// normalize đổi map[string]any lồng (yaml.v3 đã dùng string key) và giữ nguyên giá trị khác.
func normalize(v any) any {
	switch t := v.(type) {
	case map[string]any:
		for k, x := range t {
			t[k] = normalize(x)
		}
		return t
	case []any:
		for i, x := range t {
			t[i] = normalize(x)
		}
		return t
	}
	return v
}

func escape(tok string) string {
	return strings.ReplaceAll(strings.ReplaceAll(tok, "~", "~0"), "/", "~1")
}

func lookup(doc any, ptr []string) (any, bool) {
	cur := doc
	for _, tok := range ptr {
		m, ok := cur.(map[string]any)
		if !ok {
			return nil, false
		}
		if cur, ok = m[tok]; !ok {
			return nil, false
		}
	}
	return cur, true
}

// follow giải $ref của một Response Object (có thể trỏ sang tệp khác).
func (s *Spec) follow(base string, ptr []string) (string, []string, map[string]any, error) {
	for i := 0; i < 5; i++ {
		v, ok := lookup(s.docs[base], ptr)
		if !ok {
			return "", nil, nil, fmt.Errorf("hợp đồng không có %s#/%s", base, strings.Join(ptr, "/"))
		}
		m, _ := v.(map[string]any)
		ref, isRef := m["$ref"].(string)
		if !isRef {
			return base, ptr, m, nil
		}
		bu, _ := url.Parse(base)
		ru, err := bu.Parse(ref)
		if err != nil {
			return "", nil, nil, err
		}
		frag := ru.Fragment
		ru.Fragment = ""
		base = ru.String()
		ptr = nil
		for _, tok := range strings.Split(strings.TrimPrefix(frag, "/"), "/") {
			if tok != "" {
				ptr = append(ptr, strings.ReplaceAll(strings.ReplaceAll(tok, "~1", "/"), "~0", "~"))
			}
		}
	}
	return "", nil, nil, fmt.Errorf("vòng $ref quá sâu")
}

// Validate kiểm một phản hồi. file là tệp hợp đồng (ví dụ "modules/platform.yaml"), path là đường dẫn mẫu
// trong hợp đồng (ví dụ "/registry/resolve"), method chữ thường. body rỗng thì chỉ kiểm mã trạng thái.
func (s *Spec) Validate(file, path, method string, status int, contentType string, body []byte) error {
	base := fileURL(filepath.Join(s.root, file))
	if _, ok := s.docs[base]; !ok {
		return fmt.Errorf("không có tệp hợp đồng %s", file)
	}
	op := []string{"paths", path, method}
	if _, ok := lookup(s.docs[base], op); !ok {
		return fmt.Errorf("hợp đồng %s không có thao tác %s %s", file, strings.ToUpper(method), path)
	}
	code := strconv.Itoa(status)
	respPtr := append(append([]string{}, op...), "responses", code)
	if _, ok := lookup(s.docs[base], respPtr); !ok {
		respPtr = append(append([]string{}, op...), "responses", "default")
		if _, ok := lookup(s.docs[base], respPtr); !ok || status < 400 {
			return fmt.Errorf("%s %s: mã %d không có trong hợp đồng", strings.ToUpper(method), path, status)
		}
	}
	rb, rp, resp, err := s.follow(base, respPtr)
	if err != nil {
		return err
	}
	content, _ := resp["content"].(map[string]any)
	if len(body) == 0 {
		if len(content) > 0 && status != 204 {
			return fmt.Errorf("%s %s %d: hợp đồng khai thân phản hồi nhưng phản hồi rỗng", strings.ToUpper(method), path, status)
		}
		return nil
	}
	ct := strings.TrimSpace(strings.Split(contentType, ";")[0])
	if _, ok := content[ct]; !ok {
		return fmt.Errorf("%s %s %d: kiểu nội dung %q không có trong hợp đồng", strings.ToUpper(method), path, status, ct)
	}
	ptr := append(append([]string{}, rp...), "content", ct, "schema")
	toks := make([]string, len(ptr))
	for i, t := range ptr {
		toks[i] = escape(t)
	}
	loc := rb + "#/" + strings.Join(toks, "/")
	sch, err := s.schema(loc)
	if err != nil {
		return err
	}
	inst, err := jsonschema.UnmarshalJSON(bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("thân phản hồi không phải JSON: %w", err)
	}
	if err := sch.Validate(inst); err != nil {
		return fmt.Errorf("%s %s %d lệch lược đồ hợp đồng: %v", strings.ToUpper(method), path, status, err)
	}
	return nil
}

// ValidateRequest kiểm thân yêu cầu với requestBody của thao tác.
func (s *Spec) ValidateRequest(file, path, method, contentType string, body []byte) error {
	base := fileURL(filepath.Join(s.root, file))
	toks := []string{"paths", path, method, "requestBody", "content", contentType, "schema"}
	if _, ok := lookup(s.docs[base], toks); !ok {
		return fmt.Errorf("hợp đồng không có requestBody %s cho %s %s", contentType, method, path)
	}
	esc := make([]string, len(toks))
	for i, t := range toks {
		esc[i] = escape(t)
	}
	sch, err := s.schema(base + "#/" + strings.Join(esc, "/"))
	if err != nil {
		return err
	}
	inst, err := jsonschema.UnmarshalJSON(bytes.NewReader(body))
	if err != nil {
		return err
	}
	return sch.Validate(inst)
}

func (s *Spec) schema(loc string) (*jsonschema.Schema, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if sch, ok := s.cache[loc]; ok {
		return sch, nil
	}
	sch, err := s.compiler.Compile(loc)
	if err != nil {
		return nil, fmt.Errorf("biên dịch lược đồ %s: %w", loc, err)
	}
	s.cache[loc] = sch
	return sch, nil
}
