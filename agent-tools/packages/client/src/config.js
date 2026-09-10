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

/**
 * Resolves the token to call the API with.
 *
 * The environment wins over the file so a CI job can set `QEPLOY_TOKEN` without the login file
 * silently overriding it, and so a developer can run one command as a different token without
 * logging out.
 */
export function resolveToken(env = process.env) {
  const fromEnv = env.QEPLOY_TOKEN;
  if (fromEnv) return { token: fromEnv, source: 'QEPLOY_TOKEN' };

  const { token } = readConfig(env);
  if (token) return { token, source: configPath(env) };

  return { token: null, source: null };
}

/** Same precedence for the API address, so one `qeploy login` pins both. */
export function resolveApiUrl(flags = {}, env = process.env) {
  return (
    flags.apiUrl ?? env.QEPLOY_API_URL ?? readConfig(env).apiUrl ?? 'https://qeploy.com'
  );
}
