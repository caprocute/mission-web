// Package sources loads and validates `contracts/events/outbox-sources.yaml`
// against `contracts/events/catalog.yaml` (NEN-022, ADR-03, TL-03).
package sources

import (
	"bufio"
	"errors"
	"fmt"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

var (
	// ErrUnregisteredSchema indicates the requested PostgreSQL schema is not in outbox-sources.yaml.
	ErrUnregisteredSchema = errors.New("sources: schema is not registered in outbox-sources.yaml")
	// ErrTopicNotAllowed indicates the topic is not permitted for the schema or does not exist in catalog.yaml.
	ErrTopicNotAllowed = errors.New("sources: topic is not permitted for schema in outbox-sources.yaml")
	// ErrValidationFailed indicates an inconsistency between outbox-sources.yaml and catalog.yaml.
	ErrValidationFailed = errors.New("sources: outbox-sources.yaml validation failed")

	schemaIdentRe = regexp.MustCompile(`^[a-z_][a-z0-9_]*$`)
)

// HeaderMapping describes how an outbox column or JSONB headers key maps to a Kafka header.
type HeaderMapping struct {
	Column   string
	JSONBKey string
	Header   string
}

// EventRouterConfig holds Debezium Outbox EventRouter field mapping defaults.
type EventRouterConfig struct {
	KeyField     string
	PayloadField string
	TopicField   string
	HeaderFields []HeaderMapping
}

// Defaults holds global Debezium outbox and pg_partman settings.
type Defaults struct {
	PublicationName  string
	HeartbeatTable   string
	RetentionDays    int
	RetentionHours   int
	PartmanRetention string
	EventRouter      EventRouterConfig
}

// NonOutboxTopic documents an event/technical topic that bypasses PostgreSQL outbox (`outbox: null`).
type NonOutboxTopic struct {
	Code   string
	Topic  string
	Reason string
}

// SourceSchema represents a single schema entry with `<schema>.outbox_event`.
type SourceSchema struct {
	Schema      string
	OutboxTable string
	Owner       string
	Database    string
	DevDatabase string
	Connector   string
	SlotName    string
	CDCRole     string
	Events      []string
	Topics      []string
}

// CatalogEntry represents an entry from `contracts/events/catalog.yaml` (`events` or `technicalTopics`).
type CatalogEntry struct {
	Code           string
	Name           string
	Topic          string
	Key            string
	Outbox         string // empty string when `outbox: null`
	Classification string
	Producer       string
	IsTechnical    bool
}

// Catalog represents the parsed `contracts/events/catalog.yaml`.
type Catalog struct {
	Version         int
	Compatibility   string
	Events          []CatalogEntry
	TechnicalTopics []CatalogEntry
	byTopic         map[string]CatalogEntry
	byCode          map[string]CatalogEntry
}

// Registry represents the parsed and indexed `contracts/events/outbox-sources.yaml`.
type Registry struct {
	Version         int
	Defaults        Defaults
	WildcardTopics  []string
	NonOutboxTopics []NonOutboxTopic
	Sources         []SourceSchema

	bySchema    map[string]SourceSchema
	wildcardSet map[string]struct{}
	allowedBySc map[string]map[string]struct{}
}

// LoadRegistry parses `outbox-sources.yaml` from disk.
func LoadRegistry(path string) (*Registry, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("sources: read %s: %w", path, err)
	}
	return ParseRegistry(string(data))
}

// LoadCatalog parses `catalog.yaml` from disk.
func LoadCatalog(path string) (*Catalog, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("sources: read %s: %w", path, err)
	}
	return ParseCatalog(string(data))
}

// LoadAndValidate loads both `outbox-sources.yaml` and `catalog.yaml` and cross-validates them.
func LoadAndValidate(sourcesPath, catalogPath string) (*Registry, *Catalog, error) {
	reg, err := LoadRegistry(sourcesPath)
	if err != nil {
		return nil, nil, err
	}
	cat, err := LoadCatalog(catalogPath)
	if err != nil {
		return nil, nil, err
	}
	if err := reg.ValidateAgainstCatalog(cat); err != nil {
		return nil, nil, err
	}
	return reg, cat, nil
}

