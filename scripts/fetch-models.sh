#!/usr/bin/env bash
# Downloads the embedding model at a pinned Hugging Face revision into models/, checking every file's
# sha256. application.yml loads it through file: URIs, so the service fetches nothing at boot and
# cannot start without it. Re-running skips files that already match.
set -euo pipefail

cd "$(dirname "$0")/.."

# dir|repo|revision|file|sha256
MODELS='
paraphrase|sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2|e8f8c211226b894fcb81acc59f3b34ba3efd5f42|onnx/model.onnx|10f7a088420252b26caf819236ca2c9d2987afd0fc06fec7553b542a5655a05a
paraphrase|sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2|e8f8c211226b894fcb81acc59f3b34ba3efd5f42|tokenizer.json|2c3387be76557bd40970cec13153b3bbf80407865484b209e655e5e4729076b8
'

sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }

while IFS='|' read -r dir repo rev file sum; do
  [ -z "$dir" ] && continue
  dest="models/$dir/$(basename "$file")"
  if [ -f "$dest" ] && [ "$(sha256 "$dest")" = "$sum" ]; then
    echo "ok      $dest"
    continue
  fi
  mkdir -p "models/$dir"
  curl -fsSL --retry 3 -o "$dest.part" "https://huggingface.co/$repo/resolve/$rev/$file"
  actual="$(sha256 "$dest.part")"
  if [ "$actual" != "$sum" ]; then
    rm -f "$dest.part"
    echo "sha256 mismatch for $repo@$rev/$file: got $actual, want $sum" >&2
    exit 1
  fi
  mv "$dest.part" "$dest"
  echo "fetched $dest"
done <<< "$MODELS"
