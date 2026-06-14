import config from '@/axios/axiosConfig' // 导入 axios 配置文件
import { useUserStore } from '@/stores/user.js' // 导入用户状态管理 store

const userStore = useUserStore()
export const BASE_URL = config.baseURL // 导出基础 URL 常量
const TIMEOUT = config.timeout // 定义请求超时常量

let isRefreshing = false // 定义 token 刷新状态标志，防止并发刷新
let refreshSubscribers = [] // 定义 token 刷新订阅者数组，用于处理并发请求

const subscribeTokenRefresh = (callback) => { // 定义订阅 token 刷新的函数
	refreshSubscribers.push(callback) // 将回调函数添加到订阅者数组
}

const onTokenRefreshed = (token) => { // 定义 token 刷新完成的处理函数
	refreshSubscribers.forEach(callback => callback(token)) // 遍历订阅者数组，执行所有回调函数
	refreshSubscribers = [] // 清空订阅者数组
}

const refreshToken = async () => { // 定义刷新 token 的异步函数
	const refreshTokenValue = uni.getStorageSync('refreshToken') // 从本地存储中获取刷新令牌
	const token = uni.getStorageSync('token') // 从本地存储中获取令牌
	console.log("6.获取token",token)
	console.log("6.获取refreshToken",refreshTokenValue)
	if (!refreshTokenValue) { // 检查刷新令牌是否存在
		throw new Error('refresh token 错误') // 如果不存在，抛出错误
	}

	return new Promise((resolve, reject) => { // 返回一个 Promise 对象
		uni.request({ // 发起 uni-app 请求
			url: BASE_URL + '/auth/appRefresh', // 请求 URL 为刷新 token 接口
			method: 'POST', // 请求方法为 POST
			data: {
				refreshToken: refreshTokenValue
			}, // 请求体包含刷新令牌
			header: { // 设置请求头
				'Content-Type': 'application/json', // 设置内容类型为 JSON
				'Authorization': token // 设置内容类型为 JSON
			},
			timeout: TIMEOUT, // 设置请求超时时间
			success: (res) => { // 请求成功的回调函数
				if (res.statusCode === 200 && res.data.code === 200) { // 检查状态码和业务码是否成功
					console.log("7.请求刷新token成功",res.data.code)
					resolve(res.data.data) // 解析并返回新的 token 数据
				} else { // 如果业务码不是 200
					console.log("7.请求刷新token失败",res.data.code, res.data.msg)
					reject(new Error(res.data.msg || 'Refresh token failed')) // 拒绝 Promise 并抛出错误
				}
			},
			fail: (err) => { // 请求失败的回调函数
				reject(err) // 拒绝 Promise 并传递错误对象
			}
		})
	})
}

const checkTokenExpired = () => { // 定义检查 token 是否过期的函数
	const tokenExpires = uni.getStorageSync('tokenExpires') // 从本地存储中获取 token 过期时间（秒）
	console.log(tokenExpires)
	if (!tokenExpires) return true // 如果过期时间不存在，返回 true 表示已过期

	const loginTime = uni.getStorageSync('loginTime') // 获取登录时间戳，不存在则使用当前时间
	console.log("登录时间",loginTime)
	const expireTime = loginTime + tokenExpires // 计算过期时间戳（秒转毫秒）
	const bufferTime = 5 * 60 * 1000 // 设置缓冲时间为 5 分钟（毫秒）
	console.log("当前时间",Date.now())
	console.log("token过期时间",expireTime)
	// return Date.now() >= (expireTime - bufferTime) // 判断当前时间是否已超过（过期时间 - 缓冲时间）
	return Date.now() >= expireTime // 判断当前时间是否已超过（过期时间 - 缓冲时间）
}

