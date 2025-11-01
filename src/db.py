import sqlite3
from typing import List, Tuple

SCHEMA = 'CREATE TABLE IF NOT EXISTS songs(\n    song_id TEXT PRIMARY KEY,\n    title TEXT,\n    artist TEXT,\n    year INTEGER,\n    path TEXT\n);\nCREATE TABLE IF NOT EXISTS fingerprints(\n    hash TEXT,\n    song_id TEXT,\n    t_anchor INTEGER\n);\nCREATE INDEX IF NOT EXISTS idx_hash ON fingerprints(hash);\nCREATE INDEX IF NOT EXISTS idx_song ON fingerprints(song_id);\n'

def connect(db_path: str) -> sqlite3.Connection:
    return sqlite3.connect(db_path)

def init_db(conn: sqlite3.Connection):
    conn.executescript(SCHEMA)
    conn.commit()

def add_song(conn, song_id: str, title: str, artist: str, year: int, path: str):
    conn.execute(
        "INSERT OR REPLACE INTO songs(song_id, title, artist, year, path) VALUES(?,?,?,?,?)",
        (song_id, title, artist, year, path),
    )
    conn.commit()

def add_fingerprints(conn, song_id: str, hashes: List[Tuple[str,int]]):
    conn.executemany(
        "INSERT INTO fingerprints(hash, song_id, t_anchor) VALUES(?,?,?)",
        [(h, song_id, t) for (h, t) in hashes],
    )
    conn.commit()

def query_hash(conn, h: str) -> List[Tuple[str,int]]:
    cur = conn.execute("SELECT song_id, t_anchor FROM fingerprints WHERE hash = ?", (h,))
    return cur.fetchall()

def get_song_meta(conn, song_id: str):
    cur = conn.execute("SELECT song_id, title, artist, year, path FROM songs WHERE song_id = ?", (song_id,))
    return cur.fetchone()
