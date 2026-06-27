import { Box } from '@mui/material';

export interface TraceTailToggleProps {
  active: boolean;
  locked: boolean;
  tooltip?: string;
  onToggle: () => void;
}

const TraceTailToggle = ({
  active,
  locked,
  tooltip,
  onToggle,
}: TraceTailToggleProps) => {
  return (
    <Box
      component="button"
      title={tooltip}
      onClick={() => {
        if (!locked) {
          onToggle();
        }
      }}
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1,
        height: 40,
        px: 1.75,
        borderRadius: 1.5,
        cursor: locked ? 'not-allowed' : 'pointer',
        opacity: locked ? 0.4 : 1,
        fontFamily: "'Sora', sans-serif",
        fontSize: 13,
        fontWeight: 600,
        border: active ? 'none' : 1,
        borderColor: 'divider',
        color: active ? 'common.white' : 'text.secondary',
        background: active
          ? 'linear-gradient(135deg, #1f9d6b, #13b6e6)'
          : (t) => t.palette.background.paper,
        boxShadow: active ? '0 6px 16px rgba(31,157,107,0.4)' : 'none',
        '&:hover': { color: active ? 'common.white' : 'text.primary' },
      }}
    >
      <Box
        sx={{
          width: 9,
          height: 9,
          borderRadius: '50%',
          bgcolor: active ? 'common.white' : 'text.disabled',
          animation: active ? 'tailPulse 1.1s infinite' : 'none',
          '@keyframes tailPulse': {
            '0%,100%': { opacity: 1, transform: 'scale(1)' },
            '50%': { opacity: 0.35, transform: 'scale(0.8)' },
          },
        }}
      />
      {active ? 'LIVE' : 'Live tail'}
    </Box>
  );
};

export default TraceTailToggle;
