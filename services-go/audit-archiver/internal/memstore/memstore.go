// Package memstore là kho đối tượng trong bộ nhớ cho kiểm thử đơn vị: ghi không đè như bucket Object Lock.
package memstore

import (
	"bytes"
	"context"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	"mission/services-go/audit-archiver/internal/objstore"
)

// Store là kho trong bộ nhớ.
type Store struct {
	mu   sync.Mutex
	objs map[string][]byte
	// FailPut, nếu khác nil, được gọi trước mỗi lần ghi; trả lỗi để giả lập sự cố.
	FailPut func(key string) error
}

// New tạo kho rỗng.
func New() *Store { return &Store{objs: map[string][]byte{}} }

// PutLocked ghi đối tượng mới, không đè.
func (s *Store) PutLocked(_ context.Context, key string, data []byte, _ string) (bool, error) {
	if s.FailPut != nil {
		if err := s.FailPut(key); err != nil {
			return false, err
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if cur, ok := s.objs[key]; ok {
		if bytes.Equal(cur, data) {
			return false, nil
		}
		return false, fmt.Errorf("%w: %s", objstore.ErrConflict, key)
	}
	s.objs[key] = append([]byte(nil), data...)
	return true, nil
}

// Tamper sửa đối tượng bỏ qua khoá (giả lập kẻ tấn công có quyền ghi thẳng vào đĩa).
func (s *Store) Tamper(key string, fn func([]byte) []byte) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.objs[key] = fn(append([]byte(nil), s.objs[key]...))
}

// Remove xoá đối tượng bỏ qua khoá (giả lập mất đối tượng).
func (s *Store) Remove(key string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.objs, key)
}

// Get đọc đối tượng.
func (s *Store) Get(_ context.Context, key string) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	b, ok := s.objs[key]
	if !ok {
		return nil, objstore.ErrNotFound
	}
	return append([]byte(nil), b...), nil
}

// Exists kiểm đối tượng.
func (s *Store) Exists(_ context.Context, key string) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, ok := s.objs[key]
	return ok, nil
}

// CheckLocked luôn đạt.
func (s *Store) CheckLocked(context.Context, string, time.Duration) error { return nil }

// Keys trả mọi khoá theo thứ tự.
func (s *Store) Keys() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	ks := make([]string, 0, len(s.objs))
	for k := range s.objs {
		ks = append(ks, k)
	}
	sort.Strings(ks)
	return ks
}

// List liệt kê theo thứ tự từ điển.
func (s *Store) List(_ context.Context, prefix, startAfter string, fn func(string) error) error {
	for _, k := range s.Keys() {
		if strings.HasPrefix(k, prefix) && k > startAfter {
			if err := fn(k); err != nil {
				return err
			}
		}
	}
	return nil
}
