import sqlite3

DB_PATH = 'vocab.db'


def get_connection():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    with get_connection() as conn:
        conn.execute('''
            CREATE TABLE IF NOT EXISTS vocabulary (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                word        TEXT    NOT NULL,
                definition  TEXT    DEFAULT '',
                sentence    TEXT    DEFAULT '',
                source      TEXT    DEFAULT '',
                added_at    DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        ''')
        conn.commit()


def add_word(word, definition='', sentence='', source=''):
    """Insert a word. Returns new row id, or None if the word already exists."""
    with get_connection() as conn:
        existing = conn.execute(
            'SELECT id FROM vocabulary WHERE LOWER(word) = LOWER(?)', (word,)
        ).fetchone()
        if existing:
            return None
        cursor = conn.execute(
            'INSERT INTO vocabulary (word, definition, sentence, source) VALUES (?, ?, ?, ?)',
            (word, definition, sentence, source)
        )
        conn.commit()
        return cursor.lastrowid


def get_all_words():
    with get_connection() as conn:
        rows = conn.execute(
            'SELECT id, word, definition, sentence, source, added_at '
            'FROM vocabulary ORDER BY added_at DESC'
        ).fetchall()
        return [dict(r) for r in rows]


def delete_word(word_id):
    with get_connection() as conn:
        conn.execute('DELETE FROM vocabulary WHERE id = ?', (word_id,))
        conn.commit()
