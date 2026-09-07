/**
 * Terminal rendering.
 *
 * Tables are built from an explicit column spec rather than from whatever keys a response happens
 * to carry, so a field the server adds later does not silently widen every table. The cost is that
 * a field the server *renames* would leave a column empty — hence the fallback below.
 */

/**
 * Korean labels are wide characters; counting them as one column each would misalign every table.
 * The ranges cover CJK, Hangul, and full-width forms, which is what our output actually contains.
 */
export function displayWidth(text) {
  let width = 0;
  for (const ch of String(text)) {
    const c = ch.codePointAt(0);
    const wide =
      (c >= 0x1100 && c <= 0x115f) ||
      (c >= 0x2e80 && c <= 0xa4cf) ||
      (c >= 0xac00 && c <= 0xd7a3) ||
      (c >= 0xf900 && c <= 0xfaff) ||
      (c >= 0xfe30 && c <= 0xfe6f) ||
      (c >= 0xff00 && c <= 0xff60) ||
      (c >= 0xffe0 && c <= 0xffe6);
    width += wide ? 2 : 1;
  }
  return width;
}

function pad(text, width) {
  return text + ' '.repeat(Math.max(0, width - displayWidth(text)));
}

/** Empty and null render as a dash so a blank cell is visibly "no value" rather than a layout bug. */
function cell(value) {
  if (value === null || value === undefined || value === '') return '-';
  if (typeof value === 'boolean') return value ? 'yes' : 'no';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

/**
 * @param {object[]} rows
 * @param {{key: string, label: string}[]} columns
 * @returns {string} a table, or pretty JSON if the rows do not match the spec at all
 */
export function renderTable(rows, columns) {
  if (!Array.isArray(rows)) return JSON.stringify(rows, null, 2);
  if (rows.length === 0) return '(없음)';

  // If not one row carries any expected key, the response shape has moved. Printing a grid of
  // dashes would hide that; the raw data at least still answers the user's question.
  const matches = rows.some((r) => r && columns.some((c) => c.key in r));
  if (!matches) return JSON.stringify(rows, null, 2);

  const widths = columns.map((c) =>
    Math.max(displayWidth(c.label), ...rows.map((r) => displayWidth(cell(r?.[c.key]))))
  );
  const line = (cells) => cells.map((t, i) => pad(t, widths[i])).join('  ').trimEnd();

  return [
    line(columns.map((c) => c.label)),
    line(widths.map((w) => '─'.repeat(w))),
    ...rows.map((r) => line(columns.map((c) => cell(r?.[c.key])))),
  ].join('\n');
}

/** Detail view for a single object — same fallback rule as the table. */
export function renderDetail(obj, fields) {
  if (!obj || typeof obj !== 'object') return JSON.stringify(obj, null, 2);
  if (!fields.some((f) => f.key in obj)) return JSON.stringify(obj, null, 2);

  const width = Math.max(...fields.map((f) => displayWidth(f.label)));
  return fields
    .filter((f) => f.key in obj)
    .map((f) => `${pad(f.label, width)}  ${cell(obj[f.key])}`)
    .join('\n');
}
