import { Box, Typography, useTheme } from '@mui/material';
import CheckIcon from '@mui/icons-material/Check';
import SearchInput from '../SearchInput';
import { fontFamilies } from '../../theme/typography';
import { radii } from '../../theme/theme';

/**
 * A single facet value row: the checkbox, optional color dot, label, and count.
 * Callers pre-compute all display values — color, label, and monospace flag — so
 * this component has no knowledge of domain-specific logic (severity palettes, duration
 * label maps, service color registries, etc.).
 */
export interface FacetRailItem {
  /** The raw backing value used as the React key and for toggle callbacks. */
  value: string;
  /** Display label — may differ from value (e.g. a duration bucket label). */
  label: string;
  /** Facet hit count shown to the right of the label. */
  count: number;
  /** Whether the checkbox is checked. */
  selected: boolean;
  /**
   * Optional color dot shown between the checkbox and the label.
   * Pass a resolved CSS color string (e.g. from a theme palette token or a
   * service-color registry). Omit or pass undefined/null to suppress the dot.
   */
  color?: string | null;
  /**
   * Render the label in the monospace font. Use for identifier-type values
   * (trace IDs, session IDs, operation names) where proportional spacing
   * makes scanning harder.
   */
  monospace?: boolean;
}

/**
 * A single section (group) in the facet rail — a header with a "clear" button
 * and a list of selectable items.
 *
 * The generic `SectionKey` parameter lets callers keep their own key type
 * (e.g. `FacetKey` from the page-local API module) so there is no widening
 * to `string` at the call site.
 */
export interface FacetRailSection<SectionKey extends string = string> {
  /** Unique key for this section — used as the React list key. */
  key: SectionKey;
  /** Section header label (rendered uppercase in small caps). */
  title: string;
  /** Pre-built, pre-ordered item list. */
  items: FacetRailItem[];
  /**
   * Called when the user clicks a row. Receives the `value` of the clicked item.
   * The caller is responsible for toggling selection state and re-passing updated
   * `items` on the next render.
   */
  onToggle: (value: string) => void;
  /**
   * Called when the user clicks the "clear" button in the section header.
   * The "clear" button is only rendered when at least one item in the section
   * is selected (`item.selected === true`).
   */
  onClear: () => void;
}

export interface FacetRailProps {
  sections: FacetRailSection[];
  search: string;
  onSearchChange: (value: string) => void;
  searchPlaceholder?: string;
}

/**
 * Config-driven, presentational facet rail used by LogsPage and TracesPage.
 *
 * This component renders a search box and a list of collapsible facet sections.
 * It knows nothing about specific facet keys, color logic, ordering, or label
 * translation — all of that is pre-applied by the caller when building `sections`.
 */
const FacetRail = ({
  sections,
  search,
  onSearchChange,
  searchPlaceholder = 'Search…',
}: FacetRailProps) => {
  const theme = useTheme();

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', overflowY: 'auto', py: 0.75 }}>
      <SearchInput
        value={search}
        onChange={onSearchChange}
        placeholder={searchPlaceholder}
        sx={{ mx: 1.75, mt: 1.5, mb: 1 }}
      />

      {sections.map((section) => {
        const hasSelection = section.items.some((item) => item.selected);
        return (
          <Box key={section.key} sx={{ px: 0.75, pt: 0.75, pb: 0.5 }}>
            <Box
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                px: 1.25,
                pt: 1.1,
                pb: 0.75,
              }}
            >
              <Typography
                component="span"
                sx={{
                  typography: 'eyebrowSm',
                  color: 'text.secondary',
                }}
              >
                {section.title}
              </Typography>
              {hasSelection ? (
                <Box
                  component="button"
                  onClick={section.onClear}
                  sx={{
                    border: 'none',
                    bgcolor: 'transparent',
                    cursor: 'pointer',
                    fontFamily: fontFamilies.body,
                    fontSize: 11,
                    fontWeight: 600,
                    color: 'primary.main',
                  }}
                >
                  clear
                </Box>
              ) : null}
            </Box>

            {section.items.map((item) => (
              <Box
                key={item.value}
                onClick={() => section.onToggle(item.value)}
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 1.1,
                  mx: 0.5,
                  my: '1px',
                  px: 1.1,
                  py: 0.75,
                  borderRadius: radii.sm,
                  cursor: 'pointer',
                  color: item.selected ? 'text.primary' : 'text.secondary',
                  opacity: item.count ? 1 : 0.4,
                  transition: 'background-color .12s',
                  '&:hover': { bgcolor: 'action.hover', color: 'text.primary' },
                }}
              >
                <Box
                  sx={{
                    width: 15,
                    height: 15,
                    borderRadius: radii.xs,
                    flexShrink: 0,
                    display: 'grid',
                    placeItems: 'center',
                    border: item.selected
                      ? 'none'
                      : `1.5px solid ${theme.palette.text.disabled}`,
                    bgcolor: item.selected ? 'primary.main' : 'transparent',
                  }}
                >
                  {item.selected ? (
                    <CheckIcon sx={{ fontSize: 11, color: 'primary.contrastText' }} />
                  ) : null}
                </Box>

                {item.color ? (
                  <Box
                    sx={{
                      width: 8,
                      height: 8,
                      borderRadius: radii.xs,
                      bgcolor: item.color,
                      flexShrink: 0,
                    }}
                  />
                ) : null}

                <Box
                  component="span"
                  sx={{
                    flex: 1,
                    fontSize: item.monospace ? 12.5 : 13,
                    fontFamily: item.monospace ? fontFamilies.mono : undefined,
                    fontWeight: 500,
                    whiteSpace: 'nowrap',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                  }}
                  title={item.label}
                >
                  {item.label}
                </Box>

                <Box
                  component="span"
                  sx={{
                    fontSize: 12,
                    color: 'text.disabled',
                    fontVariantNumeric: 'tabular-nums',
                    fontWeight: 500,
                  }}
                >
                  {item.count.toLocaleString()}
                </Box>
              </Box>
            ))}
          </Box>
        );
      })}
    </Box>
  );
};

export default FacetRail;
