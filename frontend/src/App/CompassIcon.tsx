import SvgIcon from '@mui/material/SvgIcon';
import type { SvgIconProps } from '@mui/material/SvgIcon';

// A compass drawn as a circuit board: the bezel is a trace ring, the needle is a
// thick trace ending in an arrow, and PCB-style dog-leg traces run out to vias in
// each diagonal quadrant. Everything is currentColor so it inherits the theme.

// Diagonal traces: 45° out of the pivot, then Manhattan bends to a via pad.
const TRACES: ReadonlyArray<{ d: string; pad: readonly [number, number] }> = [
  { d: 'M24 24 L30 18 L36 18 L36 12', pad: [36, 12] },
  { d: 'M24 24 L18 18 L12 18 L12 12', pad: [12, 12] },
  { d: 'M24 24 L30 30 L36 30 L36 36', pad: [36, 36] },
  { d: 'M24 24 L18 30 L12 30 L12 36', pad: [12, 36] },
];

// Cardinal via pads sitting on the bezel (north is the arrow, so it's omitted).
const CARDINAL_VIAS: ReadonlyArray<readonly [number, number]> = [
  [44, 24],
  [24, 44],
  [4, 24],
];

const Via = ({ cx, cy, r = 2.6, opacity = 0.7 }: { cx: number; cy: number; r?: number; opacity?: number }) => (
  <g opacity={opacity}>
    <circle cx={cx} cy={cy} r={r} fill="none" stroke="currentColor" strokeWidth="1.4" />
    <circle cx={cx} cy={cy} r={r * 0.36} fill="currentColor" />
  </g>
);

const CompassIcon = (props: SvgIconProps) => (
  <SvgIcon
    viewBox="0 0 48 48"
    fill="none"
    stroke="currentColor"
    strokeLinecap="round"
    strokeLinejoin="round"
    {...props}
  >
    {/* Bezel trace ring */}
    <circle cx="24" cy="24" r="20" fill="none" strokeWidth="1.6" opacity="0.85" />

    {/* Cardinal vias on the bezel */}
    {CARDINAL_VIAS.map(([cx, cy]) => (
      <Via key={`${cx}-${cy}`} cx={cx} cy={cy} />
    ))}

    {/* Diagonal PCB traces + end-of-trace via pads */}
    <g fill="none" opacity="0.6">
      {TRACES.map((trace) => (
        <path key={trace.d} d={trace.d} strokeWidth="1.6" />
      ))}
    </g>
    {TRACES.map((trace) => (
      <circle
        key={`pad-${trace.pad[0]}-${trace.pad[1]}`}
        cx={trace.pad[0]}
        cy={trace.pad[1]}
        r="1.9"
        fill="currentColor"
        stroke="none"
        opacity="0.8"
      />
    ))}

    {/* South tail — thinner, faded trace with a via at the end */}
    <path d="M24 24 L24 40" fill="none" strokeWidth="2" opacity="0.5" />
    <circle cx="24" cy="40" r="1.7" fill="currentColor" stroke="none" opacity="0.5" />

    {/* North needle — thick trace shaft + solid arrowhead touching the bezel */}
    <path d="M24 24 L24 10" fill="none" strokeWidth="3.2" />
    <polygon points="24,3 19,11 29,11" fill="currentColor" stroke="none" />

    {/* Center pivot via, drawn last so converging traces read as one junction */}
    <Via cx={24} cy={24} r={3.4} opacity={1} />
  </SvgIcon>
);

export default CompassIcon;
