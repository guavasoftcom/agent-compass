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
import { describe, expect, it } from 'vitest';
import { fileTypeBadge } from './fileTypeBadge';

describe('fileTypeBadge', () => {
  it('badges a Java file distinctly from a TypeScript one', () => {
    expect(fileTypeBadge('src/main/java/com/example/TraceService.java')).toEqual({
      label: 'JAVA',
      color: '#e76f00',
    });
    expect(fileTypeBadge('src/pages/TraceDetailPage/TraceDetailPage.tsx')).toEqual({
      label: 'TSX',
      color: '#3178c6',
    });
  });

  it('reads the extension off only the filename, not any directory segment containing a dot', () => {
    expect(fileTypeBadge('a.java/config/settings.yml').label).toBe('YML');
  });

  it('matches extensions case-insensitively', () => {
    expect(fileTypeBadge('Notes.MD').label).toBe('MD');
  });

  it('badges a common extension-less filename by name rather than falling back generically', () => {
    expect(fileTypeBadge('backend/Dockerfile')).toEqual({ label: 'DOCKER', color: '#2496ed' });
    expect(fileTypeBadge('.gitignore')).toEqual({ label: 'GIT', color: '#f34f29' });
  });

  it('does not mistake a leading-dot-only filename for having a real extension', () => {
    // ".gitignore" has no `.`-delimited extension of its own — it's matched by filename above,
    // not badged "GITIGNORE" as if that were a file type.
    expect(fileTypeBadge('.env').label).toBe('ENV');
  });

  it('falls back to a neutral badge naming the extension when the extension is unmapped', () => {
    expect(fileTypeBadge('archive.tar.gz')).toEqual({ label: 'GZ', color: '#8a8f98' });
  });

  it('falls back to a plain FILE badge for a name with no extension and no known filename match', () => {
    expect(fileTypeBadge('LICENSE')).toEqual({ label: 'FILE', color: '#8a8f98' });
  });
});
