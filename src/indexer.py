import os, hashlib
from typing import Optional
from . import db, fingerprint

AUDIO_EXTS = {".wav", ".mp3", ".flac", ".ogg", ".m4a"}

def file_song_id(path: str) -> str:
    st = os.stat(path)
    base = f"{os.path.basename(path)}|{st.st_size}"
    return hashlib.sha1(base.encode("utf-8")).hexdigest()[:16]

def index_file(conn, path: str, title: Optional[str]=None, artist: Optional[str]=None, year: int=None):
    title = title or os.path.splitext(os.path.basename(path))[0]
    artist = artist or "Unknown"
    year = year or 0
    song_id = file_song_id(path)

    print(f"[Index] {title} — {artist} ({year})")
    hashes = fingerprint.audio_to_hashes(path)
    print(f"  → {len(hashes)} hashes")

    db.add_song(conn, song_id, title, artist, year, path)
    if hashes:
        db.add_fingerprints(conn, song_id, hashes)

def index_folder(conn, root: str):
    for dirpath, _, filenames in os.walk(root):
        for name in filenames:
            ext = os.path.splitext(name)[1].lower()
            if ext in AUDIO_EXTS:
                index_file(conn, os.path.join(dirpath, name))
