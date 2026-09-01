const fs = require('fs'), path = require('path');
const root = process.argv[2] || '/';
const resolve = p => path.join(root, p);
const profile = resolve('/root/.dsh/profiles/web/package.json');
fs.mkdirSync(path.dirname(profile), { recursive: true });
let d;
try { d = JSON.parse(fs.readFileSync(profile, 'utf8')); } catch (_) {
  d = { name: 'dsh-profile-web', private: true, dependencies: {}, dsh: { profile: { bundles: [] } } };
}
d.dependencies ||= {};
d.dsh ||= {};
d.dsh.profile ||= {};
d.dsh.profile.bundles ||= [];
d.dsh.profile.patchReload = 'startup';
const names = ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app',
  'dsh-device-shell-guide', 'dsh-task-notifier',
  'dsh-status-overlay', 'dsh-web-mobile'];
for (const n of names) if (!d.dsh.profile.bundles.includes(n)) d.dsh.profile.bundles.push(n);
const roots = {
  'dsh-device-shell-guide': '/root/dsha-device-shell-guide',
  'dsh-task-notifier': '/root/dsha-task-notifier',
  'dsh-status-overlay': '/root/dsha-status-overlay',
  'dsh-web-mobile': '/root/dsha-web-mobile'
};
for (const [n, target] of Object.entries(roots)) d.dependencies[n] = 'link:' + target;
const link = (base, n, target) => {
  const p = path.join(base, n);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  try { fs.rmSync(p, { recursive: true, force: true }); } catch (_) {}
  fs.symlinkSync(target, p);
};
for (const [n, target] of Object.entries(roots)) {
  link(resolve('/root/.dsh/profiles/web/node_modules'), n, target);
  link(resolve('/usr/local/lib/node_modules'), n, target);
}
fs.writeFileSync(profile, JSON.stringify(d, null, 2) + '\n');
console.log('PROFILE_OK');
