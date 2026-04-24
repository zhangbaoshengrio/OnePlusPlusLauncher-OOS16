from flask import Flask, render_template, request, jsonify
import os
import json as _json
import requests as http_client

import database
import book_parser
import export_handler

app = Flask(__name__)
os.makedirs('books', exist_ok=True)
database.init_db()


# ---------------------------------------------------------------------------
# Pages
# ---------------------------------------------------------------------------

@app.route('/')
def index():
    books = sorted(
        [f for f in os.listdir('books') if f.endswith(('.txt', '.epub'))],
        key=lambda f: os.path.getmtime(os.path.join('books', f)),
        reverse=True,
    )
    return render_template('index.html', books=books)


# ---------------------------------------------------------------------------
# Book upload & pagination
# ---------------------------------------------------------------------------

@app.route('/upload', methods=['POST'])
def upload():
    if 'file' not in request.files:
        return jsonify({'error': 'No file provided'}), 400

    f = request.files['file']
    if not f.filename:
        return jsonify({'error': 'No filename'}), 400

    filename = f.filename
    ext = filename.rsplit('.', 1)[-1].lower() if '.' in filename else ''
    if ext not in ('txt', 'epub'):
        return jsonify({'error': 'Only .txt and .epub files are supported'}), 400

    filepath = os.path.join('books', filename)
    f.save(filepath)

    try:
        title, paragraphs = book_parser.parse_book(filepath)
        total_pages = book_parser.count_pages(len(paragraphs))
        book_parser.cache_paragraphs(filename, paragraphs)
        return jsonify({
            'filename': filename,
            'title': title,
            'total_pages': total_pages,
            'total_paragraphs': len(paragraphs),
        })
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@app.route('/api/page')
def get_page():
    filename = request.args.get('book', '').strip()
    try:
        page = int(request.args.get('page', 0))
    except ValueError:
        page = 0

    if not filename:
        return jsonify({'error': 'No book specified'}), 400

    filepath = os.path.join('books', filename)
    if not os.path.exists(filepath):
        return jsonify({'error': 'Book not found'}), 404

    paragraphs = book_parser.get_cached_paragraphs(filename)
    if paragraphs is None:
        try:
            _, paragraphs = book_parser.parse_book(filepath)
            book_parser.cache_paragraphs(filename, paragraphs)
        except Exception as e:
            return jsonify({'error': str(e)}), 500

    total_pages = book_parser.count_pages(len(paragraphs))
    page = max(0, min(page, total_pages - 1))

    return jsonify({
        'paragraphs': book_parser.get_page(paragraphs, page),
        'page': page,
        'total_pages': total_pages,
    })


# ---------------------------------------------------------------------------
# Dictionary lookup (proxy to free API)
# ---------------------------------------------------------------------------

@app.route('/api/lookup/<word>')
def lookup_word(word):
    word = word.strip().lower()
    if not word:
        return jsonify({'word': word, 'found': False, 'phonetic': '', 'meanings': []}), 400

    try:
        url = f'https://api.dictionaryapi.dev/api/v2/entries/en/{word}'
        resp = http_client.get(url, timeout=8)

        if resp.status_code == 200:
            return jsonify(_parse_dict_response(resp.json(), word))

        return jsonify({'word': word, 'found': False, 'phonetic': '', 'meanings': []})

    except http_client.Timeout:
        return jsonify({'word': word, 'found': False, 'phonetic': '', 'meanings': [],
                        'error': 'Request timed out'})
    except Exception as e:
        return jsonify({'word': word, 'found': False, 'phonetic': '', 'meanings': [],
                        'error': str(e)})


