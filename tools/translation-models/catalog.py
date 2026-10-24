"""Writes the list of translation models Oscanner offers (core/translate assets/translation/models.json).

Models are Mozilla's, for Firefox Translations (MPL-2.0), from Firefox's Remote Settings
collection translations-models-v2: per language pair, the newest released set of files meant for
Android (as Firefox on Android would pick them: no Nightly-only or desktop-only sets). Files stay
zstd-compressed; the app checks each download against the hash given here.

    python tools/translation-models/catalog.py [records.json]

Without an argument, the records are fetched from Remote Settings.
"""
import json
import os
import sys
import urllib.request

RECORDS = "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models-v2/records"
CDN = "https://firefox-settings-attachments.cdn.mozilla.net/"
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "core", "translate", "src", "main", "assets", "translation", "models.json")
ANDROID = {"", "env.appinfo.OS == 'Android'"}
TYPES = {"model", "lex", "vocab", "srcvocab", "trgvocab"}


def version(v):
    return tuple(int(p) for p in v.split("."))


def main():
    if len(sys.argv) > 1:
        records = json.load(open(sys.argv[1], encoding="utf-8"))["data"]
    else:
        with urllib.request.urlopen(RECORDS) as r:
            records = json.load(r)["data"]
    sets = {}
    for r in records:
        if (r.get("filter_expression") or "").strip() not in ANDROID:
            continue
        # Released versions only: "3.0a1" is a test build.
        if not all(p.isdigit() for p in r["version"].split(".")):
            continue
        key = (r["sourceLanguage"], r["targetLanguage"], r["version"])
        sets.setdefault(key, []).append(r)
    best = {}
    for (src, trg, ver), files in sets.items():
        types = {f["fileType"] for f in files}
        if not ({"model", "lex"} <= types and ("vocab" in types or {"srcvocab", "trgvocab"} <= types)):
            continue
        if (src, trg) not in best or version(ver) > version(best[(src, trg)][0]):
            best[(src, trg)] = (ver, files)
    pairs = []
    for (src, trg), (ver, files) in sorted(best.items()):
        pairs.append({
            "from": src,
            "to": trg,
            "version": ver,
            "files": [
                {
                    "type": f["fileType"],
                    "name": f["attachment"]["filename"],
                    "url": CDN + f["attachment"]["location"],
                    "size": f["attachment"]["size"],
                    "sha256": f["attachment"]["hash"],
                }
                for f in sorted(files, key=lambda f: f["fileType"])
                if f["fileType"] in TYPES
            ],
        })
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as out:
        json.dump({"source": RECORDS, "pairs": pairs}, out, indent=1)
        out.write("\n")
    total = sum(f["size"] for p in pairs for f in p["files"])
    print(f"{len(pairs)} pairs, {total / 1e9:.1f} GB in all, written to {os.path.normpath(OUT)}")


if __name__ == "__main__":
    main()
