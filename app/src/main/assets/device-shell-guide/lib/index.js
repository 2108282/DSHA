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
  '- 设备 shell（Android shell，uid=2000，已配对、无需 root）：/root/dsh-bin/adb-shell "命令"',
  '- 备选通道（Shizuku 桥）：curl -s "http://127.0.0.1:3090/exec?cmd=..."',
  '- 常用操作：查设备信息(getprop)、启动应用(am start -n 包名/Activity)、查进程、读写 /sdcard 共享目录、抓日志(dumpsys/logcat)。',
  '- 权限边界：shell 级（非 root），改系统分区/卸载等需提权的操作会失败，失败就如实告诉用户。',
  '- 安全：涉及删除/格式化/重启/卸载等破坏性命令，动手前先向用户说明后果；操作要可解释（做了什么、为何、影响），并在最终回复里简要总结。',
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