def _parse_dict_response(data: list, word: str) -> dict:
    phonetic = ''
    meanings = []

    for entry in data:
        if not phonetic:
            phonetic = entry.get('phonetic', '')
            if not phonetic:
                for p in entry.get('phonetics', []):
                    if p.get('text'):
                        phonetic = p['text']
                        break

        for meaning in entry.get('meanings', []):
            pos = meaning.get('partOfSpeech', '')
            defs = []
            for d in meaning.get('definitions', [])[:2]:
                defs.append({
                    'definition': d.get('definition', ''),
                    'example': d.get('example', ''),
                })
            if defs:
                meanings.append({'pos': pos, 'definitions': defs})
            if len(meanings) >= 4:
                break
        if len(meanings) >= 4:
            break

    return {'word': word, 'found': True, 'phonetic': phonetic, 'meanings': meanings}


# ---------------------------------------------------------------------------
# Vocabulary CRUD
# ---------------------------------------------------------------------------

@app.route('/api/vocab', methods=['GET'])
def get_vocab():
    return jsonify(database.get_all_words())


@app.route('/api/vocab', methods=['POST'])
def add_vocab():
    data = request.json or {}
    word = (data.get('word') or '').strip()
    if not word:
        return jsonify({'error': 'Word is required'}), 400

    word_id = database.add_word(
        word=word,
        definition=data.get('definition', ''),
        sentence=data.get('sentence', ''),
        source=data.get('source', ''),
    )

    if word_id is None:
        return jsonify({'status': 'exists'})
    return jsonify({'id': word_id, 'status': 'added'})


@app.route('/api/vocab/<int:word_id>', methods=['DELETE'])
def delete_vocab(word_id):
    database.delete_word(word_id)
    return jsonify({'status': 'deleted'})


# ---------------------------------------------------------------------------
# Export
# ---------------------------------------------------------------------------

@app.route('/api/export/<fmt>')
def export(fmt):
    words = database.get_all_words()
    if not words:
        return jsonify({'error': 'Vocabulary is empty'}), 400

    words_sorted = sorted(words, key=lambda w: w['word'].lower())

    if fmt == 'txt_detailed':
        return export_handler.export_txt_detailed(words_sorted)
    elif fmt == 'txt_words':
        return export_handler.export_txt_words(words_sorted)
    elif fmt == 'pdf':
        return export_handler.export_pdf(words_sorted)
    elif fmt == 'apkg':
        return export_handler.export_apkg(words_sorted)
    else:
        return jsonify({'error': f'Unknown format: {fmt}'}), 400


# ---------------------------------------------------------------------------
# Dictionary management
# ---------------------------------------------------------------------------

_DICT_CONFIG = 'dict_config.json'

_BUILTIN_DICTS = [
    {
        'id': 'free-api',
        'name': 'Free Dictionary',
        'name_short': 'Free Dict',
        'description': '英英词典，提供详细英文释义与例句',
        'builtin': True,
    }
]

_DOWNLOADABLE_DICTS = [
    {
        'id': 'md-tea',
        'name': 'MD茶餐厅精选网络词典',
        'name_short': 'MD茶餐厅',
        'description': '精选网络词典，提供简洁中文释义（由有道词典提供支持）',
        'builtin': False,
    }
]


def _load_dict_cfg():
    if os.path.exists(_DICT_CONFIG):
        try:
            with open(_DICT_CONFIG, encoding='utf-8') as f:
                return _json.load(f)
        except Exception:
            pass
    return {'installed': ['free-api'], 'enabled': ['free-api']}


def _save_dict_cfg(cfg):
    with open(_DICT_CONFIG, 'w', encoding='utf-8') as f:
        _json.dump(cfg, f, ensure_ascii=False)


@app.route('/api/dictionaries')
def get_dictionaries():
    cfg = _load_dict_cfg()
    installed_ids = set(cfg.get('installed', ['free-api']))
    enabled_ids   = set(cfg.get('enabled',   ['free-api']))

    installed = []
    for d in _BUILTIN_DICTS:
        installed.append({**d, 'installed': True, 'enabled': d['id'] in enabled_ids})
    for d in _DOWNLOADABLE_DICTS:
        if d['id'] in installed_ids:
            installed.append({**d, 'installed': True, 'enabled': d['id'] in enabled_ids})

    available = [
        {**d, 'installed': False, 'enabled': False}
        for d in _DOWNLOADABLE_DICTS
        if d['id'] not in installed_ids
    ]
    return jsonify({'installed': installed, 'available': available})


