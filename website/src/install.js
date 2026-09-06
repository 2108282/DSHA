(function () {
  'use strict';
  var page = document.querySelector('[data-install-page]');
  if (!page) return;
  var status = page.querySelector('[data-install-status]');
  var actions = page.querySelector('[data-install-actions]');
  var source = document.getElementById('plugin-source');
  var note = page.querySelector('[data-install-note]');
  function parse(query) {
    var values = {};
    if (!query) return values;
    query.replace(/^\?/, '').split('&').forEach(function (part) {
      var pair = part.split('='), key = decodeURIComponent(pair.shift().replace(/\+/g, ' '));
      var value = decodeURIComponent(pair.join('=').replace(/\+/g, ' '));
      if (['url','sha256','name','version','builtin'].indexOf(key) < 0 || Object.prototype.hasOwnProperty.call(values,key) || /[\x00-\x1f\x7f]/.test(value)) throw new Error('安装链接包含无效或重复参数，请重新选择插件。');
      values[key] = value;
    });
    return values;
  }
  function encode(values) {
    return Object.keys(values).map(function (key) { return key + '=' + encodeURIComponent(values[key]); }).join('&');
  }
  function render(values) {
    actions.hidden = true;
    var name = /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/;
    if (values.builtin) {
      if (values.url || !name.test(values.builtin) || values.builtin.length > 214) throw new Error('内置插件入口无效。');
    } else {
      if (!values.url || values.url.length > 6000) throw new Error('请输入插件链接或 npm 包名。');
      var parsed = document.createElement('a'); parsed.href = values.url;
      var https = /^https:\/\//i.test(values.url) && parsed.hostname && !parsed.username && !parsed.password;
      var npm = /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*(?:@[a-zA-Z0-9.^~*+_-]+)?$/.test(values.url);
      if (!https && !npm) throw new Error('请使用 HTTPS 插件链接或 npm 包名。');
      if (values.sha256 && !/^[a-f0-9]{64}$/i.test(values.sha256)) throw new Error('插件摘要无效。');
      if (values.name && (!name.test(values.name) || values.name.length > 214)) throw new Error('插件名称无效。');
      if (values.version && values.version.length > 100) throw new Error('插件版本无效。');
      source.value = values.url;
    }
    var query = encode(values), scheme = 'dsha://install?' + query;
    var fallback = 'https://dsha.cc/install/?' + query;
    var android = /Android/i.test(navigator.userAgent);
    page.querySelector('[data-open-app]').href = android ? 'intent://install?' + query + '#Intent;scheme=dsha;package=com.dsh.client;S.browser_fallback_url=' + encodeURIComponent(fallback) + ';end' : scheme;
    page.querySelector('[data-open-scheme]').href = scheme;
    actions.hidden = false;
    status.textContent = values.builtin ? '打开插件管理，查看 ' + values.builtin + ' 的状态。' : '已准备好链接。请在 DSHA 中查看实际包信息并确认安装。';
    note.textContent = values.sha256 ? 'App 将核对目录中的 SHA-256 摘要。插件具体信息以下载包为准。' : '此链接由你或分享者提供。请在 App 中核对插件来源、版本及兼容范围。';
  }
  page.querySelector('[data-install-form]').addEventListener('submit', function (event) {
    event.preventDefault();
    try { var values = {url:source.value.trim()}; render(values); history.replaceState(null, '', '/install/?' + encode(values)); }
    catch (error) { status.textContent = error.message; }
  });
  if (location.search) try { render(parse(location.search)); } catch (error) { actions.hidden=true; status.textContent='无法打开安装链接：' + error.message; }
})();
