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
// Self-hosted Aurora fonts (bundled via @fontsource, served from your own app —
// no Google Fonts CDN dependency, so they load even offline / behind a CSP).
//
// Requires these deps (add to package.json, then `npm install`):
//   "@fontsource/sora": "^5.2.5",
//   "@fontsource/space-grotesk": "^5.2.5"
//
// Import this file once, at the top of main.tsx: `import './fonts';`
//
// Weights here MUST cover every weight the theme uses:
//   Sora           → 400/500/600/700/800  (h4 uses 800)
//   Space Grotesk  → 400/500/600/700

import '@fontsource/sora/400.css';
import '@fontsource/sora/500.css';
import '@fontsource/sora/600.css';
import '@fontsource/sora/700.css';
import '@fontsource/sora/800.css';

import '@fontsource/space-grotesk/400.css';
import '@fontsource/space-grotesk/500.css';
import '@fontsource/space-grotesk/600.css';
import '@fontsource/space-grotesk/700.css';
