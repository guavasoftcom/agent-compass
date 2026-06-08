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
