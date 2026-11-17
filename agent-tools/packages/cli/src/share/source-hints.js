/**
 * Where in the owner's project a pinned element probably lives (§14.3 `sourceHints`, #682).
 *
 * The search runs on the device, in the project folder, so no code leaves the machine — only a
 * relative path, a line number and why it was picked (COMPONENT · ID · TEXT). It is a hint for the
 * coding agent, not an answer: bounded to about a second and five candidates.
 */
import { existsSync, readdirSync, realpathSync, statSync } from 'node:fs';
import { readdir, readFile, stat } from 'node:fs/promises';
import { basename, extname, isAbsolute, join, normalize, relative, resolve, sep } from 'node:path';

export const MAX_HINTS = 5;
export const HINT_BUDGET_MS = 1000;
const MAX_FILES = 4000;
const MAX_FILE_BYTES = 256 * 1024;
const MAX_PATH = 300;
const LIST_TTL_MS = 30_000;

/** Never searched: dependencies, VCS, build output, caches, IDE state. */
const SKIP_DIRS = new Set([
  'node_modules', 'bower_components', 'jspm_packages', 'vendor', 'dist', 'build', 'out', 'target', 'coverage',
  'Pods', 'DerivedData', 'tmp', 'temp', '__pycache__', 'venv',
]);
const SOURCE_EXT = new Set([
  '.tsx', '.jsx', '.vue', '.svelte', '.astro', '.ts', '.js', '.mjs', '.cjs', '.html', '.htm', '.json', '.xml',
  '.kt', '.java', '.swift', '.dart', '.storyboard', '.xib',
]);
/** UI files first: a hit there is more likely the element than one in a helper or a fixture. */
const EXT_RANK = { '.tsx': 0, '.jsx': 0, '.vue': 0, '.svelte': 0, '.astro': 0, '.xml': 1, '.kt': 1, '.swift': 1, '.dart': 1, '.storyboard': 1, '.xib': 1, '.java': 2, '.html': 2, '.htm': 2, '.ts': 3, '.js': 3, '.mjs': 3, '.cjs': 3, '.json': 4 };
const SKIP_FILES = /(^|\/)(package-lock\.json|npm-shrinkwrap\.json|composer\.lock|[^/]*\.min\.js|[^/]*\.map)$/;

/** Files that say "this folder is a project". Without one, the current folder is not searched. */
const PROJECT_MARKERS = [
  'package.json', '.git', 'build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts', 'pom.xml',
  'pubspec.yaml', 'Package.swift', 'project.yml', 'Cargo.toml', 'go.mod', 'pyproject.toml', 'deno.json',
];

/**
 * The folder to search, or null.
 *
 * `--project` is taken as given (it must be a folder). The current folder is used only when it
 * looks like a project: running `qeploy share` from a home folder must not walk the home folder.
 *
 * @returns {{dir: string|null, why: string}}
 */
export function projectRoot({ flag, cwd }) {
  if (flag !== undefined) {
    const dir = resolve(cwd, String(flag));
    try {
      if (statSync(dir).isDirectory()) return { dir: realpathSync(dir), why: 'flag' };
    } catch {
      // falls through
    }
    return { dir: null, why: 'missing' };
  }
  const dir = resolve(cwd);
  const marked = PROJECT_MARKERS.some((m) => existsSync(join(dir, m))) || hasXcodeProject(dir);
  return marked ? { dir: realpathSync(dir), why: 'cwd' } : { dir: null, why: 'not-project' };
}

function hasXcodeProject(dir) {
  try {
    return readdirSync(dir).some((name) => name.endsWith('.xcodeproj') || name.endsWith('.xcworkspace'));
  } catch {
    return false;
  }
}

/**
 * A page-reported source location → a path relative to the project, or null.
 *
 * Accepts what dev servers expose: an absolute file path (React `_debugSource`, Vue `__file`), a
 * path relative to the dev server root (Svelte), or a module URL from a stack trace (React 19,
 * `/src/App.tsx?t=…`, Vite's `/@fs/<absolute>`). The page is the owner's app, but nothing it says
 * may point outside the project, into dependencies, or through a link to elsewhere.
 *
 * @param {{path?: string, url?: string}} src
 * @param {string|null} project
 * @param {string|null} [origin] the shared page's origin; a URL from anywhere else is ignored
 */
