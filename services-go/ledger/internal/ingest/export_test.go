package ingest

import "context"

// ResumeUnlockedForTest gọi bước đọc lại partition mà Run chạy định kỳ.
func ResumeUnlockedForTest(ctx context.Context, c *Consumer) { c.resumeUnlocked(ctx) }
