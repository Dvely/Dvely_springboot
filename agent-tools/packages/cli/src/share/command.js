/**
 * `qeploy share web <url>` — share a web app running on this machine (§7).
 *
 * Everything that touches the outside world — Chrome, the CDP socket, signals, the terminal — comes
 * from a runtime object that tests replace through `deps.share`.
 */
import { readFileSync } from 'node:fs';
import { UsageError } from '../confirm.js';
import { terminalSafe } from '../render.js';
import { askControl } from './ask.js';
import { CdpConnection, attachFirstPage } from './cdp.js';
import { NO_CHROME_MESSAGE, findChrome, launchChrome } from './chrome.js';
import { checkTunnelUrl, parseTargetUrl, parseTtl, parseViewport } from './policy.js';
import { ShareSession } from './session.js';

const CLI_VERSION = JSON.parse(readFileSync(new URL('../../package.json', import.meta.url), 'utf8')).version;

/** Mirrors EXIT in index.js (imported from there it would be a cycle). */
const EXIT_OK = 0;
const EXIT_ERROR = 1;
const EXIT_AUTH = 3;

const DEFAULT_TTL_MINUTES = 60;
const MAX_LABEL_LENGTH = 255;
const REVOKE_TIMEOUT_MS = 5000;
const SIGNALS = ['SIGINT', 'SIGTERM', 'SIGHUP'];

export const defaultShareRuntime = {
  findChrome: ({ env }) => findChrome({ env }),
  launchChrome,
  connectCdp: (url) => CdpConnection.connect(url),
  attachPage: attachFirstPage,
  createSession: (options) => new ShareSession(options),
  askControl,
  /** @returns {() => void} unsubscribe */
  onInterrupt(handler) {
    for (const signal of SIGNALS) process.on(signal, handler);
    return () => {
      for (const signal of SIGNALS) process.off(signal, handler);
    };
  },
  forceExit: (code) => process.exit(code),
};

/** How each way of ending is reported, and with which exit code. */
const ENDINGS = {
  interrupt: { code: EXIT_OK, text: '공유를 끝냈습니다.' },
  revoked: { code: EXIT_OK, text: '공유가 회수되어 끝났습니다.' },
  expired: { code: EXIT_OK, text: '공유 링크가 만료되어 끝났습니다.' },
  replaced: { code: EXIT_ERROR, text: '같은 링크로 다른 연결이 들어와 이 연결을 끝냅니다.' },
  auth: {
    code: EXIT_AUTH,
    text: '터널 인증에 실패했습니다. 토큰이 만료·폐기되었거나 WRITE 스코프가 아닙니다.',
  },
  'chrome-exit': { code: EXIT_ERROR, text: 'Chrome 이 종료되어 공유를 끝냅니다.' },
  rejected: {
    code: EXIT_ERROR,
    text: '서버가 이 CLI 의 터널 연결을 받지 않습니다(HTTP 400). @qeploy/cli 를 최신으로 올려 주세요.',
  },
};

/** Ends where the link is already over or belongs to another connection — revoking is not ours. */
const KEEP_LINK = new Set(['revoked', 'expired', 'replaced', 'gone']);

function ending(result) {
  if (result.reason === 'gone') {
    return result.status === 410
      ? { code: EXIT_OK, text: '공유 링크가 이미 끝났습니다(회수 또는 만료).' }
      : { code: EXIT_ERROR, text: '공유 링크를 찾을 수 없어 끝냅니다.' };
  }
  return ENDINGS[result.reason] ?? { code: EXIT_ERROR, text: '공유를 끝냅니다.' };
}

export function linksText(share, ttlMinutes) {
  const safe = (v) => terminalSafe(String(v ?? ''));
  return [
    `보기 링크:    ${safe(share.viewUrl)}`,
    `              남에게 줄 링크입니다. 보기만 되고, 조작은 내가 승인해야 합니다. (${ttlMinutes}분 뒤 만료)`,
    `내 제어 링크: ${safe(share.controlUrl)}`,
    '              나만 쓰는 링크입니다. 열면 바로 조작할 수 있으니 남에게 주지 마세요.',
    'Ctrl+C 로 공유를 끝냅니다.',
  ].join('\n');
}

function withTimeout(promise, ms) {
  let timer;
  return Promise.race([
    promise,
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`${ms / 1000}초 안에 끝나지 않았습니다`)), ms);
    }),
  ]).finally(() => clearTimeout(timer));
}

