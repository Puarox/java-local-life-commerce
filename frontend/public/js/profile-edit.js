const $ = (id) => document.getElementById(id);
const fallbackAvatar = '/imgs/icons/default-icon.png';

async function api(path, options = {}) {
  const token = sessionStorage.getItem('token');
  if (!token) { location.href = '/login2.html?redirect=%2Finfo-edit.html'; throw new Error('请先登录'); }
  const response = await fetch(`/api${path}`, {
    ...options,
    headers: { authorization: token, ...(options.body ? { 'Content-Type': 'application/json' } : {}) },
  });
  const body = await response.json().catch(() => null);
  if (response.status === 401) { location.href = '/login2.html?redirect=%2Finfo-edit.html'; throw new Error('登录已过期'); }
  if (!response.ok || body?.success === false || (typeof body?.code === 'number' && body.code !== 200)) {
    throw new Error(body?.errorMsg || body?.msg || `保存失败（${response.status}）`);
  }
  return body?.success === true ? body.data : (typeof body?.code === 'number' ? body.data : body);
}

function feedback(message, success = false) {
  $('feedback').textContent = message;
  $('feedback').classList.toggle('success', success);
}

async function loadProfile() {
  try {
    const user = await api('/user/me');
    const detail = await api(`/user/info/${encodeURIComponent(user.id)}`);
    $('nickname').value = user.nickName || user.username || '';
    $('avatar-url').value = user.icon || '';
    $('intro').value = detail?.introduce || '';
    $('city').value = detail?.city || '';
    $('gender').value = detail?.gender == null ? '' : String(detail.gender);
    $('birthday').value = detail?.birthday || '';
    $('avatar-preview').src = user.icon || fallbackAvatar;
  } catch (error) { feedback(error.message); }
}

$('avatar-url').addEventListener('input', () => { $('avatar-preview').src = $('avatar-url').value.trim() || fallbackAvatar; });
$('avatar-preview').addEventListener('error', () => { $('avatar-preview').src = fallbackAvatar; });
$('profile-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const nickName = $('nickname').value.trim();
  const introduce = $('intro').value.trim();
  const city = $('city').value.trim();
  if (!nickName || nickName.length > 32 || introduce.length > 128 || city.length > 64) {
    feedback('请检查昵称、个人介绍和城市的长度。'); return;
  }
  const today = new Date();
  const localToday = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;
  if ($('birthday').value && $('birthday').value > localToday) {
    feedback('生日不能晚于今天。'); return;
  }
  const button = $('save'); button.disabled = true;
  feedback('正在保存资料…');
  let accountSaved = false;
  try {
    await api('/users/me/profile', { method: 'PUT', body: JSON.stringify({ nickName, icon: $('avatar-url').value.trim() }) });
    accountSaved = true;
    await api('/user/info', { method: 'PUT', body: JSON.stringify({
      city, introduce, gender: $('gender').value === '' ? null : $('gender').value === 'true',
      birthday: $('birthday').value || null,
    }) });
    sessionStorage.removeItem('userInfo');
    feedback('资料已保存，正在返回个人主页。', true);
    setTimeout(() => { location.href = '/info.html'; }, 700);
  } catch (error) {
    feedback(accountSaved ? `昵称和头像已保存；其他资料保存失败：${error.message}` : error.message);
  } finally { button.disabled = false; }
});

loadProfile();
