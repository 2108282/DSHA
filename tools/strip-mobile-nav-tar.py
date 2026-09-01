import io, json, tarfile, sys

src, dst = sys.argv[1:3]
drop = ('./root/dsha-mobile-nav', './root/dsha-mobile-nav-installed',
        './root/dsha-mobile-nav.log', './root/dsha-mobile-inject.sh',
        './root/.dsh/profiles/web/node_modules/@dsh-external/dsh-mobile-nav',
        './usr/local/lib/node_modules/@dsh-external/dsh-mobile-nav')
with tarfile.open(src, 'r:gz') as inp, tarfile.open(dst, 'w:gz') as out:
    for m in inp:
        n = m.name.rstrip('/')
        if any(n == d or n.startswith(d + '/') for d in drop):
            continue
        data = None
        if n == './root/.dsh/profiles/web/package.json':
            raw = inp.extractfile(m).read()
            obj = json.loads(raw.decode())
            obj.get('dependencies', {}).pop('@dsh-external/dsh-mobile-nav', None)
            prof = obj.get('dsh', {}).get('profile', {})
            if isinstance(prof.get('bundles'), list):
                prof['bundles'] = [x for x in prof['bundles'] if x != '@dsh-external/dsh-mobile-nav']
            data = (json.dumps(obj, indent=2, ensure_ascii=False) + '\n').encode()
        elif n == './root/dsha-builtin.txt':
            raw = inp.extractfile(m).read()
            data = b''.join(line for line in raw.splitlines(True)
                             if line.strip() != b'@dsh-external/dsh-mobile-nav')
        if data is not None:
            m.size = len(data)
            out.addfile(m, io.BytesIO(data))
        else:
            out.addfile(m, inp.extractfile(m) if m.isfile() else None)
print('MOBILE_NAV_STRIPPED')
