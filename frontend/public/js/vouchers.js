const $ = (id) => document.getElementById(id);
const state = { current: 1, total: 0, busy: false };
const yuan = (cents) => `¥${(Number(cents || 0) / 100).toFixed(2)}`;
const loginUrl = '/login2.html?redirect=' + encodeURIComponent('/vouchers.html?tab=mine');
let noticeTimer;

function notice(message) {
  const box = $('notice');
  box.textContent = message;
  box.classList.add('show');
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => box.classList.remove('show'), 5000);
}

async function api(path, options = {}) {
  const token = sessionStorage.getItem('token');
  const response = await fetch(`/api${path}`, {
    ...options,
    headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { authorization: token } : {}), ...options.headers },
  });
  const raw = await response.text();
  let body;
  try { body = /^\d{16,}$/.test(raw.trim()) ? raw.trim() : (raw ? JSON.parse(raw) : null); }
  catch { body = raw; }
  if (response.status === 401) throw new Error('登录已过期，请重新登录。');
  if (!response.ok || body?.success === false || (typeof body?.code === 'number' && body.code !== 200)) {
    throw new Error(body?.errorMsg || body?.msg || `请求失败（${response.status}）`);
  }
  return body;
}

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function link(text, href, className) {
  const node = element('a', text, className);
  node.href = href;
  return node;
}

function dateLabel(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '时间待定' : date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function empty(message, actionText, href) {
  const node = element('p', message + ' ', 'empty');
  if (actionText) node.append(link(actionText, href));
  return node;
}

function activityCard(activity) {
  const card = element('article', undefined, 'voucher-card');
  const image = document.createElement('img');
  image.className = 'shop-photo';
  image.src = (activity.shopImages || '').split(',')[0] || '/imgs/icons/default-icon.png';
  image.alt = `${activity.shopName || '商户'}的图片`;
  image.loading = 'lazy';
  image.onerror = () => { image.src = '/imgs/icons/default-icon.png'; image.onerror = null; };
  const content = element('div', undefined, 'card-content');
  content.append(element('p', activity.shopName || '附近商户', 'shop-name'),
    element('h3', activity.title || '到店券', 'voucher-title'),
    element('p', activity.subTitle || '到店使用', 'voucher-subtitle'));
  const price = element('div', undefined, 'voucher-meta');
  price.append(element('strong', yuan(activity.payValue), 'price'),
    element('span', `抵用 ${yuan(activity.actualValue)}`, 'face-value'));
  content.append(price, element('div', undefined, 'card-divider'));
  const foot = element('div', undefined, 'card-foot');
  const detail = element('div', undefined, 'activity-detail');
  detail.append(element('div', `${dateLabel(activity.beginTime)} — ${dateLabel(activity.endTime)}`),
    element('strong', `剩余 ${activity.stock ?? 0} 张`), document.createElement('br'),
    link('查看商户详情 ↗', `/shop-detail.html?id=${encodeURIComponent(activity.shopId)}`, 'shop-link'));
  const now = Date.now();
  const upcoming = new Date(activity.beginTime).getTime() > now;
  const soldOut = Number(activity.stock || 0) < 1;
  const button = element('button', upcoming ? '即将开始' : (soldOut ? '已抢完' : '抢券下单'), 'primary-btn');
  button.type = 'button';
  button.disabled = upcoming || soldOut;
  button.addEventListener('click', async () => {
    if (!sessionStorage.getItem('token')) { location.href = loginUrl; return; }
    button.disabled = true;
    try {
      await api(`/voucher-order/seckill/${encodeURIComponent(activity.id)}`, { method: 'POST' });
      notice('抢券下单成功，请在“我的券”完成余额支付。');
      showTab('mine');
      loadActivities();
    } catch (error) { notice(error.message); button.disabled = false; }
  });
  foot.append(detail, button);
  content.append(foot);
  card.append(image, content);
  return card;
}

async function loadActivities() {
  try {
    const body = await api('/voucher/active');
    const activities = body?.data || [];
    $('activity-count').textContent = activities.length;
    $('activities').replaceChildren(...(activities.length ? activities.map(activityCard)
      : [empty('目前没有正在进行的限时券。', '先去看看附近商户', '/index.html')]));
  } catch (error) {
    $('activities').replaceChildren(empty(error.message));
  }
}

function orderCard(order) {
  const card = element('article', undefined, 'order-card');
  const top = element('div', undefined, 'order-top');
  const title = element('div');
  title.append(element('p', order.shopName || '附近商户'), element('h3', order.title || '到店券'));
  const statusText = { 1: '待支付', 2: '待到店使用', 3: '已使用', 4: '已取消', 5: '退款中', 6: '已退款' };
  top.append(title, element('span', statusText[order.status] || '处理中', `status${order.status === 1 ? ' waiting' : ''}`));
  const meta = element('div', undefined, 'order-meta');
  meta.append(element('span', `券订单 ${order.id}`), element('span', `下单 ${dateLabel(order.createTime)}`),
    element('strong', `支付金额 ${yuan(order.payValue)}`),
    link('查看商户 ↗', `/shop-detail.html?id=${encodeURIComponent(order.shopId)}`, 'shop-link'));
  card.append(top, meta);
  if (order.status === 1) {
    const form = element('form', undefined, 'pay-form');
    const label = element('label', '账户密码（余额支付）');
    const password = document.createElement('input');
    password.type = 'password'; password.required = true;
    password.autocomplete = 'current-password';
    password.placeholder = '输入账户密码';
    label.append(password);
    const button = element('button', '确认支付', 'primary-btn');
    button.type = 'submit';
    form.append(label, button);
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      if (!password.value) { notice('请输入账户密码。'); return; }
      button.disabled = true;
      try {
        const payId = await api(`/pay-orders/voucher/${encodeURIComponent(order.id)}`, { method: 'POST' });
        await api(`/pay-orders/${encodeURIComponent(payId)}`, { method: 'POST', body: JSON.stringify({ pw: password.value }) });
        password.value = '';
        notice('余额支付已提交，正在同步券订单状态。');
        for (let attempt = 0; attempt < 8; attempt++) {
          await new Promise((resolve) => setTimeout(resolve, 500));
          const body = await api('/voucher-order/me?current=1');
          if ((body.data || []).some((item) => item.id === order.id && item.status === 2)) {
            notice('支付成功，券已放入“我的券”。');
            break;
          }
        }
        state.current = 1;
        loadMine();
      } catch (error) { notice(error.message); }
      finally { button.disabled = false; }
    });
    card.append(form);
    const cancel = element('button', '取消订单', 'secondary-btn');
    cancel.type = 'button';
    cancel.addEventListener('click', async () => {
      if (!confirm('确定取消这张待支付的券吗？')) return;
      cancel.disabled = true;
      try {
        await api(`/voucher-order/${encodeURIComponent(order.id)}/cancel`, { method: 'POST' });
        notice('订单已取消。');
        state.current = 1; await loadMine(); loadActivities();
      } catch (error) { notice(error.message); cancel.disabled = false; }
    });
    card.append(cancel);
  } else if (order.status === 2) {
    const use = element('div', undefined, 'voucher-use');
    use.append(element('span', '到店出示核销码'), element('strong', order.redeemCode || '正在生成…', 'redeem-code'));
    const refund = element('button', '申请原路退回余额', 'secondary-btn');
    refund.type = 'button';
    refund.addEventListener('click', async () => {
      if (!confirm('确定退款吗？退款后此券不可使用，金额将退回账户余额。')) return;
      refund.disabled = true;
      try {
        await api(`/pay-orders/voucher/${encodeURIComponent(order.id)}/refund`, { method: 'POST' });
        notice('退款成功，金额已退回账户余额。');
        state.current = 1; await loadMine();
      } catch (error) { notice(error.message); refund.disabled = false; }
    });
    use.append(refund);
    card.append(use);
  } else if (order.status === 3 && order.useTime) {
    card.append(element('p', `核销时间：${dateLabel(order.useTime)}`, 'order-footnote'));
  } else if (order.status === 6 && order.refundTime) {
    card.append(element('p', `退款时间：${dateLabel(order.refundTime)}`, 'order-footnote'));
  }
  return card;
}

