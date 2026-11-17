// 仅开发验证：下载结果保留在 tmp，不修改用户来源，也不参与 APK 打包。
import { mkdir, readFile, writeFile, rename } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createHash, randomUUID } from 'node:crypto';

const api = 'https://api.github.com/repos/luanmuc/legado-book-sources/releases/latest';
const limit = 32 * 1024 * 1024;
const hash = bytes => createHash('sha256').update(bytes).digest('hex');

export function validate(name, bytes) {
  const data = JSON.parse(bytes.toString('utf8'));
  if (!Array.isArray(data) || !data.length || data.some(row => !row || typeof row !== 'object' || Array.isArray(row))) throw Error(`${name}: invalid array`);
  if (name === 'books.json' && data.some(row => typeof row.bookSourceUrl !== 'string' || !row.bookSourceUrl.trim() || typeof row.bookSourceName !== 'string')) throw Error('books.json: invalid source');
  return data;
}

export function difference(previous, next) {
  const index = rows => new Map(rows.map(row => [row.bookSourceUrl, row]));
  const before = index(previous), after = index(next);
  const added = [], removed = [], modified = [];
  for (const [id, row] of after) {
    if (!before.has(id)) added.push(id);
    else {
      const old = before.get(id);
      const fields = [...new Set([...Object.keys(old), ...Object.keys(row)])].filter(key => JSON.stringify(old[key]) !== JSON.stringify(row[key]));
      if (fields.length) modified.push({ id, fields });
    }
  }
  for (const id of before.keys()) if (!after.has(id)) removed.push(id);
  return { added, removed, modified };
}

export async function update(cache, fetcher = fetch) {
  const request = async (url, headers = {}) => {
    const response = await fetcher(url, { headers, signal: AbortSignal.timeout(60000) });
    if (!response.ok) throw Error(`HTTP ${response.status}`);
    const declared = Number(response.headers.get('content-length'));
    if (declared > limit) throw Error('asset too large');
    const chunks = []; let size = 0;
    for await (const chunk of response.body) {
      size += chunk.length;
      if (size > limit) throw Error('asset too large');
      chunks.push(chunk);
    }
    return Buffer.concat(chunks);
  };
  const headers = { Accept: 'application/vnd.github+json', 'User-Agent': 'inkwell-auth-dev-validation' };
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  const release = JSON.parse((await request(api, headers)).toString('utf8'));
  if (!release.tag_name || !release.published_at || release.draft || release.prerelease || !Array.isArray(release.assets)) throw Error('invalid release');
  const files = {}, records = {};
  for (const name of ['books.json', 'clean_rules.json']) {
    const matches = release.assets.filter(asset => asset.name === name);
    if (matches.length !== 1) throw Error(`missing or duplicate asset: ${name}`);
    const asset = matches[0];
    const url = new URL(asset.browser_download_url);
    if (url.protocol !== 'https:' || url.hostname !== 'github.com') throw Error('invalid asset URL');
    if (!Number.isSafeInteger(asset.size) || asset.size <= 0 || asset.size > limit) throw Error('invalid asset size');
    const bytes = await request(url.toString());
    if (bytes.length !== asset.size) throw Error(`${name}: size mismatch`);
    const sha256 = hash(bytes);
    if (asset.digest && asset.digest !== `sha256:${sha256}`) throw Error(`${name}: digest mismatch`);
    files[name] = { bytes, rows: validate(name, bytes) };
    records[name] = { bytes: bytes.length, sha256, url: url.toString() };
  }
  let previous = [];
  try {
    const pointer = JSON.parse(await readFile(join(cache, 'current.json'), 'utf8'));
    // 指针只接受本工具生成的 UUID，避免读取缓存目录之外的数据。
    if (!/^[0-9a-f-]{36}$/.test(pointer.snapshot)) throw Error('invalid snapshot pointer');
    const old = await readFile(join(cache, pointer.snapshot, 'books.json'));
    if (hash(old) !== pointer.assets['books.json'].sha256) throw Error('cached hash mismatch');
    previous = validate('books.json', old);
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
  const metadata = { tag_name: release.tag_name, published_at: release.published_at, tested_at: new Date().toISOString(), snapshot: randomUUID(), assets: records };
  const directory = join(cache, metadata.snapshot);
  await mkdir(directory, { recursive: true });
  for (const [name, file] of Object.entries(files)) await writeFile(join(directory, name), file.bytes);
  await writeFile(join(directory, 'diff.json'), JSON.stringify(difference(previous, files['books.json'].rows), null, 2));
  const matrix = files['books.json'].rows.map(row => ({
    source: row.bookSourceUrl, name: row.bookSourceName, group: row.bookSourceGroup,
    loginUrl: Boolean(row.loginUrl), loginUi: Boolean(row.loginUi),
    import: 'NOT_TESTED', login: 'NOT_TESTED', search: 'NOT_TESTED', toc: 'NOT_TESTED', content: 'NOT_TESTED',
  }));
  await writeFile(join(directory, 'matrix.json'), JSON.stringify(matrix, null, 2));
  await writeFile(join(directory, 'metadata.json'), JSON.stringify(metadata, null, 2));
  // 所有下载、结构和哈希检查成功后才替换指针；失败时保留最近已验证快照。
  const temporary = join(cache, metadata.snapshot + '.tmp');
  await writeFile(temporary, JSON.stringify(metadata, null, 2));
  await rename(temporary, join(cache, 'current.json'));
  return metadata;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { console.log(JSON.stringify(await update(resolve(process.argv[2] || 'tmp/test-sources')), null, 2)); }
  catch (error) { console.error(`BLOCKED: ${error.message}; verified cache retained`); process.exitCode = 1; }
}
