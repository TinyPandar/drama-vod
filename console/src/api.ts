export type Asset = { id: number; status: string; source_size: number; encrypted: boolean };
export type Job = { id: number; asset_id: number; status: string; attempts: number; claim_version: number };

export async function api<T>(key: string, path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      "X-Api-Key": key,
      ...(init?.headers ?? {})
    }
  });
  const text = await response.text();
  const body = text ? JSON.parse(text) as { detail?: string } : {};
  if (!response.ok) throw new Error(body.detail || `HTTP ${response.status}`);
  return body as T;
}
