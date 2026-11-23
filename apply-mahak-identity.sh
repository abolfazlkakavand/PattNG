#!/bin/bash
set -o errexit
set -o pipefail
set -o nounset
__dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Gives the playstore build its own identity so that it installs beside PattNG and PattNG F-Droid:
# package com.patterniha.pattng.mahak (PattNG is com.patterniha.pattng, F-Droid is
# com.patterniha.pattng.fdroid) and the name "PattNG Mahak". Nothing else about the app changes.

gradle="$__dir/V2rayNG/app/build.gradle.kts"
if ! grep -q 'applicationIdSuffix = ".mahak"' "$gradle"; then
  sed -i '/create("playstore") {/,/}/ s/dimension = "distribution"/dimension = "distribution"\n            applicationIdSuffix = ".mahak"/' "$gradle"
fi
if ! grep -q 'applicationIdSuffix = ".mahak"' "$gradle"; then
  echo "Identity patch did not apply to $gradle"
  exit 1
fi

res="$__dir/V2rayNG/app/src/main/res"
files=$(grep -rl 'name="app_name"' "$res" || true)
if [[ -z "$files" ]]; then
  echo "app_name was not found under $res"
  exit 1
fi
for f in $files; do
  sed -i 's|\(<string name="app_name"[^>]*>\)[^<]*\(</string>\)|\1PattNG Mahak\2|' "$f"
done
grep -rn 'name="app_name"' "$res" | head -n 5
grep -n -B2 'applicationIdSuffix = ".mahak"' "$gradle"
