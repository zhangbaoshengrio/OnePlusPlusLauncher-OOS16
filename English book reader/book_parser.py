import re
import os

PARAGRAPHS_PER_PAGE = 30

# In-memory cache: filename -> list[str]
_cache: dict = {}


def parse_book(filepath: str) -> tuple:
    """Returns (title, paragraphs) where paragraphs is a list of plain-text strings."""
    ext = filepath.rsplit('.', 1)[-1].lower() if '.' in filepath else ''
    filename = os.path.basename(filepath)

    if ext == 'txt':
        return _parse_txt(filepath, filename)
    elif ext == 'epub':
        return _parse_epub(filepath)
    else:
        raise ValueError(f'Unsupported file type: .{ext}')


def _parse_txt(filepath: str, filename: str) -> tuple:
    with open(filepath, 'r', encoding='utf-8', errors='replace') as f:
        text = f.read()

    title = filename.replace('.txt', '').replace('_', ' ').replace('-', ' ').strip()

    # Split on blank lines
    raw = re.split(r'\n\s*\n', text)
    paragraphs = []

    for chunk in raw:
        # Collapse single newlines (continuation lines)
        p = ' '.join(line.strip() for line in chunk.split('\n') if line.strip())
        if not p:
            continue

        # If a paragraph is very long, split it at sentence boundaries
        if len(p) > 1500:
            sentences = re.split(r'(?<=[.!?])\s+', p)
            buf = ''
            for sent in sentences:
                if len(buf) + len(sent) > 800 and buf:
                    paragraphs.append(buf.strip())
                    buf = sent
                else:
                    buf += (' ' if buf else '') + sent
            if buf:
                paragraphs.append(buf.strip())
        else:
            paragraphs.append(p)

    return title, paragraphs


def _parse_epub(filepath: str) -> tuple:
    import ebooklib
    from ebooklib import epub
    from bs4 import BeautifulSoup

    book = epub.read_epub(filepath)

    title_meta = book.get_metadata('DC', 'title')
    title = title_meta[0][0] if title_meta else os.path.basename(filepath).replace('.epub', '')

    paragraphs = []
    for item in book.get_items():
        if item.get_type() != ebooklib.ITEM_DOCUMENT:
            continue

        content = item.get_content()
        try:
            soup = BeautifulSoup(content, 'lxml')
        except Exception:
            soup = BeautifulSoup(content, 'html.parser')

        for tag in soup(['script', 'style', 'nav', 'head']):
            tag.decompose()

        # Extract text from block-level elements
        for tag in soup.find_all(['p', 'h1', 'h2', 'h3', 'h4']):
            text = re.sub(r'\s+', ' ', tag.get_text(' ')).strip()
            if len(text) > 20:
                paragraphs.append(text)

    # Fallback: plain text extraction
    if not paragraphs:
        for item in book.get_items():
            if item.get_type() == ebooklib.ITEM_DOCUMENT:
                soup = BeautifulSoup(item.get_content(), 'html.parser')
                full_text = soup.get_text('\n\n')
                for chunk in re.split(r'\n\s*\n', full_text):
                    p = chunk.strip()
                    if len(p) > 20:
                        paragraphs.append(p)

    return title, paragraphs


def count_pages(paragraph_count: int) -> int:
    return max(1, (paragraph_count + PARAGRAPHS_PER_PAGE - 1) // PARAGRAPHS_PER_PAGE)


def get_page(paragraphs: list, page_num: int) -> list:
    start = page_num * PARAGRAPHS_PER_PAGE
    end = min(start + PARAGRAPHS_PER_PAGE, len(paragraphs))
    return paragraphs[start:end]


def cache_paragraphs(filename: str, paragraphs: list) -> None:
    _cache[filename] = paragraphs


def get_cached_paragraphs(filename: str):
    return _cache.get(filename)
