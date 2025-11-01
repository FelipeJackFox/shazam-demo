from collections import Counter
from . import db, fingerprint, config

def identify_clip(conn, audio_path: str):
    clip_hashes = fingerprint.audio_to_hashes(audio_path)
    votes = Counter()
    for h, t_q in clip_hashes:
        for song_id, t_anchor in db.query_hash(conn, h):
            offset = t_anchor - t_q
            votes[(song_id, offset)] += 1

    if not votes:
        return None

    (best_song, best_offset), best_count = votes.most_common(1)[0]

    per_song = Counter()
    for (song_id, _), c in votes.items():
        per_song[song_id] += c
    top = per_song.most_common(config.TOP_K_MATCHES)

    meta = db.get_song_meta(conn, best_song)
    result = {
        "best_song_id": best_song,
        "best_count_aligned": best_count,
        "best_offset_quant": best_offset,
        "best_meta": {
            "song_id": meta[0],
            "title": meta[1],
            "artist": meta[2],
            "year": meta[3],
            "path": meta[4],
        } if meta else None,
        "top_songs": [
            {
                "song_id": s,
                "matches": c,
                "meta": db.get_song_meta(conn, s)
            }
            for s, c in top
        ],
        "is_hit": best_count >= config.MIN_MATCHES_FOR_HIT,
    }
    return result
