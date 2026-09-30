// Builds the publishable package into dist/: the API types generated from web/openapi.json, the
// client compiled from src/, and a package.json stamped with the release version (ADR-0149).
//   node packages/client/build.mjs <YYYY.MM.PATCH>
import { execFileSync } from 'node:child_process';
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const web = join(here, '..', '..');
const dist = join(here, 'dist');
const calver = process.argv[2];
if (!/^\d{4}\.\d{2}\.\d+$/.test(calver ?? '')) {
  console.error('usage: node packages/client/build.mjs <YYYY.MM.PATCH>');
  process.exit(2);
}
// npm versions are semver, which forbids a leading zero: Studio 2026.09.0 is the client 2026.9.0.
const version = calver
  .split('.')
  .map((part) => String(Number(part)))
  .join('.');

const app = JSON.parse(readFileSync(join(web, 'package.json'), 'utf8'));
const fetchVersion = app.devDependencies['openapi-fetch'];
if (!/^\d/.test(fetchVersion ?? ''))
  throw new Error(`web/package.json must pin openapi-fetch exactly (found ${fetchVersion})`);

rmSync(dist, { recursive: true, force: true });
mkdirSync(dist, { recursive: true });
// The types come from the document itself, so the client cannot drift from it. src/schema.d.ts is not committed.
execFileSync('npx', ['openapi-typescript', 'openapi.json', '-o', join(here, 'src/schema.d.ts')], {
  cwd: web,
  stdio: 'inherit',
});
execFileSync('npx', ['tsc', '-p', join(here, 'tsconfig.json')], { cwd: web, stdio: 'inherit' });
// tsc emits nothing for a .d.ts input.
cpSync(join(here, 'src/schema.d.ts'), join(dist, 'schema.d.ts'));
cpSync(join(here, 'README.md'), join(dist, 'README.md'));

const source = JSON.parse(readFileSync(join(here, 'package.json'), 'utf8'));
delete source.private;
writeFileSync(
  join(dist, 'package.json'),
  JSON.stringify({ ...source, version, dependencies: { 'openapi-fetch': fetchVersion } }, null, 2) + '\n',
);
console.log(`@artemis-studio/client ${version} built in ${dist}`);
