export type ApiResponse<T> = {
  error: boolean;
  code: number;
  msg: string;
  traceId?: string;
  data: T;
};

export const backendApiUrl = process.env.NEXT_PUBLIC_BACKEND_API_URL ?? 'http://localhost:4010/api';

export async function readApiResponse<T>(response: Response): Promise<T> {
  const payload = await response.json() as ApiResponse<T>;
  if (!response.ok || payload.error) {
    const traceMessage = payload.traceId ? `（TraceId: ${payload.traceId}）` : '';
    throw new Error(`${payload.msg || '后端请求失败'}${traceMessage}`);
  }
  return payload.data;
}
