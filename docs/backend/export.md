# Export

Export canvas snippets as Markdown, plain text, or PDF.

**File:** `app/services/export_service.py`, `app/api/export.py`

## Snippet Ordering

Snippets are sorted in reading order before export:
```python
sorted(canvas.snippets, key=lambda s: (s.position_y, s.position_x))
```
Primary: top to bottom. Secondary: left to right.

## Formats

### Plain Text

```python
def export_as_text(snippets: list[Snippet]) -> str
```

Output:
```
[Verse]
lyrics text here

[Chorus]
more lyrics here
```

### Markdown

```python
def export_as_markdown(snippets: list[Snippet]) -> str
```

Output:
```markdown
## Verse
lyrics text here

#tag1 #tag2

## Chorus
more lyrics here
```

Tags are included as hashtags below each snippet's text (only if tags exist).

### PDF

```python
def export_as_pdf(snippets: list[Snippet]) -> bytes
```

| Setting | Value |
|---------|-------|
| Page size | Letter (8.5" x 11") |
| Margins | 1 inch (72pt) all sides |
| Title | "Lyrics Canvas Export", Helvetica-Bold 16pt |
| Section headers | `[Label]`, Helvetica-Bold 12pt |
| Body text | Helvetica 10pt |
| Line spacing | 14pt body, 18pt headers |
| Page breaks | Auto at Y < 100 (header) or Y < 72 (body) |

Uses `reportlab.pdfgen.canvas.Canvas` with `io.BytesIO` for in-memory generation.

## Route

```
POST /api/canvases/{canvas_id}/export  (requires auth)
Body: { "format": "markdown" }  // "text", "markdown", or "pdf"
```

### Response

| Format | Content-Type | Filename |
|--------|-------------|----------|
| text | text/plain | `{title}.txt` |
| markdown | text/markdown | `{title}.md` |
| pdf | application/pdf | `{title}.pdf` |

All responses include `Content-Disposition: attachment` header for browser download.

### Database Query

```python
select(Canvas)
    .options(selectinload(Canvas.snippets).selectinload(Snippet.tags))
    .where(Canvas.id == canvas_id, Canvas.owner_id == user.id)
```

Eager-loads snippets with their tags in a single query for the markdown format (which includes tag hashtags).

## Schema

```python
class ExportRequest(BaseModel):
    format: str = "markdown"  # default format
```
