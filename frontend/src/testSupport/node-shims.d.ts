// Minimal typings for the Node built-ins that tests use (the project does not depend on @types/node).
declare module 'node:fs' {
  export function readFileSync(path: string | URL, encoding: 'utf8'): string;
}
