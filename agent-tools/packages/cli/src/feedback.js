import { mkdir, writeFile, access } from 'node:fs/promises';
import { isAbsolute, join, relative, resolve } from 'node:path';
import { UsageError } from './confirm.js';
import { renderTable } from './render.js';

/**
 * `qeploy feedback` — pin feedback for people who do not use MCP (#683): the same prompt the MCP
 * tool gives, as a Markdown file and a picture next to it, ready to paste or attach.
 *
 * Only what the owner has taken (ACCEPTED and later) is reachable with a PAT; taking and turning
 * down happen in the share terminal or on the web, by a person (§14.7).
 */

const STATUSES = ['ACCEPTED', 'FIXED', 'CONFIRMED'];
const STATUS_TEXT = { ACCEPTED: '받음', FIXED: '고침', CONFIRMED: '확인함', NEW: '받기 전', DISMISSED: '받지 않음' };
const DEFAULT_DIR = join('.qeploy', 'feedback');
const MAX_NOTE = 300;

const columns = [
  { key: 'feedbackId', label: 'ID' },
  { key: 'state', label: '상태' },
  { key: 'screenLabel', label: '화면' },
  { key: 'label', label: '요소' },
  { key: 'component', label: '컴포넌트' },
  { key: 'author', label: '남긴 사람' },
  { key: 'comment', label: '글' },
];

function feedbackId(raw) {
  if (raw === undefined) throw new UsageError('피드백 ID 가 필요합니다. qeploy feedback 으로 목록을 볼 수 있습니다.');
  const id = Number(raw);
  if (!Number.isInteger(id) || id <= 0) throw new UsageError(`피드백 ID 는 양의 정수여야 합니다: ${raw}`);
  return id;
}

const short = (text, n) => {
  const t = String(text ?? '').replace(/\s+/g, ' ').trim();
  return Array.from(t).length > n ? `${Array.from(t).slice(0, n).join('')}…` : t;
};

async function list({ client, flags }) {
  const status = flags.status === undefined ? 'ACCEPTED' : String(flags.status).toUpperCase();
  if (!STATUSES.includes(status)) throw new UsageError(`--status 는 ${STATUSES.join('·')} 중 하나입니다: ${flags.status}`);
  let limit;
  if (flags.limit !== undefined) {
    limit = Number(flags.limit);
    if (!Number.isInteger(limit) || limit < 1 || limit > 50) throw new UsageError(`--limit 는 1~50 입니다: ${flags.limit}`);
  }
  const data = await client.listShareFeedback({ status, limit });
  const rows = (Array.isArray(data) ? data : []).map((f) => ({
    feedbackId: f?.feedbackId,
    state: STATUS_TEXT[f?.status] ?? f?.status,
    screenLabel: f?.screenLabel,
    label: f?.label,
    component: f?.component,
    author: f?.authorRole === 'OWNER' ? '나' : `손님 ${f?.authorName ?? ''}`.trim(),
    comment: short(f?.body, 40),
  }));
  const hint = rows.length ? '\n\nqeploy feedback show <ID> 로 에이전트용 프롬프트와 이미지를 받습니다.' : '';
  return { data, text: renderTable(rows, columns) + hint };
}

/**
 * Prints the prompt and saves it with the crop (and the screen with --screen) under
 * `.qeploy/feedback/`. That folder gets a `.gitignore` of its own: the pictures are of a running
 * app and can show anything that was on screen, and they do not belong in the repository.
 */
