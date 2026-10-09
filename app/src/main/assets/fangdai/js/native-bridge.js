/* =========================================================
 * Android WebView 原生桥接层
 * ---------------------------------------------------------
 * 当页面被 Android 端通过 WebView 加载时，宿主 Activity 会
 * 注入一个名为 `NativeBridge` 的 JavaScriptInterface 对象，
 * 提供文件保存 / 分享 / 剪贴板 / 页面跳转等原生能力。
 *
 * 本文件封装这些调用：优先走原生桥，浏览器环境自动回退到
 * 原生 <a download> / Clipboard API / location.href 等行为。
 *
 * 宿主需注入的接口约定（@JavascriptInterface 方法名严格一致）：
 *   - void saveFile(String base64Data, String fileName, String mimeType)
 *   - void shareImage(String base64Data, String fileName)
 *   - void copyText(String text)
 *   - void openPage(String pageKey)   // pageKey: "licai" 等
 *   - boolean isReady()              // 可选，用于探测
 * ========================================================= */
;(function (global) {
  'use strict'

  var native = global.NativeBridge || null
  var isAndroid = !!(
    global.navigator &&
    /;\s*Android\s+/i.test(global.navigator.userAgent || '')
  )

  /**
   * 检测当前是否运行在已注入原生桥的 Android WebView 中。
   */
  function hasNative() {
    return !!(
      native &&
      typeof native.saveFile === 'function' &&
      typeof native.shareImage === 'function'
    )
  }

  /**
   * 触发浏览器端文件下载（回退方案）。
   */
  function browserDownload(base64Url, fileName) {
    var a = document.createElement('a')
    a.href = base64Url
    a.download = fileName
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
  }

  /**
   * 保存文件到本地 / 下载目录。
   * @param {string} base64Data  纯 base64 字符串（不含 data: 前缀）
   * @param {string} fileName    建议保存的文件名
   * @param {string} mimeType    MIME 类型
   * @returns {Promise<{via:'native'|'browser'}>}
   */
  function saveFile(base64Data, fileName, mimeType) {
    if (hasNative()) {
      try {
        native.saveFile(base64Data, fileName, mimeType || 'application/octet-stream')
        return Promise.resolve({ via: 'native' })
      } catch (e) {
        // 原生调用失败，继续走浏览器回退
      }
    }
    // 浏览器回退：拼 dataURL 用 <a download>
    var dataUrl = 'data:' + (mimeType || 'application/octet-stream') + ';base64,' + base64Data
    browserDownload(dataUrl, fileName)
    return Promise.resolve({ via: 'browser' })
  }

  /**
   * 分享 / 保存图片。优先走原生分享面板，否则浏览器下载。
   * @param {string} base64Data  纯 base64（不含 data: 前缀）
   * @param {string} fileName    文件名
   */
  function shareImage(base64Data, fileName) {
    if (hasNative()) {
      try {
        native.shareImage(base64Data, fileName)
        return Promise.resolve({ via: 'native' })
      } catch (e) {}
    }
    var dataUrl = 'data:image/png;base64,' + base64Data
    browserDownload(dataUrl, fileName)
    return Promise.resolve({ via: 'browser' })
  }

  /**
   * 复制文本到剪贴板。原生优先，回退 navigator.clipboard / execCommand。
   */
  function copyText(text) {
    if (native && typeof native.copyText === 'function') {
      try {
        native.copyText(text)
        return Promise.resolve({ via: 'native' })
      } catch (e) {}
    }
    if (global.navigator && global.navigator.clipboard && global.navigator.clipboard.writeText) {
      return global.navigator.clipboard.writeText(text).then(function () {
        return { via: 'browser' }
      })
    }
    // 老浏览器回退
    var ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    try {
      document.execCommand('copy')
    } catch (e) {}
    document.body.removeChild(ta)
    return Promise.resolve({ via: 'browser' })
  }

  /**
   * 外部公共 URL 映射：当原生 WebToolRegistry 未注册该 pageKey 时，
   * 回退到原始博客的公开 URL，保证「对比理财」等按钮永不静默失效。
   */
  var EXTERNAL_URLS = {
    licai: 'https://godweiyang.com/licai/',
  }

  /**
   * 跳转到应用内二级页面（如理财对比页）。
   * 原生优先；原生返回 false（未注册该工具）时回退到外部公开 URL；
   * 浏览器环境回退到相对路径。
   * @param {string} pageKey  页面标识，如 "licai"
   * @returns {Promise<{via:'native'|'external'|'browser', navigated:boolean, url?:string}>}
   */
  function openPage(pageKey) {
    if (native && typeof native.openPage === 'function') {
      var handled
      try {
        handled = native.openPage(pageKey)
      } catch (e) {
        handled = false
      }
      if (handled === true) {
        return Promise.resolve({ via: 'native', navigated: true })
      }
      // 原生未注册该 pageKey — 回退到外部公开 URL
      var externalUrl = EXTERNAL_URLS[pageKey]
      if (externalUrl) {
        try {
          global.location.href = externalUrl
          return Promise.resolve({ via: 'external', navigated: true, url: externalUrl })
        } catch (e) {}
      }
      return Promise.resolve({ via: 'native', navigated: false })
    }
    // 浏览器回退：相对路径（博客同源部署）
    try {
      global.location.href = '/' + pageKey + '/'
      return Promise.resolve({ via: 'browser', navigated: true })
    } catch (e) {
      return Promise.resolve({ via: 'browser', navigated: false })
    }
  }

  /**
   * 将 dataURL 转换为纯 base64 字符串（去掉 data:;base64, 前缀）。
   */
  function dataUrlToBase64(dataUrl) {
    var idx = dataUrl.indexOf(',')
    return idx >= 0 ? dataUrl.slice(idx + 1) : dataUrl
  }

  global.NativeBridge = {
    saveFile: saveFile,
    shareImage: shareImage,
    copyText: copyText,
    openPage: openPage,
    dataUrlToBase64: dataUrlToBase64,
    hasNative: hasNative,
    isAndroid: isAndroid,
  }
})(window)
