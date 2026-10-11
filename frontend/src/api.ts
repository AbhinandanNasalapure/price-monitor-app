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
  check: (id: number) => request<Product>(`/products/${id}/check`, { method: 'POST' }),
  remove: (id: number) => request<void>(`/products/${id}`, { method: 'DELETE' }),
};