async function show({ client, args, flags, deps }) {
  const id = feedbackId(args[1]);
  const [detail, prompt] = await Promise.all([client.getShareFeedback(id), client.getShareFeedbackPrompt(id)]);
  const cwd = deps.cwd?.() ?? process.cwd();
  const customOut = flags.out !== undefined && flags.out !== true;
  const dir = resolve(cwd, customOut ? String(flags.out) : DEFAULT_DIR);
  await mkdir(dir, { recursive: true });
  if (!customOut) await ignoreFolder(resolve(cwd, '.qeploy'));

  const shown = (path) => {
    const rel = relative(cwd, path);
    return rel && !rel.startsWith('..') && !isAbsolute(rel) ? rel : path;
  };
  const files = {};
  const lines = [];
  const pictures = [];
  if (detail?.hasCrop) pictures.push(['crop', `${id}.jpg`, '짚은 요소']);
  if (flags.screen && detail?.hasScreen) pictures.push(['screen', `${id}-screen.jpg`, '짚은 순간의 화면']);
  for (const [kind, name, what] of pictures) {
    const path = join(dir, name);
    await writeFile(path, await client.getShareFeedbackImage(id, kind));
    files[kind] = shown(path);
    lines.push(`- ${what}: ${files[kind]}`);
  }
  const markdown = String(prompt?.markdown ?? '').trimEnd() + (lines.length ? `\n\n## 이미지\n\n${lines.join('\n')}\n` : '\n');
  const mdPath = join(dir, `${id}.md`);
  await writeFile(mdPath, markdown);
  files.markdown = shown(mdPath);

  const saved = [files.markdown, files.crop, files.screen].filter(Boolean).join(', ');
  return {
    data: { feedback: detail?.feedback ?? null, markdown, files },
    text: `${markdown}\n저장했습니다: ${saved}\n고친 뒤 qeploy feedback fixed ${id} --note "무엇을 바꿨는지" 로 표시하면 짚은 사람에게 알림이 갑니다.`,
  };
}

async function ignoreFolder(dir) {
  const path = join(dir, '.gitignore');
  try {
    await access(path);
  } catch {
    await writeFile(path, '# qeploy feedback show 가 저장한 프롬프트와 화면 이미지. 저장소에 올리지 않는다.\n*\n');
  }
}

async function fixed({ client, args, flags, confirm }) {
  const id = feedbackId(args[1]);
  let note;
  if (flags.note !== undefined) {
    note = String(flags.note).trim();
    if (Array.from(note).length > MAX_NOTE) throw new UsageError(`--note 는 ${MAX_NOTE}자까지입니다.`);
  }
  // Naming the feedback in the prompt: "#12" alone gives nothing to check against.
  let label = `피드백 #${id}`;
  try {
    const detail = await client.getShareFeedback(id);
    if (detail?.feedback?.label) label = `피드백 #${id} (${detail.feedback.label})`;
  } catch {
    // the confirmation matters more than the name
  }
  const ok = await confirm(`${label} 를 고쳤다고 표시합니다. 짚은 사람의 화면에 알림이 갑니다. 진행할까요?`, flags);
  if (!ok) return { data: null, text: '취소했습니다.', cancelled: true };
  const data = await client.markShareFeedbackFixed(id, { note: note || undefined });
  return { data, text: `${label} 를 고침으로 표시했습니다. 짚은 사람이 확인하거나 다시 열 수 있습니다.` };
}

export const feedbackCommand = {
  usage:
    'qeploy feedback [list] [--status ACCEPTED|FIXED|CONFIRMED] [--limit 20]\n' +
    '  qeploy feedback show <ID> [--out <폴더>] [--screen]\n' +
    '  qeploy feedback fixed <ID> [--note "무엇을 바꿨는지"] [--yes]',
  summary:
    '공유 화면을 짚어 남긴 피드백 — 받은 목록, 에이전트용 프롬프트와 이미지 저장(.qeploy/feedback), 고침 표시(WRITE 스코프 토큰 필요)',
  async run(ctx) {
    const sub = ctx.args[0] ?? 'list';
    switch (sub) {
      case 'list':
        return list(ctx);
      case 'show':
        return show(ctx);
      case 'fixed':
        return fixed(ctx);
      default:
        throw new UsageError(`feedback 다음에는 list·show·fixed 중 하나가 옵니다: ${sub}`);
    }
  },
};
