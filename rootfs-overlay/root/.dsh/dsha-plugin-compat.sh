#!/bin/bash
# DSHA: 第三方插件兼容补丁（支持界面开关 + 白名单放行 + 容错防崩 + 启动自愈）
set -u

PLUGINS_CLIENT=$(find /usr/local/lib/node_modules/@deepseek-ai -path "*dsh-client-ui-settings-plugins/lib/client.js" 2>/dev/null | head -1)
MODULES_CLIENT=$(find /usr/local/lib/node_modules/@deepseek-ai -path "*dsh-client-modules/lib/client.js" 2>/dev/null | head -1)

# 1. 修复并增强 settings-plugins (自适应 0.1.6 新版与 0.1.5 旧版)
if [ -n "$PLUGINS_CLIENT" ] && [ -f "$PLUGINS_CLIENT" ]; then
  python3 - "$PLUGINS_CLIENT" << 'PY'
import sys, re

path = sys.argv[1]
with open(path, "r", encoding="utf-8") as f:
    src = f.read()

MARKER_V2 = "__DSHA_PLUGINS_WHITELIST_BYPASS_V2__"
MARKER_V1 = "__DSHA_THIRD_PARTY_PLUGINS_SWITCH__"

patched = False

# ================= 新版 DSH (0.1.6+) 自适应适配 =================
if MARKER_V2 not in src:
    changed = False

    # 1. 补充 settings.plugin.item 插槽声明，防止第三方配置卡片被废弃或丢弃
    p_slot = r'(children:\s*\{\s*["\']settings\.plugins\.tab["\']:\s*\{\s*kind:\s*["\']list["\'],\s*scope:\s*["\']root["\']\s*\}\s*)(\})'
    if re.search(p_slot, src):
        src = re.sub(p_slot, r'\1, "settings.plugin.item": { kind: "keyed" }\2', src, count=1)
        changed = True

    # 2. 放开白名单判定 (支持 localStorage 开关控制，默认放开第三方插件配置)
    p_available = r'const\s+available\s*=\s*namespaces\.some\(\(namespace\)\s*=>\s*served\.has\(namespace\)\);'
    if re.search(p_available, src):
        bypass_code = (
            f'/* {MARKER_V2} */ const available = typeof localStorage !== "undefined" && '
            'localStorage.getItem("dsh.allow_third_party_plugins") === "false" ? '
            'namespaces.some((namespace) => served.has(namespace)) : true;'
        )
        src = re.sub(p_available, bypass_code, src, count=1)
        changed = True

    # 3. 在设置页渲染中挂载第三方卡片插槽 (同时覆盖单标签模式与多标签模式)
    old_single_tail = 'children: renderSlot("settings.plugins.tab", {}, { only: single.id })\n\t\t\t\t\t})'
    new_single_tail = (
        'children: [(0, react_jsx_runtime.jsx)("div", { children: renderSlot("settings.plugins.tab", {}, { only: single.id }) }), '
        '(0, react_jsx_runtime.jsx)("div", { style: { marginTop: "16px", display: "flex", flexDirection: "column", gap: "10px" }, children: renderSlot("settings.plugin.item") })]\n\t\t\t\t\t})'
    )
    if old_single_tail in src:
        src = src.replace(old_single_tail, new_single_tail)
        changed = True

    old_multi_tail = '})] })\n\t\t\t\t]\n\t\t\t});\n\t\t}'
    new_multi_tail = (
        '})] }),\n\t\t\t\t\t(0, react_jsx_runtime.jsx)("div", {\n\t\t\t\t\t\tstyle: { marginTop: "16px", display: "flex", flexDirection: "column", gap: "10px" },\n\t\t\t\t\t\tchildren: renderSlot("settings.plugin.item")\n\t\t\t\t\t})\n\t\t\t\t]\n\t\t\t});\n\t\t}'
    )
    if old_multi_tail in src:
        src = src.replace(old_multi_tail, new_multi_tail)
        changed = True

    if changed:
        with open(path, "w", encoding="utf-8") as f:
            f.write(src)
        print("SETTINGS_PLUGINS_V2_PATCHED_OK")
        patched = True
else:
    print("SETTINGS_PLUGINS_V2_ALREADY_PATCHED")
    patched = True

