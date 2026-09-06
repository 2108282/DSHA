#!/usr/bin/env python3
"""插件安装预览、更新发现、单版本回退与安全启动；由 plugin-manager 注入既有安装器。"""
import hashlib
import json
import os
import re
import shlex
import shutil
import time
import urllib.parse
import uuid


def version_parts(value):
    match = re.fullmatch(r"v?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?", str(value))
    if not match:
        return None
    return tuple(int(match[i] or 0) for i in (1, 2, 3)), match[4]


def compare_versions(left, right):
    a, b = version_parts(left), version_parts(right)
    if a is None or b is None:
        return None
    if a[0] != b[0]:
        return (a[0] > b[0]) - (a[0] < b[0])
    if a[1] == b[1]:
        return 0
    if a[1] is None or b[1] is None:
        return 1 if a[1] is None else -1
    aa, bb = a[1].split('.'), b[1].split('.')
    for x, y in zip(aa, bb):
        if x == y:
            continue
        if x.isdigit() and y.isdigit():
            return (int(x) > int(y)) - (int(x) < int(y))
        if x.isdigit() != y.isdigit():
            return -1 if x.isdigit() else 1
        return (x > y) - (x < y)
    return (len(aa) > len(bb)) - (len(aa) < len(bb))


def accepts_version(installed, requirement):
    """识别常用精确/比较/^/~ 范围；不能确定时返回 None，交给用户查看声明。"""
    if requirement.strip() in ('*', 'latest', ''):
        return True
    parsed = version_parts(installed)
    if not parsed:
        return None
    unknown = False
    for group in requirement.split('||'):
        words = group.strip().split()
        outcomes = []
        for word in words:
            match = re.fullmatch(r'(\^|~|>=|<=|>|<|=)?(v?\d+(?:\.\d+){0,2}(?:-[0-9A-Za-z.-]+)?)', word)
            if not match:
                unknown = True; outcomes.append(None); continue
            operator, wanted = match[1] or '=', match[2]
            cmp = compare_versions(installed, wanted)
            if cmp is None:
                outcomes.append(None); continue
            if operator in ('^', '~'):
                target = version_parts(wanted)[0]
                if operator == '~':
                    upper = (target[0], target[1] + 1, 0)
                elif target[0]:
                    upper = (target[0] + 1, 0, 0)
                elif target[1]:
                    upper = (0, target[1] + 1, 0)
                else:
                    upper = (0, 0, target[2] + 1)
                outcomes.append(cmp >= 0 and parsed[0] < upper)
            else:
                outcomes.append({'=': cmp == 0, '>': cmp > 0, '<': cmp < 0, '>=': cmp >= 0, '<=': cmp <= 0}[operator])
        # 对预发布版不猜测 npm 的所有复杂区间规则，没有显式声明时标记待确认。
        if parsed[1] and '-' not in group:
            outcomes.append(None)
        if outcomes and all(value is True for value in outcomes):
            return True
        if None in outcomes and False not in outcomes:
            unknown = True
    return None if unknown else False


class HistoryChange:
    def __init__(self, life, name, old, work, source):
        self.life, self.old = life, old
        self.target = life.history_path(name)
        self.saved = os.path.join(work, 'previous-history')
        self.moved_old = False
        self.moved_history = False
        self.created = False
        try:
            if os.path.lexists(self.target):
                os.replace(self.target, self.saved); self.moved_history = True
            os.makedirs(self.target)
            self.created = True
            os.replace(old, os.path.join(self.target, 'package')); self.moved_old = True
            pkg = life.read(os.path.join(self.target, 'package', 'package.json'), {})
            life.write(os.path.join(self.target, 'state.json'), {'name': name, 'source': source,
                       'version': pkg.get('version', ''), 'savedAt': int(time.time())})
        except Exception:
            self.undo(); raise

    def undo(self):
        if self.moved_old and os.path.isdir(os.path.join(self.target, 'package')):
            os.replace(os.path.join(self.target, 'package'), self.old)
        if self.created and os.path.isdir(self.target):
            shutil.rmtree(self.target)
        if self.moved_history and os.path.exists(self.saved):
            os.replace(self.saved, self.target)