async function loadMine(append = false) {
  if (!sessionStorage.getItem('token')) {
    $('my-orders').replaceChildren(empty('登录后可以查看和支付已抢到的券。', '使用账号密码登录', loginUrl));
    $('more-orders').hidden = true;
    $('merchant-panel').hidden = true;
    return;
  }
  api('/voucher-order/merchant/permission').then(() => { $('merchant-panel').hidden = false; })
    .catch(() => { $('merchant-panel').hidden = true; });
  if (state.busy) return;
  state.busy = true;
  try {
    const body = await api(`/voucher-order/me?current=${state.current}`);
    const orders = body.data || [];
    state.total = Number(body.total || 0);
    if (!append) $('my-orders').replaceChildren();
    $('my-orders').append(...orders.map(orderCard));
    if (!state.total) $('my-orders').replaceChildren(empty('还没有券订单。', '看看限时活动', '/vouchers.html'));
    $('more-orders').hidden = state.current * 10 >= state.total;
  } catch (error) {
    if (!append) $('my-orders').replaceChildren(empty(error.message, '重新登录', loginUrl));
    else notice(error.message);
  } finally { state.busy = false; }
}

function showTab(name) {
  const mine = name === 'mine';
  $('tab-activities').classList.toggle('is-active', !mine);
  $('tab-mine').classList.toggle('is-active', mine);
  $('tab-activities').setAttribute('aria-selected', String(!mine));
  $('tab-mine').setAttribute('aria-selected', String(mine));
  $('activities-panel').hidden = mine;
  $('mine-panel').hidden = !mine;
  history.replaceState(null, '', mine ? '/vouchers.html?tab=mine' : '/vouchers.html');
  if (mine) { state.current = 1; loadMine(); }
}

$('tab-activities').addEventListener('click', () => showTab('activities'));
$('tab-mine').addEventListener('click', () => showTab('mine'));
$('more-orders').addEventListener('click', () => { state.current++; loadMine(true); });
$('redeem-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const input = $('redeem-code');
  const button = event.currentTarget.querySelector('button');
  button.disabled = true;
  try {
    const body = await api(`/voucher-order/redeem?code=${encodeURIComponent(input.value)}`, { method: 'POST' });
    notice(`核销成功，券订单 ${body.data} 已使用。`);
    input.value = '';
    state.current = 1; await loadMine();
  } catch (error) { notice(error.message); }
  finally { button.disabled = false; }
});
loadActivities();
if (new URLSearchParams(location.search).get('tab') === 'mine') showTab('mine');
