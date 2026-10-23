// let commonURL = "http://192.168.50.115:8081";
let commonURL = "/api";
// 设置后台服务地址
axios.defaults.baseURL = commonURL;
axios.defaults.timeout = 2000;
// request拦截器，将用户token放入头中
axios.interceptors.request.use(
  config => {
    const token = sessionStorage.getItem("token");
    if(token) config.headers['authorization'] = token
    return config
  },
  error => {
    console.log(error)
    return Promise.reject(error)
  }
)
axios.interceptors.response.use(function (response) {
  const body = response.data;
  // Local-life endpoints return Result; commerce endpoints return R; user login returns a VO.
  if (body && body.success === false) {
    return Promise.reject(body.errorMsg || "请求失败");
  }
  if (body && typeof body.code === "number" && body.code !== 200) {
    return Promise.reject(body.msg || "请求失败");
  }
  if (body && body.success === true && Object.prototype.hasOwnProperty.call(body, "data")) return body;
  if (body && typeof body.code === "number" && Object.prototype.hasOwnProperty.call(body, "data")) return { success: true, data: body.data };
  return { success: true, data: body };
}, function (error) {
  // 一般是服务端异常或者网络异常
  console.log(error)
  if(error.response && error.response.status == 401){
    // 未登录，跳转
    setTimeout(() => {
      location.href = "/login.html"
    }, 200);
    return Promise.reject("请先登录");
  }
  return Promise.reject("服务器异常");
});
axios.defaults.paramsSerializer = function(params) {
  let p = "";
  Object.keys(params).forEach(k => {
    if(params[k]){
      p = p + "&" + k + "=" + params[k]
    }
  })
  return p;
}
const util = {
  commonURL,
  getUrlParam(name) {
    let reg = new RegExp("(^|&)" + name + "=([^&]*)(&|$)", "i");
    let r = window.location.search.substr(1).match(reg);
    if (r != null) {
      return decodeURI(r[2]);
    }
    return "";
  },
  formatPrice(val) {
    if (typeof val === 'string') {
      if (isNaN(val)) {
        return null;
      }
      // 价格转为整数
      const index = val.lastIndexOf(".");
      let p = "";
      if (index < 0) {
        // 无小数
        p = val + "00";
      } else if (index === p.length - 2) {
        // 1位小数
        p = val.replace("\.", "") + "0";
      } else {
        // 2位小数
        p = val.replace("\.", "")
      }
      return parseInt(p);
    } else if (typeof val === 'number') {
      if (!val) {
        return null;
      }
      const s = val + '';
      if (s.length === 0) {
        return "0.00";
      }
      if (s.length === 1) {
        return "0.0" + val;
      }
      if (s.length === 2) {
        return "0." + val;
      }
      const i = s.indexOf(".");
      if (i < 0) {
        return s.substring(0, s.length - 2) + "." + s.substring(s.length - 2)
      }
      const num = s.substring(0, i) + s.substring(i + 1);
      if (i === 1) {
        // 1位整数
        return "0.0" + num;
      }
      if (i === 2) {
        return "0." + num;
      }
      if (i > 2) {
        return num.substring(0, i - 2) + "." + num.substring(i - 2)
      }
    }
  }
}
