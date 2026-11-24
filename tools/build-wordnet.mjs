// Usage: node tools/build-wordnet.mjs <extracted WordNet-3.0 directory>
// Source: https://wordnetcode.princeton.edu/3.0/WordNet-3.0.tar.gz
import { spawnSync } from 'node:child_process';
import { copyFileSync, mkdirSync, readFileSync, renameSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const source = process.argv[2];
if (!source) throw new Error('Pass the extracted WordNet-3.0 directory.');
const assets = fileURLToPath(new URL('../app/src/main/assets/dictionary/', import.meta.url));
const database = join(assets, 'wordnet-3.0.sqlite.staging');
mkdirSync(assets, { recursive: true });
const parts = ['noun', 'verb', 'adj', 'adv'];
const sql = [
  '.bail on', 'PRAGMA journal_mode=OFF;', 'BEGIN;',
  'CREATE TABLE sense(id INTEGER PRIMARY KEY, pos INTEGER NOT NULL, gloss TEXT NOT NULL);',
  'CREATE TABLE term(word TEXT PRIMARY KEY, senses BLOB NOT NULL) WITHOUT ROWID;',
  'CREATE TABLE exception(word TEXT PRIMARY KEY, lemmas TEXT NOT NULL) WITHOUT ROWID;',
];
const quote = value => "'" + value.replaceAll("'", "''") + "'";
const lines = file => readFileSync(join(source, 'dict', file), 'utf8').split(/\r?\n/);
const normalize = value => value.normalize('NFKC').toLowerCase().replaceAll('_', ' ');
const terms = new Map();
const exceptions = new Map();
let senseId = 0;

for (const [pos, part] of parts.entries()) {
  const offsets = new Map();
  for (const line of lines('data.' + part)) {
    if (!/^\d{8} /.test(line)) continue;
    offsets.set(line.slice(0, 8), ++senseId);
    const gloss = line.slice(line.indexOf(' | ') + 3).trim();
    const pieces = gloss.split(/;\s*(?=")/);
    const examples = [];
    while (pieces.length > 1) {
      const example = /^"(.+)"$/.exec(pieces.at(-1));
      if (!example) break;
      pieces.pop();
      examples.unshift(example[1]);
    }
    const formattedGloss = [pieces.join('; '), ...examples].join('\n');
    sql.push(`INSERT INTO sense VALUES(${senseId},${pos},${quote(formattedGloss)});`);
  }

  for (const line of lines('index.' + part)) {
    if (!line || /^\s/.test(line)) continue;
    const fields = line.trim().split(/\s+/);
    const word = normalize(fields[0]);
    const ids = fields.slice(6 + Number(fields[3])).map(offset => offsets.get(offset));
    if (ids.length !== Number(fields[2]) || ids.some(id => id === undefined)) {
      throw new Error('Invalid sense offsets for ' + word);
    }
    terms.set(word, [...(terms.get(word) ?? []), ...ids]);
  }

  for (const line of lines(part + '.exc')) {
    const [word, ...lemmas] = line.trim().split(/\s+/).map(normalize);
    if (!word || !lemmas.length) continue;
    const candidates = lemmas.map(lemma => pos + '\t' + lemma);
    exceptions.set(word, [...new Set([...(exceptions.get(word) ?? []), ...candidates])]);
  }
}

for (const [word, ids] of [...terms].sort()) {
  const buffer = Buffer.alloc(ids.length * 4);
  ids.forEach((id, index) => buffer.writeInt32LE(id, index * 4));
  sql.push(`INSERT INTO term VALUES(${quote(word)},X'${buffer.toString('hex')}');`);
}
for (const [word, candidates] of [...exceptions].sort()) {
  sql.push(`INSERT INTO exception VALUES(${quote(word)},${quote(candidates.join('\n'))});`);
}
sql.push('COMMIT;', 'VACUUM;', 'PRAGMA integrity_check;');

try {
  const result = spawnSync('sqlite3', [database], { input: sql.join('\n'), encoding: 'utf8' });
  if (result.status !== 0 || result.stdout.trim().split(/\r?\n/).at(-1) !== 'ok') {
    throw new Error(result.error ?? result.stderr ?? 'SQLite integrity check failed.');
  }
  renameSync(database, join(assets, 'wordnet-3.0.sqlite'));
  copyFileSync(join(source, 'LICENSE'), join(assets, 'LICENSE-WordNet-3.0.txt'));
  console.log(`${terms.size} terms, ${senseId} senses, ${exceptions.size} inflected forms`);
} finally {
  rmSync(database, { force: true });
}
