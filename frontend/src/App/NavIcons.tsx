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
    <path d="M12 8v8M9.5 14a2.5 2 0 0 0 5 0c0-2.5-5-1.5-5-4a2.5 2 0 0 1 5 0" />
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
    <path d="M3 17l5-6 4 3 6-8" />
    <circle cx="18" cy="6" r="1.4" fill="currentColor" stroke="none" />
  </svg>
);

export const LogsIcon = ({ size = 20 }: NavIconProps) => (
  <svg {...svgProps(size)}>
    <path d="M4 6h12M4 12h16M4 18h10" />
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
