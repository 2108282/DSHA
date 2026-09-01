#!/system/bin/sh
set -eu
ROOT=${1:?rootfs path required}
rm -rf "$ROOT/root/dsha-mobile-nav" "$ROOT/root/dsha-mobile-nav-installed" \
  "$ROOT/root/dsha-mobile-nav.log" "$ROOT/root/dsha-mobile-inject.sh" \
  "$ROOT/root/.dsh/profiles/web/node_modules/@dsh-external/dsh-mobile-nav" \
  "$ROOT/usr/local/lib/node_modules/@dsh-external/dsh-mobile-nav"
PF="$ROOT/root/.dsh/profiles/web/package.json"
if [ -f "$PF" ]; then
  NODE_BIN=/data/data/com.termux/files/usr/bin/node
  "$NODE_BIN" - "$PF" <<'NODE'
const fs=require('fs'); const p=process.argv[2];
const d=JSON.parse(fs.readFileSync(p,'utf8'));
if(d.dependencies) delete d.dependencies['@dsh-external/dsh-mobile-nav'];
const b=d.dsh&&d.dsh.profile&&d.dsh.profile.bundles;
if(Array.isArray(b)) d.dsh.profile.bundles=b.filter(x=>x!=='@dsh-external/dsh-mobile-nav');
fs.writeFileSync(p,JSON.stringify(d,null,2)+'\n');
NODE
fi
if [ -f "$ROOT/root/dsha-builtin.txt" ]; then
  sed -i '/^@dsh-external\/dsh-mobile-nav$/d' "$ROOT/root/dsha-builtin.txt"
fi
! find "$ROOT" -path '*mobile-nav*' -print -quit | grep -q .
grep -q 'dsh-web-mobile' "$PF"
echo MOBILE_NAV_REMOVED