export function toProjectPath(src, project, origin = null) {
  if (!project || !src || typeof src !== 'object') return null;
  let candidate = null;
  if (typeof src.url === 'string') {
    let url;
    try {
      url = new URL(src.url);
    } catch {
      return null;
    }
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
    if (origin && url.origin !== origin) return null;
    let path;
    try {
      path = decodeURIComponent(url.pathname);
    } catch {
      return null;
    }
    candidate = path.startsWith('/@fs/') ? path.slice('/@fs'.length) : path.replace(/^\/+/, '');
  } else if (typeof src.path === 'string') {
    candidate = src.path;
  }
  if (!candidate || candidate.includes('\0')) return null;
  let real;
  try {
    // The real path decides: a link inside the project may point outside it, and macOS reports
    // /tmp for /private/tmp.
    real = realpathSync(isAbsolute(candidate) ? normalize(candidate) : resolve(project, candidate));
    if (!statSync(real).isFile()) return null;
  } catch {
    return null;
  }
  return inside(project, real);
}

/** `abs` relative to `project` with forward slashes, or null when it is outside or in a skipped folder. */
function inside(project, abs) {
  const rel = relative(project, abs);
  if (!rel || rel.startsWith('..') || isAbsolute(rel)) return null;
  const posix = rel.split(sep).join('/');
  if (posix.length > MAX_PATH) return null;
  if (posix.split('/').some((part) => part.startsWith('.') || SKIP_DIRS.has(part))) return null;
  return posix;
}

// ── listing ────────────────────────────────────────────────────────────────

const listings = new Map();

/** Source files under `project`, best-ranked first. Cached briefly: one person pins many times. */
export async function listSourceFiles(project, { deadline = Infinity, now = Date.now } = {}) {
  const cached = listings.get(project);
  if (cached && now() - cached.at < LIST_TTL_MS) return cached.files;
  const files = [];
  const queue = [''];
  let complete = true;
  while (queue.length) {
    if (now() > deadline || files.length >= MAX_FILES) {
      complete = false;
      break;
    }
    const dir = queue.shift();
    let entries;
    try {
      entries = await readdir(join(project, dir), { withFileTypes: true });
    } catch {
      continue;
    }
    for (const e of entries) {
      if (e.name.startsWith('.') || e.isSymbolicLink()) continue;
      const rel = dir ? `${dir}/${e.name}` : e.name;
      if (e.isDirectory()) {
        if (!SKIP_DIRS.has(e.name)) queue.push(rel);
      } else if (e.isFile() && SOURCE_EXT.has(extname(e.name).toLowerCase()) && !SKIP_FILES.test(rel) && rel.length <= MAX_PATH) {
        files.push(rel);
      }
    }
  }
  files.sort((a, b) => rank(a) - rank(b) || a.length - b.length || (a < b ? -1 : 1));
  // A listing cut short by the clock is used once but not kept, so the next pin can finish it.
  if (complete) listings.set(project, { at: now(), files });
  return files;
}

function rank(file) {
  const ext = EXT_RANK[extname(file).toLowerCase()] ?? 5;
  const inSource = /^(src|app|lib|components|pages|routes|views|res)\//.test(file) || file.includes('/src/') ? 0 : 1;
  const test = /(^|\/)(test|tests|__tests__|e2e|stories)\/|\.(test|spec|stories)\.[a-z]+$/i.test(file) ? 1 : 0;
  return test * 100 + inSource * 10 + ext;
}

/** Forgets cached listings (tests). */
export function clearListings() {
  listings.clear();
}

// ── search ─────────────────────────────────────────────────────────────────

const escape = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * Where a component is defined: a function or class (JS/TS, Java), a Kotlin `fun`/`class`/`object`
 * (Compose, Activities, custom views), a Swift `struct`/`class`, or `const Name =` (arrow
 * components, `styled`). The name is the first capture that matched.
 */
const definitionPattern = (alternatives) =>
  new RegExp(
    `\\b(?:function|class|fun|object|interface|struct)\\s+(${alternatives})\\b|\\b(?:const|let|var|val)\\s+(${alternatives})\\s*[=:]`
  );