@app.route('/api/dictionaries/install', methods=['POST'])
def install_dictionary():
    data = request.json or {}
    dict_id = (data.get('id') or '').strip()
    valid = {d['id'] for d in _DOWNLOADABLE_DICTS}
    if dict_id not in valid:
        return jsonify({'error': 'Unknown dictionary'}), 400
    cfg = _load_dict_cfg()
    if dict_id not in cfg.get('installed', []):
        cfg.setdefault('installed', []).append(dict_id)
    if dict_id not in cfg.get('enabled', []):
        cfg.setdefault('enabled', []).append(dict_id)
    _save_dict_cfg(cfg)
    return jsonify({'status': 'installed'})


@app.route('/api/dictionaries/toggle', methods=['POST'])
def toggle_dictionary():
    data = request.json or {}
    dict_id = (data.get('id') or '').strip()
    cfg = _load_dict_cfg()
    enabled = cfg.get('enabled', ['free-api'])
    if dict_id in enabled:
        if len(enabled) <= 1:
            return jsonify({'error': '至少保留一个词典', 'enabled': True}), 400
        enabled.remove(dict_id)
        new_state = False
    else:
        enabled.append(dict_id)
        new_state = True
    cfg['enabled'] = enabled
    _save_dict_cfg(cfg)
    return jsonify({'status': 'ok', 'enabled': new_state})


@app.route('/api/lookup/md-tea/<word>')
def lookup_md_tea(word):
    word = word.strip().lower()
    if not word:
        return jsonify({'word': word, 'found': False, 'phonetic': '', 'meanings': []}), 400
    try:
        url = (f'https://dict.youdao.com/suggest?num=5&ver=3.0'
               f'&doctype=json&cache=false&le=en&q={word}')
        resp = http_client.get(url, timeout=8, headers={
            'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)',
            'Referer': 'https://dict.youdao.com/',
        })
        if resp.status_code == 200:
            return jsonify(_parse_youdao_suggest(resp.json(), word))
        return jsonify({'word': word, 'found': False, 'phonetic': '', 'meanings': []})
    except http_client.Timeout:
        return jsonify({'word': word, 'found': False, 'phonetic': '',
                        'meanings': [], 'error': '请求超时'})
    except Exception as e:
        return jsonify({'word': word, 'found': False, 'phonetic': '',
                        'meanings': [], 'error': str(e)})


def _parse_youdao_suggest(data: dict, word: str) -> dict:
    try:
        entries = data.get('result', {}).get('em', [])
        if not entries:
            return {'word': word, 'found': False, 'phonetic': '', 'meanings': []}

        phonetic = ''
        meanings = []

        # Try to find exact match first
        target = next((e for e in entries if e.get('word', '').lower() == word), None)
        if target is None:
            target = entries[0]

        phonetic = target.get('phone', '') or ''
        trans_list = target.get('trans', [])

        pos_groups: dict = {}
        for t in trans_list:
            pos = (t.get('pos') or '').strip() or '释义'
            cn  = ((t.get('tranCn') or t.get('tran') or '')).strip()
            if cn:
                pos_groups.setdefault(pos, []).append(cn)

        for pos, defs in pos_groups.items():
            meanings.append({'pos': pos, 'definitions': [
                {'definition': '；'.join(defs), 'example': ''}
            ]})

        return {'word': word, 'found': bool(meanings),
                'phonetic': phonetic, 'meanings': meanings}
    except Exception:
        return {'word': word, 'found': False, 'phonetic': '', 'meanings': []}


if __name__ == '__main__':
    app.run(debug=True, port=5001)
