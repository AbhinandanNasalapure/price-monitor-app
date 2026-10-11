import type { Product, ProductInput } from './types';

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as { detail?: string; message?: string } | null;
    throw new Error(payload?.detail ?? payload?.message ?? `Request failed (${response.status})`);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export const api = {
  products: () => request<Product[]>('/products'),
  create: (product: ProductInput) => request<Product>('/products', {
    method: 'POST',
    body: JSON.stringify({ ...product, targetPrice: product.targetPrice || null }),
  }),
  update: (id: number, product: ProductInput) => request<Product>(`/products/${id}`, {
    method: 'PUT',
    body: JSON.stringify({ ...product, targetPrice: product.targetPrice || null }),
  }),
  check: (id: number) => request<Product>(`/products/${id}/check`, { method: 'POST' }),
  remove: (id: number) => request<void>(`/products/${id}`, { method: 'DELETE' }),
  pushConfig: () => request<{ enabled: boolean; publicKey: string }>('/push/config'),
  savePushSubscription: (subscription: PushSubscriptionJSON) => request<void>('/push/subscriptions', {
    method: 'POST', body: JSON.stringify(subscription),
  }),
  removePushSubscription: (endpoint: string) => request<void>('/push/subscriptions', {
    method: 'DELETE', body: JSON.stringify({ endpoint }),
  }),
  testPush: () => request<void>('/push/test', { method: 'POST' }),
};
