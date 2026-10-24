import axios, { type AxiosError } from 'axios'
import { ElMessage } from 'element-plus'

export class ApiError2 extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message)
    this.name = 'ApiError2'
  }
}

const http2 = axios.create({ baseURL: '/api/v2', timeout: 60000 })

http2.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body === 'object' && 'status' in body && 'msg' in body && 'data' in body) {
      if (body.status !== 200) return Promise.reject(new ApiError2(body.msg ?? '请求失败', body.status))
      return body.data
    }
    return body
  },
  (error: AxiosError<{ msg?: string; message?: string }>) => {
    const status = error.response?.status ?? 0
    const msg = error.response?.data?.msg ?? error.response?.data?.message ?? error.message
    ElMessage.error(`[${status || '网络异常'}] ${msg}`)
    return Promise.reject(new ApiError2(msg, status))
  },
)

async function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  return (await http2.get(url, { params })) as T
}
async function post<T>(url: string, data?: unknown): Promise<T> {
  return (await http2.post(url, data)) as T
}
async function put<T>(url: string, data?: unknown): Promise<T> {
  return (await http2.put(url, data)) as T
}
async function del<T>(url: string): Promise<T> {
  return (await http2.delete(url)) as T
}

export { get, post, put, del }
export default http2