const lineAt = (body, index) => body.slice(0, index).split('\n').length;
const stem = (file) => basename(file).replace(/\.[^.]+$/, '');

/**
 * The line where `name` is defined in `file` (project-relative), or null.
 *
 * A stack-trace line from a dev server is a line of the code it served (imports and refresh
 * preamble added), not of the file on disk, so a component known that way is located here instead.
 */
export async function definitionLine(project, file, name) {
  if (!project || !file || !/^[A-Z][A-Za-z0-9_$]{1,60}$/.test(name)) return null;
  try {
    const full = join(project, file);
    if ((await stat(full)).size > MAX_FILE_BYTES) return null;
    const body = await readFile(full, 'utf8');
    const m = definitionPattern(escape(name)).exec(body);
    return m ? lineAt(body, m.index) : null;
  } catch {
    return null;
  }
}

/**
 * Where an id is set: web `id="…"`, Android `@+id/…`·`R.id.…`, iOS `accessibilityIdentifier("…")`
 * (SwiftUI) or `= "…"` (UIKit), a storyboard's `identifier="…"`, React Native's `testID` (which iOS
 * reports as the id).
 */
function idPattern(id) {
  const e = escape(id);
  return new RegExp(
    `\\b(?:id|testID)\\s*=\\s*\\{?\\s*["'\`]${e}["'\`]|@\\+?id/${e}\\b|\\bR\\.id\\.${e}\\b` +
      `|\\baccessibilityIdentifier\\s*(?:\\(\\s*|=\\s*)"${e}"|\\bidentifier="${e}"`
  );
}

/** Visible text worth searching for: short, and more than digits or punctuation. */
export function searchableText(text) {
  if (typeof text !== 'string') return null;
  const t = text.trim();
  const length = Array.from(t).length;
  if (length < 2 || length > 80) return null;
  if (!/[\p{L}]/u.test(t)) return null;
  return t;
}

/**
 * @param {object} o
 * @param {string|null} o.project
 * @param {{name: string, file?: string, line?: number}[]} [o.components] innermost first; a `file`
 *   is already project-relative (toProjectPath)
 * @param {{id?: string|null, text?: string|null}} [o.element]
 * @param {{id: string, scope: number}[]} [o.scopeIds] ids of the wider scope steps (§15.3): the
 *   person may widen to one of them after pinning, so each is looked for now, and its hint says
 *   which step it came from
 * @returns {Promise<{file: string, line?: number, reason: 'COMPONENT'|'ID'|'TEXT', scope?: number}[]>}
 */
