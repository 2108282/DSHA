import { execSync } from 'node:child_process';

/** 执行受保护的 Shell 命令 */
export function runShell(cmd, timeout = 5000) {
  try {
    return execSync(cmd, {
      timeout,
      encoding: 'utf-8',
      stdio: ['ignore', 'pipe', 'pipe']
    }).trim();
  } catch (e) {
    return `执行错误: ${e.stderr || e.message}`;
  }
}

/** 硬件与系统控制功能集 */
export const SYSTEM_TOOLS = {
  // 1. WiFi 控制
  wifi: ({ action }) => {
    if (action === 'enable') {
      runShell('cmd wifi set-wifi-enabled enabled');
      return 'WiFi 已开启';
    } else if (action === 'disable') {
      runShell('cmd wifi set-wifi-enabled disabled');
      return 'WiFi 已关闭';
    } else {
      return runShell('cmd wifi status');
    }
  },

  // 2. 蓝牙控制
  bluetooth: ({ action }) => {
    if (action === 'enable') {
      runShell('cmd bluetooth_manager enable');
      return '蓝牙已开启';
    } else if (action === 'disable') {
      runShell('cmd bluetooth_manager disable');
      return '蓝牙已关闭';
    }
    return '参数错误';
  },

  // 3. 音量调节
  volume: ({ stream = 3, level = 8 }) => {
    runShell(`cmd media_session volume --stream ${stream} --set ${level}`);
    return `已将音量设置为 ${level}`;
  },

  // 4. 静默设置闹钟
  alarm: ({ hour, minutes, message = '闹钟' }) => {
    const cmd = `am start -a android.intent.action.SET_ALARM --ei android.intent.extra.alarm.HOUR ${hour} --ei android.intent.extra.alarm.MINUTES ${minutes} --ez android.intent.extra.alarm.SKIP_UI true --es android.intent.extra.alarm.MESSAGE "${message}"`;
    runShell(cmd);
    return `已为您静默设置 ${hour}:${minutes < 10 ? '0' + minutes : minutes} 的${message}`;
  },

  // 5. 静默倒计时器
  timer: ({ seconds = 300, message = '计时' }) => {
    const cmd = `am start -a android.intent.action.SET_TIMER --ei android.intent.extra.alarm.LENGTH ${seconds} --ez android.intent.extra.alarm.SKIP_UI true --es android.intent.extra.alarm.MESSAGE "${message}"`;
    runShell(cmd);
    return `已为您开启 ${seconds} 秒的${message}`;
  },

  // 6. 媒体播放控制
  media: ({ action }) => {
    const keyMap = { play_pause: 85, next: 87, prev: 88 };
    const code = keyMap[action] || 85;
    runShell(`input keyevent ${code}`);
    return `媒体操作已执行 (${action})`;
  },

  // 7. 设备状态/电量查询
  battery: () => {
    const out = runShell('dumpsys battery | grep -E "level|status|temperature"');
    return out || '无法获取电池状态';
  },

  // 8. 抓取系统最近崩溃日志
  crashReport: () => {
    const out = runShell('logcat -b crash -d -v time | tail -n 60');
    return out ? `最近崩溃日志:\n${out}` : '最近无应用崩溃记录';
  },

  // 9. GUI 模拟点击与自动化
  tapText: ({ text }) => {
    return callBridge(`/app/ui/tap?text=${encodeURIComponent(text)}`);
  },

  tap: ({ x, y }) => {
    return callBridge(`/app/ui/tap?x=${x}&y=${y}`);
  },

  swipe: ({ x1, y1, x2, y2, ms = 300 }) => {
    return callBridge(`/app/ui/swipe?x1=${x1}&y1=${y1}&x2=${x2}&y2=${y2}&ms=${ms}`);
  },

  input: ({ text }) => {
    return callBridge(`/app/ui/input?text=${encodeURIComponent(text)}`);
  },

  dumpUi: () => {
    return callBridge('/app/ui/dump');
  },

  screenshot: () => {
    return callBridge('/app/ui/screenshot');
  },

  // 10. 执行通用受控 Shell 命令
  exec: ({ command }) => {
    return runShell(command);
  }
};

/** 通过 3095 桥发起本地请求 */
function callBridge(path) {
  try {
    let token = '';
    try {
      const fs = import('node:fs');
      // 读取 bridge token
      const tPath = '/root/.dsh/.bridge_token';
      const fsSync = require('node:fs');
      if (fsSync.existsSync(tPath)) {
        token = fsSync.readFileSync(tPath, 'utf-8').trim();
      }
    } catch {}
    const sep = path.includes('?') ? '&' : '?';
    const fullUrl = `http://127.0.0.1:3095${path}${token ? sep + 'token=' + encodeURIComponent(token) : ''}`;
    const cmd = `curl -s -m 5 "${fullUrl}"`;
    return runShell(cmd);
  } catch (e) {
    return `UI 操作失败: ${e.message}`;
  }
}
