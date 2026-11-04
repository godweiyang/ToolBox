/* 只运行于用户主动连接的贝壳地图标签；不读取 Cookie/localStorage/账号字段。 */
;(() => {
  const surface = location.hostname === 'map.ke.com' ? 'map' : 'profile'
  if (surface === 'profile' && !/^[a-z]{2,12}\.ke\.com$/.test(location.hostname)) return
  if (surface === 'profile' && !/^\/(xiaoqu\/\d+\/|ershoufang\/\d+\.html)/.test(location.pathname))
    return
  if (window.__blogHousingStop) window.__blogHousingStop()
  const root = 'http://127.0.0.1:17532',
    token = '__PAIR_TOKEN__'
  let stopped = false,
    busy = false
  const city = () =>
    surface === 'map'
      ? (location.pathname.match(/^\/map\/(\d{6})\//) || [])[1]
      : window.__amenityCity || '__CITY_ID__'
  async function readDetail(job) {
    const u = new URL(job.params.url)
    const pattern = job.action === 'profile' ? /^\/xiaoqu\/(\d+)\/$/ : /^\/ershoufang\/\d+\.html$/
    if (u.origin !== location.origin || !pattern.test(u.pathname) || u.search || u.hash)
      throw new Error('详情来源不允许')
    const ctrl = new AbortController(),
      timeout = setTimeout(() => ctrl.abort(), 18000)
    let response
    try {
      response = await fetch(u.href, { credentials: 'include', signal: ctrl.signal })
      if (!response.ok || response.url !== u.href)
        throw new Error('贝壳访问拒绝或需要验证，请在详情页处理')
      const doc = new DOMParser().parseFromString(await response.text(), 'text/html')
      if (job.action === 'profile') {
        const id = u.pathname.match(pattern)[1]
        const fields = {}
        doc.querySelectorAll('.xiaoquInfo .xiaoquInfoLabel').forEach((label) => {
          const k = label.textContent.trim(),
            v = label.parentElement.querySelector('.xiaoquInfoContent')?.textContent.trim()
          if (
            [
              '容积率',
              '绿化率',
              '物业费',
              '物业公司',
              '建筑类型',
              '建成年代',
              '交易权属',
              '房屋总数',
              '楼栋总数',
            ].includes(k)
          )
            fields[k] = v
        })
        if (!Object.keys(fields).length) throw new Error('小区基础资料缺失或需要验证')
        return { id, fields }
      }
      const link = [...doc.querySelectorAll('a[href*="/xiaoqu/"]')]
        .map((e) => e.getAttribute('href'))
        .find((h) => new RegExp('/xiaoqu/' + job.params.id + '/').test(h))
      if (!link) throw new Error('房源小区归属无法核实')
      const attrs = {}
      doc.querySelectorAll('.base .content li').forEach((el) => {
        const label = el.querySelector('.label')?.textContent.trim()
        if (
          ![
            '房屋户型',
            '建筑面积',
            '户型结构',
            '建筑类型',
            '所在楼层',
            '套内面积',
            '房屋朝向',
            '建筑结构',
            '装修情况',
            '梯户比例',
            '配备电梯',
          ].includes(label)
        )
          return
        const clone = el.cloneNode(true)
        clone.querySelectorAll('.label, .info, .tips, a').forEach((n) => n.remove())
        attrs[label] = clone.textContent.replace(/\s+/g, ' ').trim()
      })
      const rooms = [...doc.querySelectorAll('#infoList .row')]
        .map((el) => [...el.querySelectorAll('.col')].map((x) => x.textContent.trim()))
        .filter((x) => x.length >= 4)
        .map((x) => ({ name: x[0], area: x[1], orientation: x[2], window: x[3] }))
      if (!Object.keys(attrs).length) throw new Error('户型基础资料缺失或需要验证')
      return { id: job.params.id, attributes: attrs, rooms }
    } finally {
      clearTimeout(timeout)
    }
  }
  const badge = document.createElement('div')
  badge.style.cssText =
    'position:fixed;bottom:20px;left:20px;z-index:999999;background:#fff;border:2px solid #14688f;border-radius:8px;padding:12px 16px;font:14px Microsoft YaHei,sans-serif;color:#14688f;box-shadow:0 2px 10px #0002'
  const text = document.createElement('span')
  const stop = document.createElement('button')
  stop.textContent = '断开'
  stop.style.cssText = 'margin-left:12px;padding:6px;cursor:pointer'
  badge.append(text, stop)
  document.body.append(badge)
  const finish = () => {
    stopped = true
    clearInterval(timer)
    badge.remove()
  }
  window.__blogHousingStop = finish
  stop.onclick = finish
  async function json(url, opts, ms = 18000) {
    const ctrl = new AbortController(),
      timer = setTimeout(() => ctrl.abort(), ms)
    try {
      const response = await fetch(url, { ...opts, signal: ctrl.signal })
      if (!response.ok) throw new Error('HTTP ' + response.status)
      if (!String(response.headers.get('content-type')).includes('json'))
        throw new Error('贝壳需要登录/验证或拒绝访问，请在贝壳页面处理')
      return await response.json()
    } finally {
      clearTimeout(timer)
    }
  }
  async function loop() {
    if (stopped || busy) return
    const cid = city()
    if (!cid) {
      text.textContent = '请先切换到贝壳地图找房页面'
      return
    }
    busy = true
    try {
      const result = await json(
        root +
          '/api/housing/poll?token=' +
          encodeURIComponent(token) +
          '&cityId=' +
          cid +
          '&surface=' +
          surface +
          '&host=' +
          encodeURIComponent(location.hostname),
        { credentials: 'omit' },
        5000,
      )
      if (!result.ok) throw new Error(result.error || '连接失败')
      text.textContent = '博客本地服务已连接'
      const job = result.data.job
      if (job) {
        let payload
        try {
          if (['profile', 'floorplan'].includes(job.action)) {
            if (surface !== 'profile' || job.params.cityId !== cid)
              throw new Error('详情城市不匹配')
            payload = { body: { errno: 0, data: await readDetail(job) } }
          } else {
            if (
              surface !== 'map' ||
              !['sug', 'houselist'].includes(job.action) ||
              job.params.cityId !== cid ||
              job.params.dataSource !== 'ESF'
            )
              throw new Error('查询类型或城市不匹配')
            const keys =
              job.action === 'sug'
                ? ['cityId', 'dataSource', 'query', 'pageSize', 'hlsPreTag', 'hlsPostTag']
                : [
                    'cityId',
                    'dataSource',
                    'curPage',
                    'condition',
                    'type',
                    'resblockId',
                    'maxLatitude',
                    'minLatitude',
                    'maxLongitude',
                    'minLongitude',
                  ]
            const params = new URLSearchParams()
            keys.forEach((key) => {
              if (job.params[key] != null) params.set(key, job.params[key])
            })
            const body = await json(
              '/proxyApi/i.c-pc-webapi.ke.com/map/' + job.action + '?' + params,
              { credentials: 'include' },
            )
            if (body.errno !== 0 || !body.data || body.data.isShowLoginBtn)
              throw new Error('贝壳需要登录/验证或拒绝访问，请在贝壳页面处理后重试')
            // 仅回传房源/小区公开字段，不回传账号或任意请求头。
            const data = body.data
            const clean =
              job.action === 'sug'
                ? {
                    list: (data.list || []).map((r) => ({
                      itemId: r.itemId,
                      itemType: r.itemType,
                      cityId: r.cityId,
                      itemName: r.itemName,
                      itemAlias: r.itemAlias,
                      pointLng: r.pointLng,
                      pointLat: r.pointLat,
                      desc: r.desc,
                      count: r.count,
                    })),
                  }
                : {
                    list: (data.list || []).map((r) => ({
                      title: r.title,
                      desc: r.desc,
                      priceStr: r.priceStr,
                      unitPriceStr: r.unitPriceStr,
                      actionUrl: r.actionUrl,
                      tags: r.tags,
                    })),
                    total: data.total,
                    hasMore: data.hasMore,
                    resblockCard: data.resblockCard,
                    page: data.page,
                    pagesize: data.pagesize,
                  }
            payload = { body: { errno: 0, data: clean } }
          }
        } catch (e) {
          payload = { error: String(e.message || e) }
          text.textContent = payload.error
        }
        await json(
          root + '/api/housing/response',
          {
            method: 'POST',
            credentials: 'omit',
            headers: { 'content-type': 'application/json' },
            body: JSON.stringify({ token, id: job.id, payload }),
          },
          5000,
        )
      }
    } catch (e) {
      text.textContent = '连接中断：' + String(e.message || e)
    } finally {
      busy = false
    }
  }
  const timer = setInterval(loop, 1200)
  loop()
})()
