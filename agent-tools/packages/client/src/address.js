/**
 * Where Qeploy lives.
 *
 * Up to 0.1.x the web front and the API shared qeploy.com. Since the 2026-11 rehosting (#649) the
 * front is static hosting on qeploy.com and the API has a host of its own. Kept free of node:
 * imports so the client entry can use it without pulling in the config file code.
 */

/** The API. Paths below it all start with /api/v1. */
export const DEFAULT_API_URL = 'https://api.qeploy.com';

/**
 * The old default.
 *
 * 0.1.x saved it at `qeploy login` and its README told people to export it, so someone who updates
 * still carries it — in the config file, a CI secret, an agent's MCP config. Nothing answers the API
 * there any more, only the web front, so it is read as the new address instead of sending every
 * call to a static host that replies "not found".
 *
 * Only this exact address is rewritten. Both are ours, so a stored token may follow; anything else
 * stays as given (#482 — a project's own config must not be able to redirect the user's token).
 */
export const LEGACY_API_URL = 'https://qeploy.com';

/**
 * The service notice. It is on the web host, which stays up when the API does not — reading it
 * from the API host would fail exactly when it is needed.
 */
export const STATUS_URL = 'https://qeploy.com/status.json';

export const sameAddress = (a, b) => a.replace(/\/+$/, '') === b.replace(/\/+$/, '');

/** The address to call: the old default becomes the API host, anything else is left alone. */
export function canonicalApiUrl(url) {
  return typeof url === 'string' && sameAddress(url, LEGACY_API_URL) ? DEFAULT_API_URL : url;
}

/** Where the service notice for an API address is: the site's for ours, `<address>/status.json` otherwise. */
export function statusUrlFor(apiUrl) {
  return sameAddress(apiUrl, DEFAULT_API_URL) ? STATUS_URL : `${apiUrl.replace(/\/+$/, '')}/status.json`;
}