// Schema returns the SourceSchema metadata for the given schema name.
func (r *Registry) Schema(schema string) (SourceSchema, bool) {
	if r == nil {
		return SourceSchema{}, false
	}
	s, ok := r.bySchema[strings.TrimSpace(schema)]
	return s, ok
}

// AllowedTopics returns the sorted list of topics allowed to be emitted from `<schema>.outbox_event`
// (schema-specific topics + wildcardTopics).
func (r *Registry) AllowedTopics(schema string) ([]string, error) {
	if r == nil {
		return nil, ErrUnregisteredSchema
	}
	allowed, ok := r.allowedBySc[strings.TrimSpace(schema)]
	if !ok {
		return nil, fmt.Errorf("%w: %q", ErrUnregisteredSchema, schema)
	}
	out := make([]string, 0, len(allowed))
	for t := range allowed {
		out = append(out, t)
	}
	sort.Strings(out)
	return out, nil
}

// ValidateSchemaAndTopic checks that `schema` is registered in `outbox-sources.yaml` and `topic` is permitted.
func (r *Registry) ValidateSchemaAndTopic(schema, topic string) error {
	if r == nil {
		return ErrUnregisteredSchema
	}
	trimmedSchema := strings.TrimSpace(schema)
	allowed, ok := r.allowedBySc[trimmedSchema]
	if !ok {
		return fmt.Errorf("%w: %q", ErrUnregisteredSchema, schema)
	}
	trimmedTopic := strings.TrimSpace(topic)
	if trimmedTopic == "" {
		return fmt.Errorf("%w: empty topic for schema %q", ErrTopicNotAllowed, trimmedSchema)
	}
	if _, ok := allowed[trimmedTopic]; !ok {
		return fmt.Errorf("%w: schema %q is not allowed to publish topic %q", ErrTopicNotAllowed, trimmedSchema, trimmedTopic)
	}
	return nil
}

// TableIncludeListForDatabase returns the comma-separated `table.include.list` for a logical database
// (e.g., `"tenant_data"`) in the order defined in `outbox-sources.yaml`, for use by INF-10 KafkaConnector generator.
func (r *Registry) TableIncludeListForDatabase(database string) string {
	if r == nil {
		return ""
	}
	var tables []string
	for _, s := range r.Sources {
		if s.Database == database {
			tables = append(tables, s.OutboxTable)
		}
	}
	return strings.Join(tables, ",")
}

