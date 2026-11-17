import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { update, difference } from './update-test-sources.mjs';

test('差异报告保留字段级新增修改移除', () => {
  const source = { bookSourceUrl: 'https://example.com', bookSourceName: 'fixture', loginUrl: '/old' };
  const report = difference([source, { ...source, bookSourceUrl: 'removed' }], [{ ...source, loginUrl: '/new' }, { ...source, bookSourceUrl: 'added' }]);
  assert.equal(report.added.length, 1);
  assert.equal(report.removed.length, 1);
  assert.deepEqual(report.modified[0].fields, ['loginUrl']);
});

test('资产损坏和API失败均不替换已验证快照', async () => {
  const cache = await mkdtemp(join(tmpdir(), 'inkwell-source-test-'));
  const books = JSON.stringify([{ bookSourceUrl: 'https://example.com', bookSourceName: 'fixture' }]);
  const rules = '[{"name":"fixture"}]';
  let broken = false, blocked = false;
  const fetcher = async url => {
    if (blocked) return new Response('blocked', { status: 403 });
    if (String(url).includes('api.github.com')) return Response.json({ tag_name: 'dynamic-fixture', published_at: new Date().toISOString(), assets: [
      { name: 'books.json', size: Buffer.byteLength(books), browser_download_url: 'https://github.com/fixture/books.json' },
      { name: 'clean_rules.json', size: Buffer.byteLength(rules), browser_download_url: 'https://github.com/fixture/clean_rules.json' },
    ] });
    return new Response(String(url).endsWith('books.json') ? (broken ? 'bad JSON' : books) : rules);
  };
  try {
    await update(cache, fetcher);
    const pointer = await readFile(join(cache, 'current.json'), 'utf8');
    broken = true;
    await assert.rejects(update(cache, fetcher), /size mismatch/);
    assert.equal(await readFile(join(cache, 'current.json'), 'utf8'), pointer);
    blocked = true;
    await assert.rejects(update(cache, fetcher), /403/);
    assert.equal(await readFile(join(cache, 'current.json'), 'utf8'), pointer);
  } finally { await rm(cache, { recursive: true, force: true }); }
});
