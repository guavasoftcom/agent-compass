/*
Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If not,
see <https://www.gnu.org/licenses/>.
*/

// A small colored language badge for the Analyze Trace dialog's Files section — reads a file's
// language off its extension (or, for the handful of common extension-less filenames, off the
// filename itself) rather than pulling in a per-language icon/logo package: this app already
// favors hand-built SVG/CSS over a component library for every other visualization (see
// frontend/CLAUDE.md's "Charts and grids" section), and there is no real Material Design glyph
// for "this is a Java file" to reach for anyway — @mui/icons-material carries a handful of
// web-stack icons (Html/Css/Javascript/Php) and nothing for Java, Python, Go, Rust, etc. A short,
// colored text label reads the file's language at a glance without either gap. Pure, no React —
// the rendered chip lives in AnalyzeTraceDialogView.tsx, same split as summarizeTraceWork.ts.
export interface FileTypeBadge {
  label: string;
  color: string;
}

// Colors are the widely-recognized ones each language/format's own tooling and editors already
// use (the same associations GitHub's public linguist language-color list documents) — chosen for
// recognizability, not any brand's specific logo mark.
const EXTENSION_BADGES: Record<string, FileTypeBadge> = {
  java: { label: 'JAVA', color: '#e76f00' },
  kt: { label: 'KT', color: '#7f52ff' },
  kts: { label: 'KT', color: '#7f52ff' },
  ts: { label: 'TS', color: '#3178c6' },
  tsx: { label: 'TSX', color: '#3178c6' },
  js: { label: 'JS', color: '#d7b60a' },
  jsx: { label: 'JSX', color: '#61dafb' },
  mjs: { label: 'JS', color: '#d7b60a' },
  cjs: { label: 'JS', color: '#d7b60a' },
  py: { label: 'PY', color: '#3776ab' },
  rb: { label: 'RB', color: '#cc342d' },
  go: { label: 'GO', color: '#00add8' },
  rs: { label: 'RS', color: '#dea584' },
  php: { label: 'PHP', color: '#777bb4' },
  c: { label: 'C', color: '#5c6bc0' },
  h: { label: 'H', color: '#5c6bc0' },
  cpp: { label: 'C++', color: '#00599c' },
  hpp: { label: 'C++', color: '#00599c' },
  cs: { label: 'C#', color: '#178600' },
  sql: { label: 'SQL', color: '#e38c00' },
  md: { label: 'MD', color: '#719cd6' },
  mdx: { label: 'MD', color: '#719cd6' },
  json: { label: 'JSON', color: '#a3a326' },
  yml: { label: 'YML', color: '#cb171e' },
  yaml: { label: 'YML', color: '#cb171e' },
  toml: { label: 'TOML', color: '#9c4221' },
  xml: { label: 'XML', color: '#e37933' },
  html: { label: 'HTML', color: '#e34c26' },
  htm: { label: 'HTML', color: '#e34c26' },
  css: { label: 'CSS', color: '#563d7c' },
  scss: { label: 'SCSS', color: '#c6538c' },
  sass: { label: 'SASS', color: '#c6538c' },
  less: { label: 'LESS', color: '#1d365d' },
  sh: { label: 'SH', color: '#4d8f3c' },
  bash: { label: 'SH', color: '#4d8f3c' },
  zsh: { label: 'SH', color: '#4d8f3c' },
  properties: { label: 'PROPS', color: '#6d6d6d' },
  gradle: { label: 'GRADLE', color: '#5290c6' },
  vue: { label: 'VUE', color: '#41b883' },
  svelte: { label: 'SVELTE', color: '#ff3e00' },
  graphql: { label: 'GQL', color: '#e10098' },
  proto: { label: 'PROTO', color: '#5c9bd1' },
  txt: { label: 'TXT', color: '#6d6d6d' },
  csv: { label: 'CSV', color: '#6d6d6d' },
};

// Common extension-less filenames, matched case-insensitively on the whole filename (never on a
// partial match) — a dotfile like `.gitignore` is looked up on its name without the leading dot.
const FILENAME_BADGES: Record<string, FileTypeBadge> = {
  dockerfile: { label: 'DOCKER', color: '#2496ed' },
  makefile: { label: 'MAKE', color: '#6d6d6d' },
  gitignore: { label: 'GIT', color: '#f34f29' },
  gitattributes: { label: 'GIT', color: '#f34f29' },
  dockerignore: { label: 'DOCKER', color: '#2496ed' },
  env: { label: 'ENV', color: '#6d6d6d' },
  editorconfig: { label: 'EDITOR', color: '#6d6d6d' },
};

const NEUTRAL_BADGE_COLOR = '#8a8f98';
// A generic fallback badge for a real but unmapped extension still names it (uppercased, capped
// so a long one — e.g. a stray `.tsbuildinfo` — never blows out the row) rather than showing
// nothing; only a file with literally no name-derived signal at all falls back to "FILE".
const MAX_FALLBACK_LABEL_LENGTH = 6;

const fileNameOf = (path: string): string => {
  const lastSlashIndex = path.lastIndexOf('/');
  return lastSlashIndex === -1 ? path : path.slice(lastSlashIndex + 1);
};

// The last `.`-delimited segment, or null for a name with no real extension — a leading dot alone
// (`.gitignore`) does not count as one, so that file falls through to FILENAME_BADGES instead of
// badging itself "GITIGNORE" as if that were a file type.
const extensionOf = (fileName: string): string | null => {
  const lastDotIndex = fileName.lastIndexOf('.');
  if (lastDotIndex <= 0) {
    return null;
  }
  return fileName.slice(lastDotIndex + 1).toLowerCase();
};

/**
 * The colored language badge for a file path shown in the Analyze Trace dialog's Files section.
 * Read off the extension first, then a handful of common extension-less filenames
 * (Dockerfile, Makefile, .gitignore, ...), falling back to a neutral badge — never a blank one —
 * so every file gets a consistent leading marker whether or not its type is one this module knows.
 */
export const fileTypeBadge = (path: string): FileTypeBadge => {
  const fileName = fileNameOf(path);
  const extension = extensionOf(fileName);
  if (extension && EXTENSION_BADGES[extension]) {
    return EXTENSION_BADGES[extension];
  }
  const lowerCaseFileName = fileName.toLowerCase();
  const bareFileName = lowerCaseFileName.startsWith('.') ? lowerCaseFileName.slice(1) : lowerCaseFileName;
  if (FILENAME_BADGES[bareFileName]) {
    return FILENAME_BADGES[bareFileName];
  }
  if (extension) {
    return { label: extension.slice(0, MAX_FALLBACK_LABEL_LENGTH).toUpperCase(), color: NEUTRAL_BADGE_COLOR };
  }
  return { label: 'FILE', color: NEUTRAL_BADGE_COLOR };
};