// ValidateAgainstCatalog enforces 100% consistency between `outbox-sources.yaml` and `catalog.yaml`:
//  1. Every schema in `outbox-sources.yaml` has valid identifiers, `outboxTable == <schema>.outbox_event`,
//     `cdcRole == cdc_<schema>`, and non-empty database/connector/slotName.
//  2. Every topic in `outbox-sources.yaml` (`sources[].topics`, `wildcardTopics`, `nonOutboxTopics`)
//     exists in `catalog.yaml`. Unknown topics are rejected.
//  3. Every business event (`EVT-01..EVT-26`) and technical topic in `catalog.yaml` with
//     `outbox: <schema>.outbox_event` has exactly one owning schema in `outbox-sources.yaml`,
//     and is listed in that schema's `topics` (and `events` for `EVT-*`).
//  4. Every entry in `catalog.yaml` with `outbox: "*.outbox_event"` is listed in `wildcardTopics`.
//  5. Every entry in `catalog.yaml` with `outbox: null` is listed in `nonOutboxTopics` and NOT in any schema's `topics`.
func (r *Registry) ValidateAgainstCatalog(cat *Catalog) error {
	if r == nil {
		return fmt.Errorf("%w: nil registry", ErrValidationFailed)
	}
	if cat == nil {
		return fmt.Errorf("%w: nil catalog", ErrValidationFailed)
	}
	if r.Defaults.PublicationName == "" {
		return fmt.Errorf("%w: defaults.publicationName is required", ErrValidationFailed)
	}
	if r.Defaults.RetentionDays != 3 || r.Defaults.RetentionHours != 72 {
		return fmt.Errorf("%w: retention must match TL-03 (3 days) and TL-04 (72 hours)", ErrValidationFailed)
	}

	// Verify wildcard topics exist in catalog with outbox == "*.outbox_event"
	wildcardSeen := make(map[string]struct{}, len(r.WildcardTopics))
	for _, wt := range r.WildcardTopics {
		if _, dup := wildcardSeen[wt]; dup {
			return fmt.Errorf("%w: duplicate wildcard topic %q", ErrValidationFailed, wt)
		}
		wildcardSeen[wt] = struct{}{}
		entry, ok := cat.byTopic[wt]
		if !ok {
			return fmt.Errorf("%w: wildcard topic %q not found in catalog.yaml", ErrValidationFailed, wt)
		}
		if entry.Outbox != "*.outbox_event" {
			return fmt.Errorf("%w: wildcard topic %q has outbox=%q in catalog.yaml, expected \"*.outbox_event\"", ErrValidationFailed, wt, entry.Outbox)
		}
	}

	// Verify nonOutboxTopics exist in catalog with outbox == "" (null)
	nonOutboxSeen := make(map[string]struct{}, len(r.NonOutboxTopics))
	for _, nt := range r.NonOutboxTopics {
		if _, dup := nonOutboxSeen[nt.Topic]; dup {
			return fmt.Errorf("%w: duplicate nonOutboxTopic %q", ErrValidationFailed, nt.Topic)
		}
		nonOutboxSeen[nt.Topic] = struct{}{}
		entry, ok := cat.byTopic[nt.Topic]
		if !ok {
			return fmt.Errorf("%w: nonOutboxTopic %q not found in catalog.yaml", ErrValidationFailed, nt.Topic)
		}
		if entry.Outbox != "" {
			return fmt.Errorf("%w: nonOutboxTopic %q has outbox=%q in catalog.yaml, expected null", ErrValidationFailed, nt.Topic, entry.Outbox)
		}
		if nt.Code != "" && entry.Code != nt.Code {
			return fmt.Errorf("%w: nonOutboxTopic %q code mismatch: got %q, catalog has %q", ErrValidationFailed, nt.Topic, nt.Code, entry.Code)
		}
	}

	topicOwner := make(map[string]string)
	eventOwner := make(map[string]string)
	schemaSeen := make(map[string]struct{}, len(r.Sources))

	for _, src := range r.Sources {
		if !schemaIdentRe.MatchString(src.Schema) {
			return fmt.Errorf("%w: invalid schema name %q", ErrValidationFailed, src.Schema)
		}
		if _, dup := schemaSeen[src.Schema]; dup {
			return fmt.Errorf("%w: duplicate schema %q in outbox-sources.yaml", ErrValidationFailed, src.Schema)
		}
		schemaSeen[src.Schema] = struct{}{}

		expectedTable := src.Schema + ".outbox_event"
		if src.OutboxTable != expectedTable {
			return fmt.Errorf("%w: schema %q outboxTable=%q, expected %q", ErrValidationFailed, src.Schema, src.OutboxTable, expectedTable)
		}
		expectedRole := "cdc_" + src.Schema
		if src.CDCRole != expectedRole {
			return fmt.Errorf("%w: schema %q cdcRole=%q, expected %q", ErrValidationFailed, src.Schema, src.CDCRole, expectedRole)
		}
		if strings.TrimSpace(src.Owner) == "" || strings.TrimSpace(src.Database) == "" ||
			strings.TrimSpace(src.Connector) == "" || strings.TrimSpace(src.SlotName) == "" {
			return fmt.Errorf("%w: schema %q missing owner/database/connector/slotName", ErrValidationFailed, src.Schema)
		}

		for _, code := range src.Events {
			if prev, exists := eventOwner[code]; exists {
				return fmt.Errorf("%w: event %q claimed by both %q and %q", ErrValidationFailed, code, prev, src.Schema)
			}
			eventOwner[code] = src.Schema
			catEvt, ok := cat.byCode[code]
			if !ok {
				return fmt.Errorf("%w: schema %q references unknown event code %q", ErrValidationFailed, src.Schema, code)
			}
			if catEvt.Outbox != expectedTable {
				return fmt.Errorf("%w: event %s (%s) has outbox=%q in catalog.yaml, but listed under schema %q", ErrValidationFailed, code, catEvt.Topic, catEvt.Outbox, src.Schema)
			}
		}

		for _, topic := range src.Topics {
			if prev, exists := topicOwner[topic]; exists {
				return fmt.Errorf("%w: topic %q claimed by both %q and %q", ErrValidationFailed, topic, prev, src.Schema)
			}
			topicOwner[topic] = src.Schema
			catEntry, ok := cat.byTopic[topic]
			if !ok {
				return fmt.Errorf("%w: schema %q references unknown topic %q not in catalog.yaml", ErrValidationFailed, src.Schema, topic)
			}
			if catEntry.Outbox != expectedTable {
				return fmt.Errorf("%w: topic %q has outbox=%q in catalog.yaml, but listed under schema %q", ErrValidationFailed, topic, catEntry.Outbox, src.Schema)
			}
		}
	}

	// Reverse check: every entry in catalog.yaml must be accounted for in outbox-sources.yaml
	allCatalog := make([]CatalogEntry, 0, len(cat.Events)+len(cat.TechnicalTopics))
	allCatalog = append(allCatalog, cat.Events...)
	allCatalog = append(allCatalog, cat.TechnicalTopics...)

	for _, entry := range allCatalog {
		switch entry.Outbox {
		case "":
			if _, ok := nonOutboxSeen[entry.Topic]; !ok {
				return fmt.Errorf("%w: catalog topic %q (outbox: null) is missing from nonOutboxTopics", ErrValidationFailed, entry.Topic)
			}
			if owner, claimed := topicOwner[entry.Topic]; claimed {
				return fmt.Errorf("%w: non-outbox topic %q must not be claimed by schema %q", ErrValidationFailed, entry.Topic, owner)
			}
		case "*.outbox_event":
			if _, ok := wildcardSeen[entry.Topic]; !ok {
				return fmt.Errorf("%w: catalog wildcard topic %q (outbox: *.outbox_event) is missing from wildcardTopics", ErrValidationFailed, entry.Topic)
			}
		default:
			owner, ok := topicOwner[entry.Topic]
			if !ok {
				return fmt.Errorf("%w: catalog topic %q (outbox: %s) has no owning schema in outbox-sources.yaml", ErrValidationFailed, entry.Topic, entry.Outbox)
			}
			if owner+".outbox_event" != entry.Outbox {
				return fmt.Errorf("%w: catalog topic %q expects outbox %q, but owned by schema %q", ErrValidationFailed, entry.Topic, entry.Outbox, owner)
			}
			if entry.Code != "" {
				evtSchema, ok := eventOwner[entry.Code]
				if !ok || evtSchema != owner {
					return fmt.Errorf("%w: catalog event %s (%s) missing from events list of schema %q", ErrValidationFailed, entry.Code, entry.Topic, owner)
				}
			}
		}
	}

	return nil
}

