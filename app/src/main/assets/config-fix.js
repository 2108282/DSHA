#!/usr/bin/env node
/**
 * dsh-config-fix.js — DSHA 启动前配置自愈。
 * 问题：用户/插件写入的 cordis 配置里 timeoutMs 一旦超过 schema 上限(300000)，
 *       WebUI 启动即抛 ValidationError 直接崩溃退出（如 timeoutMs: 600000）。
 * 作用：把各配置里超上限的 timeoutMs 数值钳回 300000（幂等，找不到/出错直接退出 0）。
 */
'use strict';

const fs = require('fs');

const LIMIT = 300000;
const FILES = [
  '/root/.dsh/profiles/web/cordis.patch.yml',
  '/root/.dsh/profiles/web/cordis.yml',
  '/root/.dsh/profiles/main/cordis.patch.yml',
  '/root/.dsh/profiles/main/cordis.yml',
  '/root/.dsh/cordis.yml',
  '/root/.dsh/cordis.patch.yml',
];
const RE = /(\btimeoutMs\s*:\s*)(-?\d+)/g;

let fixed = 0;
for (const file of FILES) {
  let text = null;
  try { text = fs.readFileSync(file, 'utf8'); } catch (_) { continue; }
  if (text === null) continue;
  let changed = false;
  const next = text.replace(RE, (match, head, value) => {
    const n = Number.parseInt(value, 10);
    if (!Number.isFinite(n) || n <= LIMIT) return match;
    changed = true;
    fixed += 1;
    return head + String(LIMIT);
  });
  if (changed) {
    try { fs.writeFileSync(file, next); } catch (_) { /* 只读也不阻塞 */ }
  }
}

if (fixed > 0) {
  console.log('[DSHA] config-fix: 已自动钳制 ' + fixed + ' 处超限 timeoutMs → ' + LIMIT);
}
