import { chmodSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';

/**
 * Where the token issued by `qeploy login` lives.
 *
 * Shared by the CLI and the MCP server so one login unlocks both — an agent configured with
 * `npx @qeploy/mcp` gets no chance to set an environment variable, and asking the user to put a
 * credential into their agent's config file spreads it to one more place.
 *
 * A file rather than an environment variable because the point of `qeploy login` is that the user
 * does it once; an env var would have to be re-exported in every shell, and the usual fix for that
 * is pasting the token into a dotfile that gets committed by accident.
 */

/** Follows XDG so a user who has moved their config directory does not get a stray ~/.config. */
export function configPath(env = process.env) {
  const base = env.QEPLOY_CONFIG_HOME ?? env.XDG_CONFIG_HOME ?? join(homedir(), '.config');
  return join(base, 'qeploy', 'config.json');
}

/** Missing, unreadable, or corrupt all mean the same thing to a caller: not logged in. */
export function readConfig(env = process.env) {
  try {
    const parsed = JSON.parse(readFileSync(configPath(env), 'utf8'));
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

export function writeConfig(config, env = process.env) {
  const path = configPath(env);
  // 0700 on the directory and 0600 on the file: this holds a credential, and the default umask on a
  // shared machine would leave it world-readable.
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
  writeFileSync(path, `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 });
  // writeFileSync's mode only applies when it creates the file; an existing one keeps its old
  // permissions, so a file written before this rule existed would stay readable.
  chmodSync(path, 0o600);
  return path;
}

export function clearConfig(env = process.env) {
  const path = configPath(env);
  try {
    rmSync(path);
    return path;
  } catch {
    return null;
  }
}

export const DEFAULT_API_URL = 'https://qeploy.com';

const sameAddress = (a, b) => a.replace(/\/+$/, '') === b.replace(/\/+$/, '');

/**
 * Resolves the token to call the API with.
 *
 * The environment wins over the file so a CI job can set `QEPLOY_TOKEN` without the login file
 * silently overriding it, and so a developer can run one command as a different token without
 * logging out.
 *
 * The file token is only handed out for the address it was saved with. `QEPLOY_API_URL` can come
 * from a project's own config — a cloned repository's `.mcp.json` env, an `.envrc` — and without
 * this check pointing it somewhere else would send the user's stored token there (#482). A
 * `QEPLOY_TOKEN` the caller set themselves goes wherever they point it.
 *
 * @param apiUrl the address the token is about to be sent to; omit to skip the check
 * @returns {{token: string|null, source: string|null, boundTo?: string}} `boundTo` is set when a
 *   stored token exists but belongs to a different address
 */
export function resolveToken(env = process.env, apiUrl) {
  const fromEnv = env.QEPLOY_TOKEN;
  if (fromEnv) return { token: fromEnv, source: 'QEPLOY_TOKEN' };

  const config = readConfig(env);
  if (config.token) {
    const savedFor = config.apiUrl ?? DEFAULT_API_URL;
    if (apiUrl !== undefined && !sameAddress(savedFor, apiUrl)) {
      return { token: null, source: null, boundTo: savedFor };
    }
    return { token: config.token, source: configPath(env) };
  }

  return { token: null, source: null };
}

/** The message for a stored token that `resolveToken` withheld because the address changed. */
export function boundToMessage(boundTo, apiUrl) {
  return (
    `저장된 토큰은 ${boundTo} 에서 받은 것이라 ${apiUrl} 로는 보내지 않습니다.\n` +
    `그 주소를 쓰려면 거기서 다시 로그인하거나(qeploy login --api-url ${apiUrl}) QEPLOY_TOKEN 을 직접 넣어주세요.`
  );
}

/** Same precedence for the API address, so one `qeploy login` pins both. */
export function resolveApiUrl(flags = {}, env = process.env) {
  return flags.apiUrl ?? env.QEPLOY_API_URL ?? readConfig(env).apiUrl ?? DEFAULT_API_URL;
}
