import io
from app.models.snippet import Snippet, SnippetType


SNIPPET_TYPE_LABELS = {
    SnippetType.verse: "Verse",
    SnippetType.chorus: "Chorus",
    SnippetType.bridge: "Bridge",
    SnippetType.hook: "Hook",
    SnippetType.freestyle: "Freestyle",
}


def export_as_text(snippets: list[Snippet]) -> str:
    lines = []
    for snippet in snippets:
        label = SNIPPET_TYPE_LABELS.get(snippet.snippet_type, "Section")
        lines.append(f"[{label}]")
        lines.append(snippet.raw_text)
        lines.append("")
    return "\n".join(lines)


def export_as_markdown(snippets: list[Snippet]) -> str:
    lines = []
    for snippet in snippets:
        label = SNIPPET_TYPE_LABELS.get(snippet.snippet_type, "Section")
        lines.append(f"## {label}")
        lines.append("")
        lines.append(snippet.raw_text)
        tags = [t.name for t in snippet.tags] if snippet.tags else []
        if tags:
            lines.append("")
            lines.append(" ".join(f"#{tag}" for tag in tags))
        lines.append("")
    return "\n".join(lines)


def export_as_pdf(snippets: list[Snippet]) -> bytes:
    from reportlab.lib.pagesizes import letter
    from reportlab.pdfgen import canvas as pdf_canvas

    buffer = io.BytesIO()
    c = pdf_canvas.Canvas(buffer, pagesize=letter)
    width, height = letter
    y = height - 72

    c.setFont("Helvetica-Bold", 16)
    c.drawString(72, y, "Lyrics Canvas Export")
    y -= 36

    for snippet in snippets:
        label = SNIPPET_TYPE_LABELS.get(snippet.snippet_type, "Section")
        c.setFont("Helvetica-Bold", 12)

        if y < 100:
            c.showPage()
            y = height - 72

        c.drawString(72, y, f"[{label}]")
        y -= 18

        c.setFont("Helvetica", 10)
        for line in snippet.raw_text.split("\n"):
            if y < 72:
                c.showPage()
                y = height - 72
            c.drawString(72, y, line)
            y -= 14
        y -= 14

    c.save()
    buffer.seek(0)
    return buffer.read()
