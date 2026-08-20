/**
 * dsh-device-shell-guide — DSHA builtin server plugin（rc.8 全局 npm 模式版）。
 *
 * 启用后，为每个新对话（亦是每次请求组装）的系统提示追加一段「设备操作能力」
 * 引导：让 agent 知道它运行在用户 Android 手机上、并可通过 ADB 无线通道
 * （/root/dsh-bin/adb-shell，uid=2000 免 root）或 Shizuku 桥真实干预实体机。
 *
 * 实现：注册一个 system-prompt section（order 150，落在 tool-guidance 带
 * 100–199），位于 persona(0) 之后、工具描述附近，随每次汇编生效。
 *
 * 挂载方式（rc.8 全局模式）：作为 bundle 注册进 web profile 的
 * dsh.profile.bundles + dependencies（file: 指向本机目录，零网络）。
 */

/** 依赖的服务，确保先于 apply 就绪（systemPrompt 注册点）。 */
export const inject = ['systemPrompt']

/** 注入到系统提示的引导段（针对 DSHA 手机端环境）。 */
const PROMPT = [
  '【设备操作能力 · DSHA】你正运行在用户 Android 手机的容器里，可以直接干预这台实体手机：',

  '■ 主通道（ADB 无线，已配对）：',
  '  /root/dsh-bin/adb-shell "命令"          # shell 级（uid=2000）',
  '  /root/dsh-bin/adb-shell --su "命令"     # root 级（需手机已 root，Magisk/KernelSU）',
  '  （若包装命令不存在，直接用：python3 /root/.dsh/adb-shell.py "命令"）',

  '■ 备选通道（Shizuku 桥，可能未就绪）：',
  '  curl -s "http://127.0.0.1:3090/exec?cmd=..."',

  '■ ⚠️ 注意：不要用 /root/dsh-bin/adb 或裸 adb 命令——那是守卫包装脚本，会失败。',

  '■ 常用操作：',
  '  - 查设备信息：getprop ro.product.model',
  '  - 查前台应用：dumpsys window | grep mCurrentFocus',
  '  - 启动应用：am start -n 包名/Activity',
  '  - 抓日志：dumpsys / logcat',
  '  - 读写共享目录：/sdcard',

  '■ 权限边界：默认 shell 级（uid=2000）；手机已 root 时用 --su 提权执行（如 pm uninstall 系统应用）。',
  '■ 安全：删除/格式化/重启/卸载等破坏性命令，动手前先说明后果；操作要可解释（做了什么、为何、影响）。',
  '■ 语言要求：与用户交流请一律使用中文回复。',
].join('\n')

/**
 * Plugin entry: register the guidance section.
 * @param {import('@deepseek-ai/cordis').Context} ctx
 */
export function apply(ctx) {
  ctx.systemPrompt.section({
    name: 'dsh:device-shell-guide',
    order: 150,
    text: PROMPT,
  })
}