export async function findSourceHints({ project, components = [], element = {}, scopeIds = [], max = MAX_HINTS, budgetMs = HINT_BUDGET_MS, now = Date.now }) {
  if (!project) return [];
  const deadline = now() + budgetMs;
  const hints = [];
  const seen = new Set();
  const add = (file, line, reason, scope) => {
    if (hints.length >= max) return;
    const key = `${file}:${line ?? 0}`;
    // One file once per reason (and step) is enough; a second line in the same file adds little.
    if (seen.has(key) || hints.some((h) => h.file === file && h.reason === reason && h.scope === scope)) return;
    seen.add(key);
    const hint = line > 0 ? { file, line, reason } : { file, reason };
    if (scope > 0) hint.scope = scope;
    hints.push(hint);
  };

  // 1. The dev server said where the component is.
  for (const c of components) if (c.file) add(c.file, c.line, 'COMPONENT');
  if (hints.length >= max) return hints;

  const names = [...new Set(components.filter((c) => !c.file).map((c) => c.name))]
    .filter((n) => /^[A-Z][A-Za-z0-9_$]{1,60}$/.test(n))
    .slice(0, 4);
  const validId = (v) => typeof v === 'string' && /^[A-Za-z0-9_.-]{2,80}$/.test(v);
  const id = validId(element.id) ? element.id : null;
  const text = searchableText(element.text);
  const wider = scopeIds.filter((s) => validId(s.id) && s.id !== id && Number.isInteger(s.scope) && s.scope > 0).slice(0, 4);
  if (!names.length && !id && !text && !wider.length) return hints;

  const files = await listSourceFiles(project, { deadline, now });

  // 2. Components known only by name, the id and the visible text, in one pass over the files.
  const alternatives = names.map(escape).join('|');
  const defRe = names.length ? definitionPattern(alternatives) : null;
  // Web `id="…"`, Android `@+id/…`·`R.id.…`, iOS `accessibilityIdentifier("…")` (SwiftUI) or `= "…"`
  // (UIKit), a storyboard's `identifier="…"`, React Native's `testID` (which iOS reports as the id).
  const idRe = id ? idPattern(id) : null;
  // A wider step's id: its first place in the project is enough (one hint per step).
  const widerRes = wider.map((s) => ({ re: idPattern(s.id), scope: s.scope }));
  const found = { def: [], id: [], text: [], textRef: [], wider: [] };
  const stringNames = [];
  const want = max;
  for (const file of files) {
    if (now() > deadline) break;
    if ((!defRe || found.def.length >= want) && (!idRe || found.id.length >= want) && (!text || found.text.length >= want)
      && found.wider.length >= widerRes.length) break;
    let body;
    try {
      const full = join(project, file);
      if ((await stat(full)).size > MAX_FILE_BYTES) continue;
      body = await readFile(full, 'utf8');
    } catch {
      continue;
    }
    if (defRe && found.def.length < want) {
      const m = defRe.exec(body);
      if (m) found.def.push({ file, line: lineAt(body, m.index), name: m[1] || m[2] });
    }
    if (idRe && found.id.length < want) {
      const m = idRe.exec(body);
      if (m) found.id.push({ file, line: lineAt(body, m.index) });
    }
    for (const w of widerRes) {
      if (found.wider.some((f) => f.scope === w.scope)) continue;
      const m = w.re.exec(body);
      if (m) found.wider.push({ file, line: lineAt(body, m.index), scope: w.scope });
    }
    if (text && found.text.length < want) {
      const i = body.indexOf(text);
      if (i >= 0) {
        found.text.push({ file, line: lineAt(body, i) });
        // Android: the text lives in res/values*/strings.xml; the layout or code uses its name.
        if (/(^|\/)values[^/]*\/[^/]+\.xml$/.test(file)) {
          const name = /<string\s+name="([A-Za-z0-9_.]+)"[^>]*>\s*$/.exec(body.slice(body.lastIndexOf('\n', i) + 1, i));
          if (name && !stringNames.includes(name[1])) stringNames.push(name[1]);
        }
      }
    }
  }
  if (stringNames.length && now() <= deadline) {
    const refs = stringNames.slice(0, 3).map(escape).join('|');
    const refRe = new RegExp(`@string/(?:${refs})\\b|\\bR\\.string\\.(?:${refs})\\b`);
    for (const file of files) {
      if (now() > deadline || found.textRef.length >= want) break;
      if (/(^|\/)values[^/]*\//.test(file) || !/\.(xml|kt|java)$/.test(file)) continue;
      try {
        const full = join(project, file);
        if ((await stat(full)).size > MAX_FILE_BYTES) continue;
        const body = await readFile(full, 'utf8');
        const m = refRe.exec(body);
        if (m) found.textRef.push({ file, line: lineAt(body, m.index) });
      } catch {
        // unreadable: skip
      }
    }
  }
  // A definition in the file named after the component beats one elsewhere; a file name alone
  // (no definition read in time) still beats nothing.
  for (const name of names) {
    const defs = found.def.filter((d) => d.name === name);
    const def = defs.find((d) => stem(d.file) === name) ?? defs[0];
    if (def) add(def.file, def.line, 'COMPONENT');
    else {
      const file = files.find((f) => stem(f) === name);
      if (file) add(file, null, 'COMPONENT');
    }
  }
  for (const h of found.id) add(h.file, h.line, 'ID');
  // The wider steps' ids, narrowest first: the person may widen to them (§15.3).
  for (const h of found.wider.sort((a, b) => a.scope - b.scope)) add(h.file, h.line, 'ID', h.scope);
  // Where the text is used (a layout, a screen) before where it is defined (strings.xml).
  for (const h of found.textRef) add(h.file, h.line, 'TEXT');
  for (const h of found.text) add(h.file, h.line, 'TEXT');
  return hints;
}
