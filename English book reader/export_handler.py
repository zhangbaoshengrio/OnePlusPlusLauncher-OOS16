import io
import os
import tempfile
from flask import send_file


# ---------------------------------------------------------------------------
# TXT – detailed
# ---------------------------------------------------------------------------

def export_txt_detailed(words: list):
    lines = ['Vocabulary List — Detailed Export', '=' * 50, '']
    for i, w in enumerate(words, 1):
        lines.append(f'{i}. {w["word"]}')
        if w['definition']:
            lines.append(f'   Definition : {w["definition"]}')
        if w['sentence']:
            lines.append(f'   Sentence   : {w["sentence"]}')
        if w['source']:
            lines.append(f'   Source     : {w["source"]}')
        lines.append('')

    buf = io.BytesIO('\n'.join(lines).encode('utf-8'))
    return send_file(buf, mimetype='text/plain; charset=utf-8',
                     as_attachment=True, download_name='vocabulary_detailed.txt')


# ---------------------------------------------------------------------------
# TXT – words only
# ---------------------------------------------------------------------------

def export_txt_words(words: list):
    word_list = sorted({w['word'].lower() for w in words})
    buf = io.BytesIO('\n'.join(word_list).encode('utf-8'))
    return send_file(buf, mimetype='text/plain; charset=utf-8',
                     as_attachment=True, download_name='vocabulary_words.txt')


# ---------------------------------------------------------------------------
# PDF
# ---------------------------------------------------------------------------

def export_pdf(words: list):
    from fpdf import FPDF, XPos, YPos

    def safe(text: str) -> str:
        """Normalise common Unicode chars to latin-1 so Helvetica can render them."""
        if not text:
            return ''
        table = {
            '\u2018': "'", '\u2019': "'", '\u201c': '"', '\u201d': '"',
            '\u2013': '-', '\u2014': '--', '\u2026': '...', '\u00b7': '*',
            '\u00e9': 'e', '\u00e8': 'e', '\u00ea': 'e', '\u00eb': 'e',
            '\u00e0': 'a', '\u00e1': 'a', '\u00e2': 'a', '\u00e4': 'a',
            '\u00f3': 'o', '\u00f4': 'o', '\u00f6': 'o',
            '\u00fa': 'u', '\u00fb': 'u', '\u00fc': 'u',
            '\u00ed': 'i', '\u00ee': 'i', '\u00ef': 'i',
            '\u00e7': 'c', '\u00f1': 'n',
        }
        for src, dst in table.items():
            text = text.replace(src, dst)
        return text.encode('latin-1', errors='replace').decode('latin-1')

    class VocabPDF(FPDF):
        def header(self):
            self.set_font('Helvetica', 'B', 17)
            self.set_text_color(44, 62, 80)
            self.cell(0, 12, 'Vocabulary List',
                      new_x=XPos.LMARGIN, new_y=YPos.NEXT, align='C')
            self.set_draw_color(200, 200, 200)
            self.line(self.l_margin, self.get_y(),
                      self.w - self.r_margin, self.get_y())
            self.ln(5)

        def footer(self):
            self.set_y(-12)
            self.set_font('Helvetica', 'I', 8)
            self.set_text_color(150)
            self.cell(0, 8, f'Page {self.page_no()}', align='C')

    pdf = VocabPDF()
    pdf.set_margins(22, 28, 22)
    pdf.set_auto_page_break(True, margin=18)
    pdf.add_page()

    for i, w in enumerate(words, 1):
        # Word heading
        pdf.set_font('Helvetica', 'B', 13)
        pdf.set_text_color(41, 128, 185)
        pdf.cell(0, 8, f'{i}. {safe(w["word"])}',
                 new_x=XPos.LMARGIN, new_y=YPos.NEXT)

        # Definition
        if w['definition']:
            pdf.set_font('Helvetica', '', 10)
            pdf.set_text_color(55, 55, 55)
            pdf.multi_cell(0, 6, safe(w['definition']),
                           new_x=XPos.LMARGIN, new_y=YPos.NEXT)

        # Sentence (italic)
        if w['sentence']:
            pdf.set_font('Helvetica', 'I', 9)
            pdf.set_text_color(100, 100, 100)
            sentence = safe(w['sentence'])
            if len(sentence) > 240:
                sentence = sentence[:237] + '...'
            pdf.multi_cell(0, 6, f'"{sentence}"',
                           new_x=XPos.LMARGIN, new_y=YPos.NEXT)

        # Source
        if w['source']:
            pdf.set_font('Helvetica', 'I', 8)
            pdf.set_text_color(160, 160, 160)
            pdf.cell(0, 6, f'-- {safe(w["source"])}',
                     new_x=XPos.LMARGIN, new_y=YPos.NEXT, align='R')

        pdf.ln(5)

    buf = io.BytesIO(pdf.output())
    return send_file(buf, mimetype='application/pdf',
                     as_attachment=True, download_name='vocabulary.pdf')


# ---------------------------------------------------------------------------
# APKG (Anki)
# ---------------------------------------------------------------------------

def export_apkg(words: list):
    import genanki

    model = genanki.Model(
        1607392319,
        'English Book Reader',
        fields=[
            {'name': 'Word'},
            {'name': 'Definition'},
            {'name': 'Sentence'},
            {'name': 'Source'},
        ],
        templates=[{
            'name': 'Card 1',
            'qfmt': '<div class="word">{{Word}}</div>',
            'afmt': (
                '<div class="word">{{Word}}</div>'
                '<hr id=answer>'
                '{{#Definition}}<div class="definition">{{Definition}}</div>{{/Definition}}'
                '{{#Sentence}}<div class="sentence">&#8220;{{Sentence}}&#8221;</div>{{/Sentence}}'
                '{{#Source}}<div class="source">&#8212; {{Source}}</div>{{/Source}}'
            ),
        }],
        css="""\
.card {
  font-family: 'Georgia', serif;
  background: #fdf8f0;
  max-width: 620px;
  margin: 24px auto;
  padding: 32px;
  border-radius: 10px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.08);
}
.word {
  font-size: 2.4em;
  font-weight: bold;
  color: #2c3e50;
  text-align: center;
  margin: 8px 0 20px;
}
hr { border: none; border-top: 1px solid #ddd; margin: 20px 0; }
.definition {
  font-size: 1.05em;
  color: #333;
  line-height: 1.65;
  margin-bottom: 14px;
}
.sentence {
  font-size: 0.95em;
  color: #555;
  font-style: italic;
  line-height: 1.6;
  padding: 12px 16px;
  background: #eef4fb;
  border-left: 3px solid #4a9eff;
  margin-bottom: 12px;
}
.source {
  font-size: 0.82em;
  color: #999;
  text-align: right;
}
"""
    )

    deck = genanki.Deck(2059400110, 'English Book Reader')

    for w in words:
        note = genanki.Note(
            model=model,
            fields=[
                w['word'] or '',
                w['definition'] or '',
                w['sentence'] or '',
                w['source'] or '',
            ]
        )
        deck.add_note(note)

    # Write to a temp file, read into memory, then delete
    with tempfile.NamedTemporaryFile(suffix='.apkg', delete=False) as tmp:
        tmp_path = tmp.name

    try:
        genanki.Package(deck).write_to_file(tmp_path)
        with open(tmp_path, 'rb') as f:
            data = f.read()
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass

    buf = io.BytesIO(data)
    return send_file(buf, mimetype='application/octet-stream',
                     as_attachment=True, download_name='vocabulary.apkg')