# ================= 旧版 DSH (0.1.5 及更早) 向下兼容 =================
if not patched and MARKER_V1 not in src:
    OLD_PUBLISH = "const namespaces = this.entries().flatMap((entry) => entry.options.key !== void 0 && served.has(entry.options.key) ? [entry.options.key] : []);"
    NEW_PUBLISH = """/* """ + MARKER_V1 + """ */
        const allowThirdParty = typeof localStorage !== 'undefined' ? localStorage.getItem('dsh.allow_third_party_plugins') !== 'false' : true;
        const allKeys = this.entries().flatMap((entry) => entry.options.key !== void 0 ? [entry.options.key] : []);
        const namespaces = allowThirdParty
            ? Array.from(new Set([...served, ...allKeys]))
            : this.entries().flatMap((entry) => entry.options.key !== void 0 && served.has(entry.options.key) ? [entry.options.key] : []);"""

    OLD_TAB = 'function ConfigurablePluginsTab(props) {'
    NEW_TAB = """function ConfigurablePluginsTab(props) {
        const [allowThirdParty, setAllowThirdParty] = (0, react.useState)(() => typeof localStorage !== 'undefined' ? localStorage.getItem('dsh.allow_third_party_plugins') !== 'false' : true);
        const toggleSwitch = (e) => {
            const next = e.target.checked;
            setAllowThirdParty(next);
            if (typeof localStorage !== 'undefined') localStorage.setItem('dsh.allow_third_party_plugins', String(next));
            window.dispatchEvent(new Event('dsh-third-party-toggle'));
        };
        (0, react.useEffect)(() => {
            const handler = () => setAllowThirdParty(localStorage.getItem('dsh.allow_third_party_plugins') !== 'false');
            window.addEventListener('dsh-third-party-toggle', handler);
            return () => window.removeEventListener('dsh-third-party-toggle', handler);
        }, []);
        const switchBar = (0, react_jsx_runtime.jsxs)("div", {
            style: { display: "flex", alignItems: "center", justifyContent: "space-between", padding: "12px 16px", marginBottom: "16px", background: "var(--dsw-alias-bg-hover, rgba(127,127,127,0.08))", borderRadius: "10px", border: "1px solid var(--dsw-alias-border-base, rgba(127,127,127,0.2))" },
            children: [
                (0, react_jsx_runtime.jsxs)("div", {
                    children: [
                        (0, react_jsx_runtime.jsx)("div", { style: { fontWeight: 600, fontSize: "14px" }, children: "显示第三方插件配置" }),
                        (0, react_jsx_runtime.jsx)("div", { style: { fontSize: "12px", color: "var(--dsw-alias-label-tertiary, #888)", marginTop: "2px" }, children: "放开官方白名单限制，自动渲染所有已安装第三方插件的设置卡片" })
                    ]
                }),
                (0, react_jsx_runtime.jsx)("input", {
                    type: "checkbox",
                    checked: allowThirdParty,
                    onChange: toggleSwitch,
                    style: { width: "18px", height: "18px", cursor: "pointer", accentColor: "var(--dsw-alias-brand, #1677ff)" }
                })
            ]
        });"""

    OLD_RETURN = "if (namespaces.length > 0) return (0, react_jsx_runtime.jsx)(\"ul\", {"
    NEW_RETURN = """if (namespaces.length > 0) return (0, react_jsx_runtime.jsxs)(react.Fragment, {
        children: [
            switchBar,
            (0, react_jsx_runtime.jsx)(\"ul\", {"""

    OLD_EMPTY_RETURN = "return loaded ? (0, react_jsx_runtime.jsx)(\"p\", {"
    NEW_EMPTY_RETURN = """return loaded ? (0, react_jsx_runtime.jsxs)(react.Fragment, {
        children: [
            switchBar,
            (0, react_jsx_runtime.jsx)(\"p\", {"""

    if OLD_PUBLISH in src and OLD_TAB in src:
        src = src.replace(OLD_PUBLISH, NEW_PUBLISH)
        src = src.replace(OLD_TAB, NEW_TAB)
        src = src.replace(OLD_RETURN, NEW_RETURN)
        src = src.replace("});\n\t\t\treturn loaded ?", "})]\n\t\t\t});\n\t\t\treturn loaded ?")
        src = src.replace(OLD_EMPTY_RETURN, NEW_EMPTY_RETURN)
        src = src.replace("children: t(\"empty\")\n\t\t\t}) : null;", "children: t(\"empty\")\n\t\t\t})]\n\t\t\t}) : null;")
        with open(path, "w", encoding="utf-8") as f:
            f.write(src)
        print("SETTINGS_PLUGINS_V1_PATCHED_OK")
        patched = True
PY
fi

# 2. 修复 client-modules (为模块加载器注入安全容错，防止第三方插件缺少小依赖直接整盘崩溃)
if [ -n "$MODULES_CLIENT" ] && [ -f "$MODULES_CLIENT" ]; then
  python3 - "$MODULES_CLIENT" << 'PY'
import sys

path = sys.argv[1]
with open(path, "r", encoding="utf-8") as f:
    src = f.read()

MARKER = "__DSHA_MODULE_LOADER_GUARD__"
if MARKER not in src:
    OLD_THROW = 'throw new Error(`client-modules: require("${spec}") missed the module table — not a platform seed word, not a materialized module, and no registered package factory (a build-time externals drift, or a dynamic dependency that did not arrive)`);'
    NEW_THROW = """/* """ + MARKER + """ */
                    console.warn(`[dsh-plugin-guard] require("${spec}") missed module table, returning graceful fallback.`);
                    return new Proxy({}, {
                        get(target, prop) {
                            if (prop === '__esModule') return true;
                            if (prop === 'default') return () => null;
                            return () => null;
                        }
                    });"""
    if OLD_THROW in src:
        src = src.replace(OLD_THROW, NEW_THROW)
        with open(path, "w", encoding="utf-8") as f:
            f.write(src)
        print("MODULES_CLIENT_PATCHED_OK")
    else:
        print("MODULES_CLIENT_NOT_MATCHED")
else:
    print("MODULES_CLIENT_ALREADY_PATCHED")
PY
fi