// ParseRegistry parses the YAML content of `outbox-sources.yaml`.
func ParseRegistry(content string) (*Registry, error) {
	reg := &Registry{
		bySchema:    make(map[string]SourceSchema),
		wildcardSet: make(map[string]struct{}),
		allowedBySc: make(map[string]map[string]struct{}),
	}

	var (
		topSection   string
		subSection   string
		inSourceItem bool
		curSource    SourceSchema
		sourceList   string // "events" or "topics"
		inNonOutbox  bool
		curNonOutbox NonOutboxTopic
		inHeaderItem bool
		curHeader    HeaderMapping
	)

	flushSource := func() {
		if inSourceItem {
			reg.Sources = append(reg.Sources, curSource)
			curSource = SourceSchema{}
			inSourceItem = false
			sourceList = ""
		}
	}
	flushNonOutbox := func() {
		if inNonOutbox {
			reg.NonOutboxTopics = append(reg.NonOutboxTopics, curNonOutbox)
			curNonOutbox = NonOutboxTopic{}
			inNonOutbox = false
		}
	}
	flushHeader := func() {
		if inHeaderItem {
			reg.Defaults.EventRouter.HeaderFields = append(reg.Defaults.EventRouter.HeaderFields, curHeader)
			curHeader = HeaderMapping{}
			inHeaderItem = false
		}
	}

	scanner := bufio.NewScanner(strings.NewReader(content))
	lineNo := 0
	for scanner.Scan() {
		lineNo++
		raw := scanner.Text()
		cleaned := stripComment(raw)
		if strings.TrimSpace(cleaned) == "" {
			continue
		}
		indent := countIndent(cleaned)
		trimmed := strings.TrimSpace(cleaned)

		if indent == 0 {
			flushHeader()
			flushNonOutbox()
			flushSource()
			subSection = ""
			k, v, ok := splitKV(trimmed)
			if !ok {
				return nil, fmt.Errorf("sources: line %d: invalid top-level line %q", lineNo, trimmed)
			}
			if k == "version" {
				ver, err := strconv.Atoi(v)
				if err != nil {
					return nil, fmt.Errorf("sources: line %d: invalid version %q", lineNo, v)
				}
				reg.Version = ver
				topSection = ""
			} else {
				topSection = k
			}
			continue
		}

		switch topSection {
		case "defaults":
			if indent == 2 {
				flushHeader()
				k, v, ok := splitKV(trimmed)
				if !ok {
					return nil, fmt.Errorf("sources: line %d: invalid defaults entry %q", lineNo, trimmed)
				}
				switch k {
				case "publicationName":
					reg.Defaults.PublicationName = unquote(v)
				case "heartbeatTable":
					reg.Defaults.HeartbeatTable = unquote(v)
				case "retentionDays":
					n, err := strconv.Atoi(v)
					if err != nil {
						return nil, fmt.Errorf("sources: line %d: invalid retentionDays", lineNo)
					}
					reg.Defaults.RetentionDays = n
				case "retentionHours":
					n, err := strconv.Atoi(v)
					if err != nil {
						return nil, fmt.Errorf("sources: line %d: invalid retentionHours", lineNo)
					}
					reg.Defaults.RetentionHours = n
				case "partmanRetention":
					reg.Defaults.PartmanRetention = unquote(v)
				case "eventRouter":
					subSection = "eventRouter"
				}
			} else if subSection == "eventRouter" && indent == 4 {
				flushHeader()
				k, v, ok := splitKV(trimmed)
				if !ok {
					return nil, fmt.Errorf("sources: line %d: invalid eventRouter entry %q", lineNo, trimmed)
				}
				switch k {
				case "keyField":
					reg.Defaults.EventRouter.KeyField = unquote(v)
				case "payloadField":
					reg.Defaults.EventRouter.PayloadField = unquote(v)
				case "topicField":
					reg.Defaults.EventRouter.TopicField = unquote(v)
				case "headerFields":
					subSection = "headerFields"
				}
			} else if subSection == "headerFields" {
				if strings.HasPrefix(trimmed, "- ") {
					flushHeader()
					inHeaderItem = true
					rest := strings.TrimSpace(strings.TrimPrefix(trimmed, "- "))
					if k, v, ok := splitKV(rest); ok {
						assignHeaderField(&curHeader, k, unquote(v))
					}
				} else if inHeaderItem {
					if k, v, ok := splitKV(trimmed); ok {
						assignHeaderField(&curHeader, k, unquote(v))
					}
				}
			}

		case "wildcardTopics":
			if strings.HasPrefix(trimmed, "- ") {
				val := unquote(strings.TrimSpace(strings.TrimPrefix(trimmed, "- ")))
				reg.WildcardTopics = append(reg.WildcardTopics, val)
			}

		case "nonOutboxTopics":
			if strings.HasPrefix(trimmed, "- ") {
				flushNonOutbox()
				inNonOutbox = true
				rest := strings.TrimSpace(strings.TrimPrefix(trimmed, "- "))
				if k, v, ok := splitKV(rest); ok {
					assignNonOutbox(&curNonOutbox, k, unquote(v))
				}
			} else if inNonOutbox {
				if k, v, ok := splitKV(trimmed); ok {
					assignNonOutbox(&curNonOutbox, k, unquote(v))
				}
			}

		case "sources":
			if indent == 2 && strings.HasPrefix(trimmed, "- ") {
				flushSource()
				inSourceItem = true
				rest := strings.TrimSpace(strings.TrimPrefix(trimmed, "- "))
				if k, v, ok := splitKV(rest); ok {
					assignSourceField(&curSource, &sourceList, k, v)
				}
			} else if inSourceItem && indent == 4 {
				if k, v, ok := splitKV(trimmed); ok {
					assignSourceField(&curSource, &sourceList, k, v)
				}
			} else if inSourceItem && indent >= 6 && strings.HasPrefix(trimmed, "- ") {
				item := unquote(strings.TrimSpace(strings.TrimPrefix(trimmed, "- ")))
				switch sourceList {
				case "events":
					curSource.Events = append(curSource.Events, item)
				case "topics":
					curSource.Topics = append(curSource.Topics, item)
				}
			}
		}
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	flushHeader()
	flushNonOutbox()
	flushSource()

	for _, wt := range reg.WildcardTopics {
		reg.wildcardSet[wt] = struct{}{}
	}
	for _, s := range reg.Sources {
		reg.bySchema[s.Schema] = s
		allowed := make(map[string]struct{}, len(s.Topics)+len(reg.WildcardTopics))
		for _, t := range s.Topics {
			allowed[t] = struct{}{}
		}
		for _, wt := range reg.WildcardTopics {
			allowed[wt] = struct{}{}
		}
		reg.allowedBySc[s.Schema] = allowed
	}
	return reg, nil
}

// ParseCatalog parses `contracts/events/catalog.yaml`.
func ParseCatalog(content string) (*Catalog, error) {
	cat := &Catalog{
		byTopic: make(map[string]CatalogEntry),
		byCode:  make(map[string]CatalogEntry),
	}

	var (
		section string
		inItem  bool
		cur     CatalogEntry
	)
	flush := func() {
		if !inItem {
			return
		}
		if section == "events" {
			cat.Events = append(cat.Events, cur)
			if cur.Code != "" {
				cat.byCode[cur.Code] = cur
			}
			cat.byTopic[cur.Topic] = cur
		} else if section == "technicalTopics" {
			cur.IsTechnical = true
			cat.TechnicalTopics = append(cat.TechnicalTopics, cur)
			cat.byTopic[cur.Topic] = cur
		}
		cur = CatalogEntry{}
		inItem = false
	}

	scanner := bufio.NewScanner(strings.NewReader(content))
	for scanner.Scan() {
		raw := scanner.Text()
		cleaned := stripComment(raw)
		if strings.TrimSpace(cleaned) == "" {
			continue
		}
		indent := countIndent(cleaned)
		trimmed := strings.TrimSpace(cleaned)

		if indent == 0 {
			flush()
			k, v, ok := splitKV(trimmed)
			if !ok {
				continue
			}
			switch k {
			case "version":
				cat.Version, _ = strconv.Atoi(unquote(v))
				section = ""
			case "compatibility":
				cat.Compatibility = unquote(v)
				section = ""
			case "events", "technicalTopics":
				section = k
			default:
				section = ""
			}
			continue
		}

		if section == "events" || section == "technicalTopics" {
			if indent == 2 && strings.HasPrefix(trimmed, "- ") {
				flush()
				inItem = true
				rest := strings.TrimSpace(strings.TrimPrefix(trimmed, "- "))
				if k, v, ok := splitKV(rest); ok {
					assignCatalogField(&cur, k, v)
				}
			} else if inItem && indent == 4 {
				if k, v, ok := splitKV(trimmed); ok {
					assignCatalogField(&cur, k, v)
				}
			}
		}
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	flush()
	return cat, nil
}

func assignHeaderField(h *HeaderMapping, k, v string) {
	switch k {
	case "column":
		h.Column = v
	case "jsonbKey":
		h.JSONBKey = v
	case "header":
		h.Header = v
	}
}

func assignNonOutbox(n *NonOutboxTopic, k, v string) {
	switch k {
	case "code":
		n.Code = v
	case "topic":
		n.Topic = v
	case "reason":
		n.Reason = v
	}
}

func assignSourceField(s *SourceSchema, activeList *string, k, v string) {
	val := unquote(v)
	*activeList = ""
	switch k {
	case "schema":
		s.Schema = val
	case "outboxTable":
		s.OutboxTable = val
	case "owner":
		s.Owner = val
	case "database":
		s.Database = val
	case "devDatabase":
		s.DevDatabase = val
	case "connector":
		s.Connector = val
	case "slotName":
		s.SlotName = val
	case "cdcRole":
		s.CDCRole = val
	case "events":
		if val == "[]" {
			s.Events = nil
		} else {
			*activeList = "events"
		}
	case "topics":
		if val == "[]" {
			s.Topics = nil
		} else {
			*activeList = "topics"
		}
	}
}

func assignCatalogField(c *CatalogEntry, k, v string) {
	val := unquote(v)
	switch k {
	case "code":
		c.Code = val
	case "name":
		c.Name = val
	case "topic":
		c.Topic = val
	case "key":
		c.Key = val
	case "outbox":
		if val == "null" || val == "~" {
			c.Outbox = ""
		} else {
			c.Outbox = val
		}
	case "classification":
		c.Classification = val
	case "producer":
		c.Producer = val
	}
}

func stripComment(line string) string {
	inSingle := false
	inDouble := false
	for i := 0; i < len(line); i++ {
		ch := line[i]
		if ch == '\'' && !inDouble {
			inSingle = !inSingle
		} else if ch == '"' && !inSingle {
			inDouble = !inDouble
		} else if ch == '#' && !inSingle && !inDouble {
			if i == 0 || line[i-1] == ' ' || line[i-1] == '\t' {
				return line[:i]
			}
		}
	}
	return line
}

func countIndent(s string) int {
	n := 0
	for i := 0; i < len(s); i++ {
		if s[i] == ' ' {
			n++
		} else {
			break
		}
	}
	return n
}

func splitKV(s string) (string, string, bool) {
	idx := strings.IndexByte(s, ':')
	if idx <= 0 {
		return "", "", false
	}
	k := strings.TrimSpace(s[:idx])
	v := strings.TrimSpace(s[idx+1:])
	return k, v, true
}

func unquote(s string) string {
	s = strings.TrimSpace(s)
	if len(s) >= 2 {
		if (s[0] == '"' && s[len(s)-1] == '"') || (s[0] == '\'' && s[len(s)-1] == '\'') {
			return s[1 : len(s)-1]
		}
	}
	return s
}
