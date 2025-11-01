import csv
import hashlib
import os
import re
import sys
from typing import Dict, Iterable, List, Optional, Tuple

from dotenv import load_dotenv

# ---- DB: forzamos MySQL backend (Aurora) ----
from src import db_mysql as db

# ---- Fingerprinting ----
# Usamos tu implementación si está disponible:
try:
    from src import fingerprint  # debes tener fingerprint.audio_to_hashes(path)
    HAVE_FINGERPRINT = hasattr(fingerprint, "audio_to_hashes")
except Exception:
    HAVE_FINGERPRINT = False

# Fallback (solo si no existe fingerprint.audio_to_hashes)
def _fallback_fingerprint_file(path: str) -> List[Tuple[str, int]]:
    import numpy as np
    import librosa
    y, sr = librosa.load(path, sr=44100, mono=True)
    S = np.abs(librosa.stft(y, n_fft=2048, hop_length=1024))
    S_db = librosa.amplitude_to_db(S, ref=np.max)

    freq_bins, time_frames = S_db.shape
    bands = 5
    band_size = max(1, freq_bins // bands)

    peaks: List[Tuple[int, int]] = []
    for t in range(time_frames):
        for b in range(bands):
            start = b * band_size
            end = freq_bins if b == bands - 1 else (b + 1) * band_size
            sl = S_db[start:end, t]
            if sl.size == 0:
                continue
            f_rel = int(np.argmax(sl))
            f_idx = start + f_rel
            peaks.append((f_idx, t))

    fan_window = 15
    hashes: List[Tuple[str, int]] = []
    for i, (f1, t1) in enumerate(peaks):
        for j in range(i + 1, min(i + 1 + 64, len(peaks))):
            f2, t2 = peaks[j]
            dt = t2 - t1
            if 0 < dt <= fan_window:
                h = f"{f1}|{f2}|{dt}"
                hashes.append((h, t1))
    return hashes

def compute_fingerprints(path: str) -> List[Tuple[str, int]]:
    if HAVE_FINGERPRINT:
        return fingerprint.audio_to_hashes(path)
    return _fallback_fingerprint_file(path)


# ---------------- Utilities ----------------
load_dotenv()

CSV_REQUIRED = {"path", "title", "artist"}
CSV_OPTIONAL = {"year", "youtube_url", "youtube_id", "song_id"}
CSV_ALL = CSV_REQUIRED | CSV_OPTIONAL

YT_RE = re.compile(
    r"(?:v=|/v/|/embed/|youtu\.be/|/shorts/)([A-Za-z0-9_-]{6,})",
    re.IGNORECASE,
)

def youtube_id_from_url(url: Optional[str]) -> Optional[str]:
    if not url:
        return None
    m = YT_RE.search(url)
    return m.group(1) if m else None

def norm_str(x: Optional[str]) -> Optional[str]:
    if x is None:
        return None
    x = str(x).strip()
    return x or None

def to_int_or_none(x: Optional[str]) -> Optional[int]:
    x = norm_str(x)
    if not x:
        return None
    try:
        return int(x)
    except Exception:
        return None

def stable_song_id(title: Optional[str], artist: Optional[str], year: Optional[int], fallback_key: str) -> str:
    key = f"{norm_str(title) or ''}|{norm_str(artist) or ''}|{year if year is not None else ''}"
    if key == "||":
        key = fallback_key
    return hashlib.md5(key.encode("utf-8")).hexdigest()[:32]

def file_exists(path: str) -> bool:
    return os.path.isfile(path)

def guess_rel_path(p: str) -> str:
    return p


# --------------- Indexación por archivo ---------------
def index_file(
    conn,
    path: str,
    title: Optional[str] = None,
    artist: Optional[str] = None,
    year: Optional[int] = None,
    youtube_url: Optional[str] = None,
    youtube_id: Optional[str] = None,
    song_id: Optional[str] = None,
) -> Tuple[bool, str]:
    path = norm_str(path)
    if not path:
        return (False, "skip: path vacío")
    if not file_exists(path):
        return (False, f"skip: file not found -> {path}")

    # Normaliza metadatos mínimos
    if not title:
        title = os.path.splitext(os.path.basename(path))[0].replace("_", " ") or "Unknown Title"
    if not artist:
        artist = "Unknown Artist"
    if year is None:
        year = None

    # youtube_id derivado si no viene
    if not youtube_id and youtube_url:
        youtube_id = youtube_id_from_url(youtube_url)

    # song_id estable si no viene (por metadatos; fallback = filename)
    if not song_id:
        fallback_key = os.path.basename(path)
        song_id = stable_song_id(title, artist, year, fallback_key)

    # Inserta/actualiza metadatos
    db.add_song(
        conn,
        song_id=song_id,
        title=title,
        artist=artist,
        year=year,
        path=path,
        youtube_url=youtube_url,
        youtube_id=youtube_id,
    )

    # Fingerprints
    hashes = compute_fingerprints(path)
    if not hashes:
        return (False, f"no fingerprints -> {path}")

    db.add_fingerprints(conn, song_id=song_id, hashes=hashes, batch_size=5000)
    return (True, f"indexed {title} ({artist}) - hashes: {len(hashes)}")


# --------------- Indexación por carpeta (lo que ya tenías) ---------------
AUDIO_EXT = {".mp3", ".wav", ".flac", ".m4a", ".ogg", ".aac"}

def index_folder(conn, root: str = "audio_corpus"):
    total, ok, skipped, errors = 0, 0, 0, 0
    for dirpath, _, files in os.walk(root):
        for name in files:
            ext = os.path.splitext(name)[1].lower()
            if ext not in AUDIO_EXT:
                continue
            total += 1
            path = os.path.join(dirpath, name)
            try:
                done, msg = index_file(conn, path)
                if done:
                    ok += 1
                    print(f"✅ {msg}")
                else:
                    skipped += 1
                    print(f"⏭️  {msg}")
            except Exception as e:
                errors += 1
                print(f"❌ error: {path} -> {e}")

    print("\n---- RESUMEN (folder) ----")
    print(f"total archivos: {total}")
    print(f"indexados:      {ok}")
    print(f"saltados:       {skipped}")
    print(f"errores:        {errors}")


# --------------- Indexación por CSV (manifest.csv) ---------------
def read_csv_rows(csv_path: str) -> Iterable[Dict[str, str]]:
    with open(csv_path, "r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        headers = {h.strip() for h in (reader.fieldnames or [])}
        missing = CSV_REQUIRED - headers
        if missing:
            raise ValueError(f"CSV missing required columns: {sorted(missing)}")
        for row in reader:
            yield row

def index_manifest(conn, csv_path: str = "manifest.csv", limit: Optional[int] = None):
    total = 0
    ok = 0
    skipped = 0
    errors = 0

    for row in read_csv_rows(csv_path):
        if limit is not None and total >= limit:
            break
        total += 1
        try:
            path   = norm_str(row.get("path"))
            title  = norm_str(row.get("title"))
            artist = norm_str(row.get("artist"))
            year   = to_int_or_none(row.get("year"))
            yt_url = norm_str(row.get("youtube_url"))
            yt_id  = norm_str(row.get("youtube_id"))
            s_id   = norm_str(row.get("song_id"))

            done, msg = index_file(
                conn,
                path=path or "",
                title=title,
                artist=artist,
                year=year,
                youtube_url=yt_url,
                youtube_id=yt_id,
                song_id=s_id,
            )
            if done:
                ok += 1
                print(f"✅ {msg}")
            else:
                skipped += 1
                print(f"⏭️  {msg}")
        except Exception as e:
            errors += 1
            print(f"❌ error fila {total}: {e}")

    print("\n---- RESUMEN (CSV) ----")
    print(f"total filas:    {total}")
    print(f"indexadas:      {ok}")
    print(f"saltadas:       {skipped}")
    print(f"errores:        {errors}")


# --------------- Entrypoint CLI ---------------
def main():
    """
    Usos:
      python -m src.indexer                # indexar carpeta audio_corpus
      python -m src.indexer manifest.csv   # indexar CSV
      python -m src.indexer manifest.csv 50  # CSV con límite
    """
    args = sys.argv[1:]
    conn = db.connect()
    db.init_db(conn)  # idempotente

    try:
        if not args:
            index_folder(conn, root="audio_corpus")
        else:
            csv_path = args[0]
            limit = int(args[1]) if len(args) > 1 else None
            index_manifest(conn, csv_path=csv_path, limit=limit)
    finally:
        conn.close()

if __name__ == "__main__":
    main()
