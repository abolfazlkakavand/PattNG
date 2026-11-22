#!/bin/bash
set -o errexit
set -o pipefail
set -o nounset
__dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Builds the Mahak engine as a static GOOS=linux arm64 program and stages it as libmahak.so,
# the way compile-pt.sh stages lyrebird: a CGO_ENABLED=0 linux ELF runs as it is on Android, and
# the installer extracts a lib*.so with execute permission (useLegacyPackaging is on).

SRC="${MAHAK_SRC:-$__dir/mahak-src}"
if [[ ! -f "$SRC/go.mod" ]]; then
  echo "Mahak sources not found at $SRC"
  exit 1
fi
if ! command -v go >/dev/null 2>&1; then
  echo "Go: go not found. install the Go toolchain"
  exit 1
fi

OUT="$__dir/libs-mahak/arm64-v8a"
mkdir -p "$OUT"
cd "$SRC"
go get github.com/Nemu-x/desync@v0.2.1
go mod tidy
go vet ./...
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o "$OUT/libmahak.so" .
ls -la "$OUT/libmahak.so"
