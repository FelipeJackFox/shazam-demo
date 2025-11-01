# Mini‑Shazam (Fingerprinting acústico en Python)

Demo educativa que implementa un *fingerprinting* tipo Shazam usando:
- STFT (librosa) → espectrograma
- Detección de picos (máximos locales 2D, `scipy.ndimage.maximum_filter`)
- Hashes de pares de picos `(f1, f2, Δt)` con cuantización
- Indexado en SQLite para búsqueda rápida por hash
- Identificación por votación y alineamiento temporal (offset histogram)

## Instalación
```bash
python -m venv .venv
source .venv/bin/activate  # En Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

## Estructura
- `config.py` — parámetros del pipeline (FFT, hop, umbrales, etc.).
- `fingerprint.py` — STFT, picos y generación de hashes.
- `db.py` — base de datos SQLite para huellas.
- `indexer.py` — indexado de canciones (wav/mp3) a la base de datos.
- `identify.py` — identificación de un clip, devolviendo el mejor match.
- `main.py` — CLI simple para indexar e identificar.

## Uso rápido
1) **Indexar** tu corpus (p. ej., una carpeta con covers):
```bash
python main.py index --db fp.sqlite --root ./audio_corpus
```
Esto indexará todos los `.wav`/`.mp3` dentro de `audio_corpus` como canciones separadas.

2) **Identificar** un fragmento:
```bash
python main.py identify --db fp.sqlite --audio ./snippets/one_kiss_test_clip.wav

python main.py identify --db fp.sqlite --audio ./snippets/the_final_countdown_test_clip.ogg

python main.py identify --db fp.sqlite --audio ./snippets/suite_bergamasque_test_clip.ogg #no hit example

python main.py identify --db fp.sqlite --audio ./snippets/suite_bergamasque_2_test_clip.ogg #same song, better quality, less time than no hit, still this one hits

python main.py identify --db fp.sqlite --audio ./snippets/hedwig's_theme_test_clip.ogg
```
3) **visualize_match**
```bash
python visualize_match.py --db fp.sqlite --audio .\snippets\one_kiss_test_clip.wav

python visualize_match.py --db fp.sqlite --audio .\snippets\the_final_countdown_test_clip.ogg

python visualize_match.py --db fp.sqlite --audio .\snippets\suite_bergamasque_2_test_clip.ogg

python visualize_match.py --db fp.sqlite --audio ./snippets/hedwig's_theme_test_clip.ogg

```


3) **Tips**:
- Mantén el *sample rate* en 44.1 kHz y mono para consistencia.
- Si tu audio viene muy bajo, normaliza antes.
- Ajusta `PEAK_AMPLITUDE_DB` y `PEAK_NEIGHBORHOOD_SIZE` si hay pocos/muchos picos.
- Para robustez: captura 5–8 s del micrófono.

## Nota legal
Usa **solo** audio con licencias adecuadas (covers autorizados, CC, etc.).
