#!/usr/bin/env bash
# Downloads the multilingual embedding models at pinned Hugging Face revisions into models/, checking
# every file's sha256. The profiles load them through file: URIs, so nothing is fetched at boot.
# Usage: scripts/fetch-models.sh [e5|paraphrase]...   (no argument: both)
set -euo pipefail

cd "$(dirname "$0")/.."

# name|repo|revision|file|sha256
MODELS='
e5|intfloat/multilingual-e5-small|614241f622f53c4eeff9890bdc4f31cfecc418b3|onnx/model.onnx|ca456c06b3a9505ddfd9131408916dd79290368331e7d76bb621f1cba6bc8665
e5|intfloat/multilingual-e5-small|614241f622f53c4eeff9890bdc4f31cfecc418b3|tokenizer.json|0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39
paraphrase|sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2|e8f8c211226b894fcb81acc59f3b34ba3efd5f42|onnx/model.onnx|10f7a088420252b26caf819236ca2c9d2987afd0fc06fec7553b542a5655a05a
paraphrase|sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2|e8f8c211226b894fcb81acc59f3b34ba3efd5f42|tokenizer.json|2c3387be76557bd40970cec13153b3bbf80407865484b209e655e5e4729076b8
'

wanted=("$@")
[ ${#wanted[@]} -eq 0 ] && wanted=(e5 paraphrase)

sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }

while IFS='|' read -r name repo rev file sum; do
  [ -z "$name" ] && continue
  [[ " ${wanted[*]} " == *" $name "* ]] || continue
  dest="models/$name/$(basename "$file")"
  if [ -f "$dest" ] && [ "$(sha256 "$dest")" = "$sum" ]; then
    echo "ok      $dest"
    continue
  fi
  mkdir -p "models/$name"
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