const requestInterceptor = async (config) => { // 定义请求拦截器函数，接收请求配置对象
	const token = uni.getStorageSync('token') // 从本地存储中获取访问令牌
	console.log("2.获取token",token)
	if (token) { // 检查 token 是否存在
		console.log("3.1.token存在")
		if (checkTokenExpired()) { // 检查 token 是否过期
			console.log("4.token已过期")
			if (isRefreshing) { // 检查是否正在刷新 token
				return new Promise((resolve) => { // 返回一个 Promise，等待 token 刷新完成
					subscribeTokenRefresh((newToken) => { // 订阅 token 刷新事件
						config.header['Authorization'] = `Bearer ${newToken}` // 使用新 token 更新请求头
						resolve(config) // 解析 Promise 并返回更新后的配置
					})
				})
			} else { // 如果没有正在刷新
				isRefreshing = true // 设置刷新状态为 true
				try { // 开始 try 块
					console.log("5.调用刷新 token 函数")
					const tokenInfo = await refreshToken() // 调用刷新 token 函数
					console.log("8.请求刷新tokenInfo详情",tokenInfo)
					console.log("9.更新token至内存和本地",tokenInfo)
					userStore.updateTokenInfo(tokenInfo)
					config.header['Authorization'] = `Bearer ${tokenInfo.accessToken}` // 使用新 token 更新请求头
					onTokenRefreshed(tokenInfo.accessToken) // 通知所有订阅者 token 已刷新

					return config // 返回更新后的请求配置
				} catch (error) { // 捕获刷新 token 的错误
					uni.removeStorageSync('token') // 从本地存储中移除 token
					uni.removeStorageSync('tokenExpires') // 从本地存储中移除 token 过期时间
					uni.removeStorageSync('refreshToken') // 从本地存储中移除刷新令牌
					uni.removeStorageSync('refreshTokenExpires') // 从本地存储中移除刷新令牌过期时间
					uni.removeStorageSync('userInfo') // 从本地存储中移除用户信息
					console.log("10.调用刷新 token 函数失败")
					uni.showToast({
						title: '登录已过期，请重新登录',
						icon: 'none',
						duration: 2000
					})

					setTimeout(() => {
						uni.navigateTo({
							url: '/pages/user/login'
						})
					}, 1000)

					return Promise.reject(error) // 拒绝 Promise 并传递错误
				} finally { // 无论成功或失败都执行
					isRefreshing = false // 重置刷新状态为 false
				}
			}
		} else { // 如果 token 未过期
			config.header['Authorization'] = `Bearer ${token}` // 直接使用现有 token 更新请求头
		}
	}
	console.log("3.2.token不存在")
	return config // 返回请求配置
}

const responseInterceptor = (res) => { // 定义响应拦截器函数，接收响应对象
	if (res.statusCode === 200) { // 检查 HTTP 状态码是否为 200
		return res.data // 返回响应数据
	} else { // 如果状态码不是 200
		uni.showToast({ // 显示错误提示
			title: `请求失败：${res.statusCode}`, // 提示文本包含状态码
			icon: 'none' // 不显示图标
		})
	}
}

const errorInterceptor = (err) => { // 定义错误拦截器函数，接收错误对象
	uni.showToast({ // 显示错误提示
		title: '网络错误，请检查网络连接', // 提示文本
		icon: 'none' // 不显示图标
	})
}

export const request = async (url, method = 'GET', data = {}) => { // 导出通用的请求函数，接收 URL、方法和数据
	const requestConfig = { // 创建请求配置对象
		url: BASE_URL + url, // 完整的请求 URL
		method: method, // 请求方法
		data: data, // 请求数据
		header: { // 请求头
			'Content-Type': 'application/json' // 内容类型为 JSON
		},
		timeout: TIMEOUT // 请求超时时间
	}

	console.log("1.请求开始...")
	const interceptedConfig = await requestInterceptor(requestConfig) // 调用请求拦截器处理配置

	return new Promise((resolve, reject) => { // 返回一个 Promise 对象
		uni.request({ // 发起 uni-app 请求
			...interceptedConfig, // 展开拦截后的配置
			success: (res) => { // 请求成功的回调函数
				const interceptedRes = responseInterceptor(res) // 调用响应拦截器处理响应
				resolve(interceptedRes) // 解析 Promise 并返回处理后的响应
			},
			fail: (err) => { // 请求失败的回调函数
				errorInterceptor(err) // 调用错误拦截器处理错误
			}
		})
	})
}

export const get = (url, data = {}) => { // 导出 GET 请求方法
	return request(url, 'GET', data) // 调用通用请求函数，方法为 GET
}

export const post = (url, data = {}) => { // 导出 POST 请求方法
	return request(url, 'POST', data) // 调用通用请求函数，方法为 POST
}

export const put = (url, data = {}) => { // 导出 PUT 请求方法
	return request(url, 'PUT', data) // 调用通用请求函数，方法为 PUT
}

export const del = (url, data = {}) => { // 导出 DELETE 请求方法
	return request(url, 'DELETE', data) // 调用通用请求函数，方法为 DELETE
}

export { config } // 导出配置对象
