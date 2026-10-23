const $ = (id) => document.getElementById(id);
const state = { page: 1, pages: 1, key: '', goods: [], cart: [], orderId: null, orderAmount: 0 };
const yuan = (cents) => `¥${(Number(cents || 0) / 100).toFixed(2)}`;
let noticeTimer;

function notice(message) {
  const box = $('notice');
  box.textContent = message;
  box.classList.add('show');
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => box.classList.remove('show'), 4500);
}

async function api(path, options = {}) {
  const token = sessionStorage.getItem('token');
  const response = await fetch(`/api${path}`, {
    ...options,
    headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { authorization: token } : {}), ...options.headers },
  });
  const raw = await response.text();
  let data;
  // Spring MVC returns bare 64-bit IDs as text; JSON.parse would round them in JavaScript.
  try { data = /^\d{16,}$/.test(raw.trim()) ? raw.trim() : (raw ? JSON.parse(raw) : null); }
  catch { data = raw; }
  if (response.status === 401) throw new Error('请先登录。可从页面顶部进入“我的”或打开登录页。');
  if (!response.ok || data?.success === false || (typeof data?.code === 'number' && data.code !== 200)) {
    throw new Error(data?.errorMsg || data?.msg || `请求失败（${response.status}）`);
  }
  return data?.success === true ? data.data : (typeof data?.code === 'number' ? data.data : data);
}

function text(tag, value, className) {
  const el = document.createElement(tag);
  el.textContent = value;
  if (className) el.className = className;
  return el;
}

async function loadGoods() {
  $('goods').replaceChildren(text('p', '正在查找商品…', 'empty'));
  try {
    const query = new URLSearchParams({ pageNo: state.page, pageSize: 9, key: state.key });
    const page = await api(`/search/list?${query}`);
    state.goods = page.list || [];
    state.pages = Math.max(1, Number(page.pages || 1));
    $('catalog-count').textContent = `共 ${page.total || 0} 件商品`;
    $('page-label').textContent = `第 ${state.page} / ${state.pages} 页`;
    $('prev-page').disabled = state.page <= 1;
    $('next-page').disabled = state.page >= state.pages;
    const cards = state.goods.map((item) => {
      const card = text('article', '', 'goods-card');
      const image = text('div', '', 'goods-image');
      const img = document.createElement('img');
      img.src = item.image || '/imgs/icons/default-icon.png';
      img.alt = item.name || '商品图片';
      img.loading = 'lazy';
      img.onerror = () => { img.src = '/imgs/icons/default-icon.png'; img.onerror = null; };
      image.append(img);
      const body = text('div', '', 'goods-body');
      body.append(text('p', item.name || '未命名商品', 'goods-name'), text('p', `${item.brand || '精选商品'} · 库存 ${item.stock ?? 0}`, 'goods-meta'));
      const buy = text('div', '', 'goods-buy');
      const button = text('button', '放入购物车');
      button.type = 'button';
      button.disabled = Number(item.stock || 0) < 1;
      button.addEventListener('click', () => addCart(item.id));
      buy.append(text('strong', yuan(item.price), 'goods-price'), button);
      body.append(buy); card.append(image, body);
      return card;
    });
    $('goods').replaceChildren(...(cards.length ? cards : [text('p', '没有找到商品，换个关键词试试。', 'empty')]));
  } catch (error) { $('goods').replaceChildren(text('p', error.message, 'empty')); notice(error.message); }
}

async function loadCart() {
  if (!sessionStorage.getItem('token')) {
    $('account-state').textContent = '请先登录后购买';
    $('cart').replaceChildren(text('p', '登录后可查看购物车。', 'empty'));
    return;
  }
  try {
    state.cart = await api('/carts') || [];
    $('account-state').textContent = `已登录 · ${state.cart.length} 件商品`;
    const rows = state.cart.map((item) => {
      const row = text('div', '', 'cart-row');
      row.append(text('div', item.name || '商品', 'cart-row-name'));
      const foot = text('div', '', 'cart-row-foot');
      const remove = text('button', '移除', 'cart-remove');
      remove.type = 'button';
      remove.addEventListener('click', () => removeCart(item.id));
      foot.append(text('span', `× ${item.num || 1}`), text('strong', yuan((item.newPrice ?? item.price) * (item.num || 1))), remove);
      row.append(foot); return row;
    });
    $('cart').replaceChildren(...(rows.length ? rows : [text('p', '购物车空着，先挑一件喜欢的商品。', 'empty')]));
    $('total').textContent = yuan(state.cart.reduce((sum, item) => sum + Number(item.newPrice ?? item.price) * Number(item.num || 1), 0));
    $('checkout').disabled = !state.cart.length;
  } catch (error) { notice(error.message); }
}

async function addCart(id) {
  if (!sessionStorage.getItem('token')) { notice('请先登录后加入购物车。'); return; }
  try { await api('/carts', { method: 'POST', body: JSON.stringify({ itemId: id }) }); await loadCart(); notice('已放入购物车。'); }
  catch (error) { notice(error.message); }
}

async function removeCart(id) {
  try { await api(`/carts/${encodeURIComponent(id)}`, { method: 'DELETE' }); await loadCart(); notice('已从购物车移除。'); }
  catch (error) { notice(error.message); }
}

async function checkout() {
  if (!state.cart.length) return;
  const details = state.cart.map((item) => ({ itemId: item.itemId, num: Number(item.num || 1) }));
  $('checkout').disabled = true;
  try {
    const orderId = await api('/orders', { method: 'POST', body: JSON.stringify({ paymentType: 3, details }) });
    state.orderId = orderId;
    const order = await api(`/orders/${encodeURIComponent(orderId)}`);
    state.orderAmount = Number(order.totalFee);
    $('order-number').textContent = `订单 ${orderId} · ${yuan(state.orderAmount)}`;
    $('payment').hidden = false;
    await loadCart();
    notice('订单已创建，请完成余额支付。');
  } catch (error) { notice(error.message); $('checkout').disabled = false; }
}

async function pay() {
  const password = $('pay-password').value;
  if (!password) { notice('请输入账户密码。'); return; }
  const button = $('pay-button'); button.disabled = true;
  try {
    const payId = await api('/pay-orders', { method: 'POST', body: JSON.stringify({ bizOrderNo: state.orderId, amount: state.orderAmount, payChannelCode: 'balance', payType: 5, orderInfo: '附近好物订单' }) });
    await api(`/pay-orders/${encodeURIComponent(payId)}`, { method: 'POST', body: JSON.stringify({ pw: password }) });
    $('pay-password').value = '';
    $('payment').hidden = true;
    state.orderId = null;
    notice('支付成功，订单已提交。');
  } catch (error) { notice(error.message); }
  finally { button.disabled = false; }
}

$('search-form').addEventListener('submit', (event) => { event.preventDefault(); state.key = $('search-input').value.trim(); state.page = 1; loadGoods(); });
$('prev-page').addEventListener('click', () => { if (state.page > 1) { state.page--; loadGoods(); } });
$('next-page').addEventListener('click', () => { if (state.page < state.pages) { state.page++; loadGoods(); } });
$('checkout').addEventListener('click', checkout);
$('pay-button').addEventListener('click', pay);
loadGoods(); loadCart();