export const shareCommand = {
  usage: 'qeploy share web <url> [--viewport 390x844] [--ttl 60] [--headed] [--json]',
  summary:
    '내 기기에서 도는 웹 앱 화면을 링크로 공유한다. 보는 사람은 설치 없이 보고, 내가 승인하면 조작한다 (WRITE 스코프 토큰 필요)',
  write: true,
  async run({ client, args, flags, deps, out, err }) {
    const kind = args[0];
    if (kind !== 'web') {
      throw new UsageError(
        kind ? `지원하지 않는 공유 대상입니다: ${kind} (지금은 web 만 됩니다)` : '공유할 대상이 필요합니다 (지금은 web).'
      );
    }
    const targetLabel = String(args[1] ?? '').trim();
    const target = parseTargetUrl(targetLabel);
    const viewport = flags.viewport === undefined ? null : parseViewport(flags.viewport);
    const ttlMinutes = flags.ttl === undefined ? undefined : parseTtl(flags.ttl);

    const rt = { ...defaultShareRuntime, ...(deps.share ?? {}) };
    const env = deps.env ?? process.env;

    // Before the link exists: a link nothing can serve would still take one of the five a user may
    // have open.
    const executable = rt.findChrome({ env });
    if (!executable) throw new Error(NO_CHROME_MESSAGE);

    const log = (text) => err(terminalSafe(text));
    let session = null;
    let chrome = null;
    let conn = null;
    let stopReason = null;

    const interrupt = () => {
      if (stopReason) {
        // Second Ctrl+C: the user does not want to wait for the revoke or for Chrome to exit.
        chrome?.killNow?.();
        rt.forceExit(130);
        return;
      }
      stopReason = 'interrupt';
      log('공유를 끝내는 중입니다… (한 번 더 누르면 바로 끝냅니다)');
      session?.stop('interrupt');
    };
    // Installed before the link is created, so Ctrl+C while it is being made still revokes it.
    const unsubscribe = rt.onInterrupt(interrupt);

    let share = null;
    let result = { reason: 'error' };
    let revoked = false;
    try {
      share = await client.createDeviceShare({
        kind: 'WEB',
        targetLabel: targetLabel.slice(0, MAX_LABEL_LENGTH),
        ttlMinutes,
      });
      if (!share?.shareId || !share?.tunnelUrl) {
        throw new Error('서버 응답에 공유 정보(shareId·tunnelUrl)가 없습니다.');
      }
      const tunnelUrl = checkTunnelUrl(share.tunnelUrl);

      // --json is for scripts that read the links, so it goes out now, not when the share ends.
      out(flags.json ? JSON.stringify(share, null, 2) : linksText(share, ttlMinutes ?? DEFAULT_TTL_MINUTES));

      if (stopReason) {
        result = { reason: stopReason };
      } else {
        chrome = await rt.launchChrome({ executable, headed: Boolean(flags.headed), viewport });
        conn = await rt.connectCdp(chrome.wsUrl);
        const { browser, page, targetId } = await rt.attachPage(conn);
        session = rt.createSession({
          target,
          targetLabel,
          viewport,
          browser,
          page,
          targetId,
          tunnelUrl,
          token: client.token,
          cliVersion: CLI_VERSION,
          askControl: (question) => rt.askControl(question, { onInterrupt: interrupt }),
          log,
        });
        chrome.onExit(() => session.stop('chrome-exit'));
        if (stopReason) session.stop(stopReason);
        result = await session.run();
      }
    } finally {
      unsubscribe();
      if (share?.shareId && !KEEP_LINK.has(result.reason)) {
        try {
          await withTimeout(Promise.resolve(client.revokeDeviceShare(share.shareId)), REVOKE_TIMEOUT_MS);
          revoked = true;
        } catch (e) {
          log(`링크를 회수하지 못했습니다 (${e.message}). 웹에서 회수하거나 만료를 기다려 주세요.`);
        }
      }
      if (session && result.reason === 'error') session.stop('error');
      conn?.close();
      if (chrome) await chrome.close().catch(() => {});
    }

    const { code, text } = ending(result);
    log(
      [text, revoked ? '링크를 회수했습니다.' : null, chrome ? 'Chrome 과 임시 프로필을 지웠습니다.' : null]
        .filter(Boolean)
        .join(' ')
    );
    return { printed: true, exitCode: code, data: share };
  },
};
