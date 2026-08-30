import type { CSSProperties } from 'react';

// Thin line-art nav icons matching the Aurora mockup (stroke, not filled),
// so the sidebar reads like the design instead of MUI's heavier filled glyphs.
// Each renders at 20px with `currentColor`, inheriting the nav item's color.

export interface NavIconProps {
  size?: number;
}

const base = (size: number): CSSProperties => ({
  width: size,
  height: size,
  display: 'block',
});

const svgProps = (size: number) => ({
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.8,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  style: base(size),
});

export const ToolUsageIcon = ({ size = 20 }: NavIconProps) => (
  <svg {...svgProps(size)}>
    <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />
  </svg>
);

export const TokenUsageIcon = ({ size = 20 }: NavIconProps) => (
  <svg {...svgProps(size)}>
    <circle cx="12" cy="12" r="8" />
    <path d="M12 6v12M9 9h6M9 15h6" />
  </svg>
);

export const SessionsIcon = ({ size = 20 }: NavIconProps) => (
  <svg {...svgProps(size)}>
    <rect x="3" y="4" width="6" height="5" rx="1" />
    <rect x="15" y="15" width="6" height="5" rx="1" />
    <path d="M6 9v4h12v2" />
  </svg>
);

export const TracesIcon = ({ size = 20 }: NavIconProps) => (
  <svg {...svgProps(size)}>
    <rect x="3" y="5" width="10" height="3" rx="1" />
    <rect x="3" y="11" width="16" height="3" rx="1" />
    <rect x="3" y="17" width="7" height="3" rx="1" />
  </svg>
);

export const LogsIcon = ({ size = 20 }: NavIconProps) => (
  <svg {...svgProps(size)}>
    <circle cx="4" cy="6" r="1" fill="currentColor" />
    <path d="M8 6h12" />
    <circle cx="4" cy="12" r="1" fill="currentColor" />
    <path d="M8 12h12" />
    <circle cx="4" cy="18" r="1" fill="currentColor" />
    <path d="M8 18h8" />
  </svg>
);

export const TrendReportIcon = ({ size = 20 }: NavIconProps) => (
  <svg {...svgProps(size)}>
    <polyline points="22 7 13.5 15.5 8.5 10.5 2 17" />
    <polyline points="16 7 22 7 22 13" />
  </svg>
);

export const CostIcon = ({ size = 20 }: NavIconProps) => (
  <svg {...svgProps(size)}>
    <line x1="12" y1="1" x2="12" y2="23" />
    <path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6" />
  </svg>
);

export const MetricsIcon = ({ size = 20 }: NavIconProps) => (
  <svg {...svgProps(size)}>
    <path d="M5 20V9M12 20V4M19 20v-7" />
  </svg>
);

export const ReportIcon = ({ size = 20 }: NavIconProps) => (
  <svg {...svgProps(size)}>
    <rect x="5" y="3" width="14" height="18" rx="2" />
    <path d="M9 8h6M9 12h6M9 16h3" />
  </svg>
);

export const SettingsIcon = ({ size = 20 }: NavIconProps) => (
  <svg {...svgProps(size)}>
    <circle cx="12" cy="12" r="3" />
    <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.6a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
  </svg>
);
