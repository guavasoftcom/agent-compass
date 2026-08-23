import { useRef } from 'react';
import type { KeyboardEvent, ReactNode } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { ButtonBase, Stack } from '@mui/material';
import { gradients } from '../../theme/colors';
import { fontFamilies } from '../../theme/typography';

export interface PillTabItem<TValue> {
  value: TValue;
  label: ReactNode;
  /**
   * When set the tab navigates instead of toggling in place, rendering as a
   * router link to this path. Routed sections (`SectionLayout`) use this form;
   * in-page tab strips leave it undefined and handle `onChange`.
   */
  to?: string;
}

export interface PillTabsProps<TValue> {
  tabs: readonly PillTabItem<TValue>[];
  activeValue: TValue;
  onChange?: (value: TValue) => void;
  /** Accessible name for the tab strip, e.g. "Token usage views". */
  ariaLabel?: string;
}

/**
 * The Aurora underline tab strip: an unfilled track with a thin (2px)
 * violet/pink gradient underline beneath the active label only, muted
 * inactive labels that darken to full contrast on hover. Shared by the
 * routed section tabs and the in-page tab strips so a tab reads identically
 * whether or not it changes the URL.
 *
 * Implements the WAI-ARIA tabs pattern's roving-tabindex keyboard behavior
 * (Left/Right arrow moves focus between tabs; only the active tab is in the
 * default tab order) to go with the `role="tablist"`/`role="tab"` markup
 * below — claiming those roles without it would leave a keyboard user unable
 * to navigate the strip the way the role promises.
 */
const PillTabs = <TValue,>({ tabs, activeValue, onChange, ariaLabel }: PillTabsProps<TValue>) => {
  const listRef = useRef<HTMLDivElement>(null);

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') {
      return;
    }
    const tabElements = Array.from(
      listRef.current?.querySelectorAll<HTMLElement>('[role="tab"]') ?? [],
    );
    const currentIndex = tabElements.indexOf(document.activeElement as HTMLElement);
    if (currentIndex === -1 || tabElements.length === 0) {
      return;
    }
    event.preventDefault();
    const delta = event.key === 'ArrowRight' ? 1 : -1;
    const nextIndex = (currentIndex + delta + tabElements.length) % tabElements.length;
    tabElements[nextIndex]?.focus();
  };

  return (
    <Stack
      ref={listRef}
      direction="row"
      spacing={3}
      useFlexGap
      role="tablist"
      aria-label={ariaLabel}
      onKeyDown={handleKeyDown}
      sx={{ flexWrap: 'wrap', borderBottom: 1, borderColor: 'divider', mb: 2 }}
    >
      {tabs.map((tab) => {
        const selected = tab.value === activeValue;
        return (
          <ButtonBase
            key={String(tab.value)}
            role="tab"
            aria-selected={selected}
            tabIndex={selected ? 0 : -1}
            focusRipple
            component={tab.to === undefined ? 'button' : RouterLink}
            to={tab.to}
            onClick={tab.to === undefined ? () => onChange?.(tab.value) : undefined}
            sx={(theme) => ({
              position: 'relative',
              py: 1.5,
              px: 0,
              fontFamily: fontFamilies.display,
              fontSize: 14,
              fontWeight: 600,
              lineHeight: 1,
              letterSpacing: 0.1,
              color: selected ? 'primary.main' : 'text.secondary',
              transition: theme.transitions.create('color'),
              '&:hover': {
                color: selected ? 'primary.main' : 'text.primary',
              },
              '&::after': {
                content: '""',
                position: 'absolute',
                left: 0,
                right: 0,
                bottom: -1,
                height: 2,
                borderRadius: 1,
                backgroundImage: selected ? gradients.auroraAction : 'none',
              },
            })}
          >
            {tab.label}
          </ButtonBase>
        );
      })}
    </Stack>
  );
};

export default PillTabs;
