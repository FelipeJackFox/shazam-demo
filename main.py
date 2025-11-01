import argparse, sys, json
from src import db, indexer, identify

def cmd_index(args):
    conn = db.connect(args.db)
    db.init_db(conn)
    if args.root:
        indexer.index_folder(conn, args.root)
    elif args.file:
        indexer.index_file(conn, args.file, title=args.title, artist=args.artist, year=args.year)
    else:
        print("Proporciona --root (carpeta) o --file (archivo)")
        sys.exit(1)
    print("Indexado listo.")

def cmd_identify(args):
    conn = db.connect(args.db)
    result = identify.identify_clip(conn, args.audio)
    if not result:
        print("Sin coincidencias.")
        sys.exit(2)
    print(json.dumps(result, indent=2, ensure_ascii=False))

def main():
    p = argparse.ArgumentParser(description="Mini‑Shazam demo (fingerprinting acústico)")
    sub = p.add_subparsers(dest="cmd")

    p_idx = sub.add_parser("index", help="Indexar canciones a la base de huellas")
    p_idx.add_argument("--db", required=True, help="Ruta a la base SQLite (se crea si no existe)")
    g = p_idx.add_mutually_exclusive_group(required=True)
    g.add_argument("--root", help="Carpeta con audios a indexar (recursivo)")
    g.add_argument("--file", help="Archivo de audio a indexar")
    p_idx.add_argument("--title", help="Título (si --file)")
    p_idx.add_argument("--artist", help="Artista (si --file)")
    p_idx.add_argument("--year", type=int, help="Año (si --file)")
    p_idx.set_defaults(func=cmd_index)

    p_id = sub.add_parser("identify", help="Identificar un clip de audio")
    p_id.add_argument("--db", required=True, help="Ruta a la base SQLite existente")
    p_id.add_argument("--audio", required=True, help="Archivo de audio del fragmento a identificar")
    p_id.set_defaults(func=cmd_identify)

    args = p.parse_args()
    if not args.cmd:
        p.print_help()
        return
    args.func(args)

if __name__ == "__main__":
    main()
