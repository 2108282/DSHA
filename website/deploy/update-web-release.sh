#!/usr/bin/env bash
set -euo pipefail
base=/srv/dsha.cc
build="${1:?Usage: update-web-release.sh BUILD_ID ARCHIVE_SHA256 VERSION}"
archive_sha="${2:?Expected SHA-256 is required}"
version="${3:?Version is required}"
[[ "$build" =~ ^[a-f0-9]{16}$ ]]
[[ "$archive_sha" =~ ^[a-f0-9]{64}$ ]]
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$ ]]
archive="$base/uploads/$build/dsha-web-$version-$build.tar.gz"
release="$base/releases/$build"
test -L "$base/current"
previous=$(readlink "$base/current")
case "$previous" in "$base"/releases/*) ;; *) exit 1 ;; esac
printf '%s  %s\n' "$archive_sha" "$archive" | sha256sum -c -
if [ ! -e "$release" ]; then
  stage="$base/releases/.$build.stage.$$"
  test ! -e "$stage"
  install -d -m 755 "$stage/downloads/$version"
  tar -xzf "$archive" --no-same-owner -C "$stage"
  # 当前 APK 从同一上传目录取，校验清单后才生效；旧 APK 路径继续可用。
  cp -p "$base/uploads/$build/dsha-$version.apk" "$stage/downloads/$version/"
  cp -p "$base/uploads/$build/dsha-${version}low.apk" "$stage/downloads/$version/"
  (cd "$stage" && sha256sum -c checksums.sha256)
  python3 - "$stage" "$version" <<'PY'
import json, sys
from pathlib import Path
stage = Path(sys.argv[1])
feed = json.loads((stage / 'api/updates.json').read_text())
assert feed['releases'][0]['version'] == sys.argv[2]
assert json.loads((stage / 'health.json').read_text())['version'] == sys.argv[2]
PY
  for older in "$previous"/downloads/*; do
    name=$(basename "$older")
    if [[ "$name" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$ ]] && [ -d "$older" ] && [ ! -e "$stage/downloads/$name" ]; then
      ln -s "$(readlink -f "$older")" "$stage/downloads/$name"
    fi
  done
  find "$stage" -type d -exec chmod 755 {} +
  find "$stage" -type f -exec chmod 644 {} +
  mv "$stage" "$release"
fi
(cd "$release" && sha256sum -c checksums.sha256)
rollback() {
  code=$?
  trap - ERR
  ln -s "$previous" "$base/current.rollback.$$"
  mv -Tf "$base/current.rollback.$$" "$base/current"
  printf 'Restored previous release: %s\n' "$previous" >&2
  exit "$code"
}
trap rollback ERR
ln -s "$release" "$base/current.next.$$"
mv -Tf "$base/current.next.$$" "$base/current"
health=$(curl --fail --silent --show-error --resolve dsha.cc:443:127.0.0.1 https://dsha.cc/health.json)
python3 -c 'import json,sys; assert json.loads(sys.argv[2])["buildId"] == sys.argv[1]' "$build" "$health"
trap - ERR
printf '%s\nPrevious release: %s\nCurrent release: %s\n' "$health" "$previous" "$release"