class Lifecycle:
    def __init__(self, manager):
        self.g = manager
        self.builtin = manager['builtin']
        self.local = manager['local']
        self.home = self.local(manager['DSH_HOME'])
        self.read, self.write = manager['read_json'], manager['write_json']

    def path(self, relative):
        path = os.path.join(self.home, relative)
        if os.path.commonpath([os.path.realpath(self.home), os.path.realpath(path)]) != os.path.realpath(self.home):
            raise ValueError('插件管理目录指向外部，已停止操作')
        return path

    def history_path(self, name):
        if not self.builtin.valid_name(name):
            raise ValueError('无效插件名')
        parent = self.path('plugin-history')
        os.makedirs(parent, exist_ok=True)
        return self.path('plugin-history/' + hashlib.sha256(name.encode()).hexdigest()[:32])

    def retain(self, name, old, work, source):
        return HistoryChange(self, name, old, work, source)

    def history_info(self, name):
        path = self.history_path(name)
        state = self.read(os.path.join(path, 'state.json'), {})
        return state if state.get('name') == name and os.path.isfile(os.path.join(path, 'package', 'package.json')) else {}

    def dsh_version(self):
        for path in ('/usr/local/lib/node_modules/@deepseek-ai/dsh/package.json', '/root/dsh-src/package.json'):
            value = self.read(self.local(path), {})
            if value.get('name') == '@deepseek-ai/dsh':
                return value.get('version', '')
        return ''

    def metadata(self, root):
        pkg = self.g['plugin_package'](root)
        author = pkg.get('author', '')
        if isinstance(author, dict):
            author = author.get('name', '')
        requirement = (pkg.get('peerDependencies') or {}).get('@deepseek-ai/dsh') or (pkg.get('engines') or {}).get('dsh') or ''
        if not isinstance(requirement, str):
            requirement = ''
        installed = self.dsh_version()
        accepted = accepts_version(installed, requirement) if installed and requirement else None
        state = 'compatible' if accepted is True else 'incompatible' if accepted is False else 'unknown'
        text = ('适用 dsh：' + requirement if requirement else '作者未声明 dsh 兼容范围')
        text += '；当前：' + (installed or '版本未知')
        if accepted is False:
            text += '。版本不匹配，安装前请确认作者说明'
        elif accepted is None:
            text += '。兼容性需要确认'
        return {'name': pkg['name'], 'version': str(pkg.get('version', '')), 'author': str(author)[:200] or '未注明',
                'description': str(pkg.get('description', ''))[:1500], 'requiredDsh': requirement,
                'installedDsh': installed, 'compatibility': state, 'compatibilityMessage': text,
                'repository': self.g['repository_url'](pkg)}

    @staticmethod
    def digest(path):
        digest = hashlib.sha256()
        with open(path, 'rb') as stream:
            for chunk in iter(lambda: stream.read(65536), b''):
                digest.update(chunk)
        return digest.hexdigest()

    def preview_path(self, key):
        if not re.fullmatch('[a-f0-9]{32}', key):
            raise ValueError('无效安装预览，请重新解析链接')
        return self.path('plugin-previews/' + key)

    def prune_previews(self):
        root = self.path('plugin-previews')
        os.makedirs(root, exist_ok=True)
        for key in os.listdir(root):
            if not re.fullmatch('[a-f0-9]{32}', key):
                continue
            path = self.preview_path(key)
            if os.path.isdir(path) and not os.path.islink(path) and time.time() - os.path.getmtime(path) > 7200:
                shutil.rmtree(path)

    def inspect(self, request, emit=True):
        if isinstance(request, str):
            request = json.loads(request)
        if not isinstance(request, dict):
            raise ValueError('安装请求格式错误')
        command = request.get('command', '')
        if not isinstance(command, str) or len(command) > 8192:
            raise ValueError('安装链接过长或格式错误')
        parts = shlex.split(command)
        if not parts or parts[0] not in ('download', 'github', 'release', 'npm', 'file'):
            raise ValueError('链接只支持下载和插件包解析')
        expected_hash = request.get('sha256', '')
        if expected_hash and not re.fullmatch('[a-fA-F0-9]{64}', expected_hash):
            raise ValueError('插件校验码格式错误')
        self.prune_previews()
        key = uuid.uuid4().hex
        path = self.preview_path(key); os.makedirs(path)
        prepared = None

        def receive(archive, subdir='', source=''):
            nonlocal prepared
            if not os.path.isfile(archive) or os.path.getsize(archive) > self.g['MAX_DOWNLOAD']:
                raise ValueError('插件包不存在或超过 256 MiB')
            digest = self.digest(archive)
            if expected_hash and digest.lower() != expected_hash.lower():
                raise ValueError('插件 SHA-256 与网站提供的校验码不一致')
            destination = os.path.join(path, 'archive')
            shutil.copyfile(archive, destination)
            staging = os.path.join(path, 'contents')
            self.g['extract_archive'](destination, staging)
            roots = self.g['find_plugin_roots'](staging, subdir)
            items = [self.metadata(root) for root in roots]
            names = [item['name'] for item in items]
            if len(set(names)) != len(names):
                raise ValueError('归档中包含同名插件')
            if request.get('name') and (len(items) != 1 or items[0]['name'] != request['name']):
                raise ValueError('下载包的名称与选中的插件不一致')
            if request.get('version') and (len(items) != 1 or items[0]['version'] != request['version']):
                raise ValueError('下载包版本与网页说明不一致')
            prepared = {'previewId': key, 'source': source, 'command': command, 'sha256': digest,
                        'items': items, 'createdAt': int(time.time()), 'subdir': subdir,
                        'updateFor': request.get('updateFor', ''), 'fromVersion': request.get('fromVersion', '')}
            self.write(os.path.join(path, 'preview.json'), prepared)
            shutil.rmtree(staging)
            return 0
        try:
            if parts[0] == 'download' and len(parts) == 2:
                self.g['cmd_download'](parts[1], consume=receive)
            elif parts[0] == 'github' and len(parts) in (3, 4):
                self.g['cmd_github'](*parts[1:], consume=receive)
            elif parts[0] == 'release' and len(parts) == 4:
                self.g['cmd_release'](*parts[1:], consume=receive)
            elif parts[0] == 'npm' and len(parts) == 2:
                self.g['cmd_npm'](parts[1], consume=receive)
            elif parts[0] == 'file' and len(parts) == 2:
                receive(self.local(parts[1]))
            else:
                raise ValueError('不支持的安装参数')
            if prepared is None:
                raise ValueError('未获得可安装的插件包')
        except Exception:
            shutil.rmtree(path); raise
        if emit:
            self.g['result']('ok', '插件包已解析，请核对作者、版本和兼容信息后确认安装', preview=prepared)
            return 0
        return prepared

    def show_preview(self, key):
        path = self.preview_path(key)
        preview = self.read(os.path.join(path, 'preview.json'), {})
        if not preview or time.time() - preview.get('createdAt', 0) > 3600:
            raise ValueError('安装预览已过期，请重新检查更新')
        self.g['result']('ok', '请确认插件版本与兼容范围', preview=preview)
        return 0

    def discard_preview(self, key):
        path = self.preview_path(key)
        if os.path.isdir(path):
            shutil.rmtree(path)
        self.g['result']('ok', '已取消安装并清理临时包')
        return 0

    def install_preview(self, key):
        path = self.preview_path(key)
        preview = self.read(os.path.join(path, 'preview.json'), {})
        if not preview or time.time() - preview.get('createdAt', 0) > 3600:
            raise ValueError('安装预览已过期，请重新解析链接')
        archive = os.path.join(path, 'archive')
        if not os.path.isfile(archive) or self.digest(archive) != preview.get('sha256'):
            raise ValueError('暂存包已变化，请重新解析链接')
        if preview.get('updateFor'):
            directory = self.g['resolve_plugin_dir'](preview['updateFor'])
            current = self.read(os.path.join(directory, 'package.json'), {}) if directory else {}
            if current.get('version') != preview.get('fromVersion'):
                raise ValueError('插件已被其他操作更新，请重新检查版本')
        expected = {preview['updateFor']: preview.get('fromVersion')} if preview.get('updateFor') else None
        code = self.g['cmd_import'](archive, preview.get('subdir', ''), preview.get('source', ''), expected_versions=expected)
        if code == 0:
            shutil.rmtree(path)
        return code

    def source_command(self, name):
        sources = self.read(self.local(self.g['SOURCES']), {})
        directory = self.g['resolve_plugin_dir'](name)
        pkg = self.read(os.path.join(directory, 'package.json'), {}) if directory else {}
        source = sources.get(name, '') or self.g['repository_url'](pkg)
        if source.startswith('npm:'):
            match = re.fullmatch(r'((?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*)(?:@[^/]+)?', source[4:])
            if not match:
                raise ValueError('npm 来源无法识别，请重新导入')
            return 'npm ' + shlex.quote(match[1] + '@latest')
        uri = urllib.parse.urlsplit(source)
        if uri.hostname in ('registry.npmjs.org', 'registry.npmmirror.com') and '/-/' in uri.path:
            package = urllib.parse.unquote(uri.path.split('/-/')[0]).strip('/')
            if self.builtin.valid_name(package):
                return 'npm ' + shlex.quote(package + '@latest')
        pieces = uri.path.strip('/').split('/')
        if uri.hostname in ('github.com', 'www.github.com') and len(pieces) >= 2:
            owner, repository = pieces[:2]
            if len(pieces) > 2 and pieces[2] == 'releases':
                return 'release ' + shlex.join([owner, repository, 'latest'])
            if len(pieces) > 3 and pieces[2] == 'tree':
                return 'github ' + shlex.join([owner, repository, '/'.join(pieces[3:])])
            if len(pieces) > 2 and pieces[2] == 'archive':
                return 'download ' + shlex.quote(source)
            return 'github ' + shlex.join([owner, repository])
        if uri.scheme == 'https' and uri.hostname:
            return 'download ' + shlex.quote(source)
        raise ValueError('此插件没有可检查的来源，请从作者页面取得新包后导入')

    def check_updates(self, name=''):
        doc = self.builtin.read_manifest() or {}
        candidates = [name] if name else list(doc.get('dependencies', {}))
        states = self.read(self.path('plugin-updates.json'), {})
        checked = []
        for item in candidates:
            if item in self.builtin.OFFICIAL_BUNDLES or item in self.builtin.builtin_names():
                continue
            if not self.builtin.valid_name(item):
                continue
            directory = self.g['resolve_plugin_dir'](item)
            pkg = self.read(os.path.join(directory, 'package.json'), {}) if directory else {}
            if not (pkg.get('dsh') or {}).get('bundle'):
                continue
            status = {'name': item, 'installedVersion': str(pkg.get('version', '')), 'checkedAt': int(time.time()), 'available': False}
            try:
                preview = self.inspect({'command': self.source_command(item), 'name': item,
                                       'updateFor': item, 'fromVersion': pkg.get('version', '')}, False)
                latest = preview['items'][0]
                comparison = compare_versions(latest['version'], status['installedVersion'])
                status.update(latestVersion=latest['version'], previewId=preview['previewId'],
                              available=comparison is not None and comparison > 0,
                              compatibility=latest['compatibilityMessage'], source=preview['source'])
                status['message'] = '有新版本' if status['available'] else '来源未提供更高版本' if comparison is not None else '版本格式不同，请手动核对'
            except Exception as error:
                status['message'] = str(error)
            states[item] = status; checked.append(status)
        with self.builtin.operation_lock():
            latest_states = self.read(self.path('plugin-updates.json'), {})
            latest_states.update({item['name']: item for item in checked})
            self.write(self.path('plugin-updates.json'), latest_states)
        updates = sum(1 for item in checked if item['available'])
        self.g['result']('ok', '已检查 %d 个第三方插件，%d 个可更新；详情见插件卡片' % (len(checked), updates), updates=checked)
        return 0

    def rollback(self, name, expected=''):
        if name in self.builtin.OFFICIAL_BUNDLES or name in self.builtin.builtin_names():
            raise ValueError('内置插件请通过应用更新维护')
        previous = self.history_info(name)
        if not previous:
            raise ValueError('没有可回退的上一版')
        if expected and previous.get('version') != expected:
            raise ValueError('上一版已发生变化，请刷新后重新确认')
        self.g['register_plugin'](os.path.join(self.history_path(name), 'package'), previous.get('source', ''))
        self.g['result']('ok', '已回退 ' + name + ' 至 ' + str(previous.get('version', '')) + '；启用状态保留，重启 Web 生效')
        return 0

    def safe_mode(self, action):
        path = self.path('plugin-safe-mode.json')
        with self.builtin.operation_lock():
            state = self.read(path, {'active': False, 'names': []})
            doc = self.builtin.read_manifest() or {}
            if action == 'on':
                names = [n for n in doc.get('dsh', {}).get('profile', {}).get('bundles', [])
                         if self.builtin.valid_name(n) and n not in self.builtin.OFFICIAL_BUNDLES and n not in self.builtin.builtin_names()]
                remembered = list(dict.fromkeys(state.get('names', []) + names))
                self.write(path, {'active': True, 'names': remembered})
                for name in names:
                    if self.builtin.disable_plugin(name):
                        raise ValueError('无法停用第三方插件：' + name + '；其他插件保持已停用，可重试安全启动')
                    marker = self.builtin.marker_path(name)
                    os.makedirs(os.path.dirname(marker), exist_ok=True)
                    with open(marker, 'w', encoding='utf-8') as out:
                        out.write('DSHA_SAFE_MODE\n')
                self.g['result']('ok', '安全模式已启用，第三方插件暂时停用，数据保留；可重启 Web', safeMode=True, count=len(remembered))
                return 0
            if action == 'off':
                restored = []
                remaining = []
                for name in state.get('names', []):
                    if not self.builtin.valid_name(name):
                        continue
                    marker = self.builtin.marker_path(name)
                    if not os.path.isfile(marker):
                        continue
                    with open(marker, encoding='utf-8') as stream:
                        held = stream.read(128) == 'DSHA_SAFE_MODE\n'
                    # 用户在安全模式期间手动禁用的插件不被批量恢复覆盖。
                    if held and self.g['resolve_plugin_dir'](name):
                        if self.builtin.enable_plugin(name): remaining.append(name)
                        else: restored.append(name)
                self.write(path, {'active': bool(remaining), 'names': remaining})
                self.g['result']('partial' if remaining else 'ok', '已恢复 %d 个插件的启用状态；重启 Web 生效%s' %
                                 (len(restored), ('；仍需修复：' + '、'.join(remaining)) if remaining else ''), safeMode=bool(remaining))
                return 1 if remaining else 0
            if action == 'status':
                self.g['result']('ok', '安全模式已启用' if state.get('active') else '正常启动模式', safeMode=bool(state.get('active')))
                return 0
            raise ValueError('安全模式只支持 on / off / status')
