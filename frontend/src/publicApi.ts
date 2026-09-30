import { ApiError } from './api';
import type { PublicChangelog, PublicPortal, PublicRoadmap, PublicTracking } from './types';

/** Calls the public (no sign-in) endpoints; never sends the user's token. */
async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api/public${path}`, {
      method,
      headers: { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'Cannot reach the server. Check your connection and try again.');
  }
  if (!response.ok) {
    let message = response.status === 404 ? 'This page does not exist.' : `Something went wrong (${response.status}).`;
    let fieldErrors: Record<string, string> = {};
    try {
      const data = await response.json();
      if (data?.message) message = data.message;
      if (data?.fieldErrors) fieldErrors = data.fieldErrors;
    } catch {
      /* non-JSON error body */
    }
    if (response.status === 429) message = 'Too many requests from your network. Please wait a while and try again.';
    throw new ApiError(response.status, message, fieldErrors);
  }
  return response.json() as Promise<T>;
}

export interface NewPortalRequest {
  requestTypeId: number | null;
  name: string;
  email: string;
  summary: string;
  description: string;
  answers: Record<string, string | boolean>;
  channel?: 'portal' | 'widget';
  website?: string;
}

export const publicApi = {
  portal: (key: string) => call<PublicPortal>('GET', `/portal/${encodeURIComponent(key)}`),
  submit: (key: string, request: NewPortalRequest) =>
    call<{ token: string; reference: string }>('POST', `/portal/${encodeURIComponent(key)}/requests`, request),
  similar: (key: string, q: string) =>
    call<{ title: string; kind: 'roadmap' | 'changelog'; status: string }[]>('GET', `/portal/${encodeURIComponent(key)}/similar?q=${encodeURIComponent(q)}`),
  tracking: (token: string) => call<PublicTracking>('GET', `/requests/${encodeURIComponent(token)}`),
  reply: (token: string, body: string) => call<PublicTracking>('POST', `/requests/${encodeURIComponent(token)}/messages`, { body }),
  roadmap: (key: string) => call<PublicRoadmap>('GET', `/projects/${encodeURIComponent(key)}/roadmap`),
  changelog: (key: string) => call<PublicChangelog>('GET', `/projects/${encodeURIComponent(key)}/changelog`),
};
