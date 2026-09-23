import axios, { type AxiosError } from 'axios'
import { ElMessage } from 'element-plus'

export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

const http = axios.create({ baseURL: '/api/v1', timeout: 20000 })

http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    // 统一包裹 {status,msg,data}；栅格数据等裸 JSON 直接透传
    if (body && typeof body === 'object' && 'status' in body && 'msg' in body && 'data' in body) {
      if (body.status !== 200) return Promise.reject(new ApiError(body.msg ?? '请求失败', body.status))
      return body.data
    }
    return body
  },
  (error: AxiosError<{ msg?: string; message?: string }>) => {
    const status = error.response?.status ?? 0
    const msg = error.response?.data?.msg ?? error.response?.data?.message ?? error.message
    ElMessage.error(`[${status || '网络异常'}] ${msg}`)
    return Promise.reject(new ApiError(msg, status))
  },
)

async function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  return (await http.get(url, { params })) as T
}
async function post<T>(url: string, data?: unknown): Promise<T> {
  return (await http.post(url, data)) as T
}
async function put<T>(url: string, data?: unknown): Promise<T> {
  return (await http.put(url, data)) as T
}
async function del<T>(url: string): Promise<T> {
  return (await http.delete(url)) as T
}

export { get, post, put, del }
export default http
