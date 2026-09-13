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
import {
  Alert,
  alpha,
  Autocomplete,
  Box,
  FormControlLabel,
  InputAdornment,
  Paper,
  Skeleton,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import type { Theme } from '@mui/material/styles';
import GhostButton from '../../../../components/GhostButton';
import { severity } from '../../../../theme/colors';
import { fontFamilies } from '../../../../theme/typography';
import type { OllamaModel, OllamaSettings } from '../../settingsTypes';

/**
 * A field's label as its own line above the box (design-handoff `.fl` style), rather than MUI's
 * default notched label cut into the border — the handoff never shows a notch, so `TextField`'s
 * built-in `label` prop is deliberately left unset on both the Port and Model fields below, and
 * this renders the visible label instead. Each field still carries its own `aria-label` (`'Port'`
 * / `'Model'`) via `slotProps.htmlInput`, so the accessible name — and `getByLabelText` in tests —
 * work the same as they would with a real MUI `label`.
 */
const FieldLabel = ({ children }: { children: string }) => (
  <Typography
    sx={{
      fontFamily: fontFamilies.display,
      fontSize: 12,
      fontWeight: 600,
      color: 'text.secondary',
      mb: 0.5,
    }}
  >
    {children}
  </Typography>
);

/** Shared mono styling for both fields' typed value, matching the design handoff's `.oinput input`. */
const MONO_INPUT_STYLE = { fontFamily: fontFamilies.mono, fontSize: 13.5 };
/** The Port field's value is bold on top of the shared mono style, per the design handoff. */
const PORT_INPUT_STYLE = { ...MONO_INPUT_STYLE, fontWeight: 700 };

/**
 * A step lighter than `text.secondary` ("muted"), matching the style guide's separate `--dim`
 * token (`#938cae` vs. `--muted`'s `#6c6589`) — used for the Port field's `http://localhost:`
 * prefix and, on the Model dropdown, a non-large model's parameter-size annotation. Derived from
 * `text.secondary` rather than a hardcoded hex so it still adapts to dark mode, which the style
 * guide (a light-mode-only reference) has no token for.
 */
const dimTextColor = (theme: Theme) => alpha(theme.palette.text.secondary, 0.7);

/**
 * Port/Model field chrome, matching the style guide's `.oinput`/`.ocombo-input` exactly: 44px
 * tall, 11px radius, and a border that stays on `divider` at rest AND on hover (MUI's own
 * default hover/rest border colors are hardcoded, not palette-aware, so both are pinned here) —
 * only focus gets the violet ring (`--selected-ring` border + a soft `0 0 0 3px` glow), matching
 * `.oinput:focus-within` / `.ocombo-input:focus`. Applied via `slotProps.input.sx` (the
 * `OutlinedInput` root), not the component's own top-level `sx` (which instead only carries the
 * per-field disabled opacity, see `enabled` below).
 */
const OLLAMA_FIELD_SX = {
  height: 44,
  borderRadius: '11px',
  '& .MuiOutlinedInput-notchedOutline': { borderColor: 'divider' },
  '&:hover .MuiOutlinedInput-notchedOutline': { borderColor: 'divider' },
  '&.Mui-focused .MuiOutlinedInput-notchedOutline': {
    borderColor: (theme: Theme) => alpha(theme.palette.primary.main, 0.32),
  },
  '&.Mui-focused': {
    boxShadow: (theme: Theme) => `0 0 0 3px ${alpha(theme.palette.primary.main, 0.12)}`,
  },
} as const;

/** The Model dropdown menu's Paper: 12px radius (the app's card-radius family) + the standard card shadow. */
const MODEL_DROPDOWN_PAPER_SX = {
  borderRadius: '12px',
  boxShadow: (theme: Theme) => theme.custom.cardShadow,
} as const;

/** Each dropdown row: 9px/11px padding, 8px radius, mono 13px — row hover already matches via `action.hover`. */
const MODEL_DROPDOWN_LISTBOX_SX = {
  p: '6px',
  '& .MuiAutocomplete-option': {
    borderRadius: '8px',
    fontFamily: fontFamilies.mono,
    fontSize: 13,
    padding: '9px 11px',
  },
} as const;

/**
 * Save's `sx` override: the handoff's `.btn` is a full-size action button (40px tall, 11px
 * radius, the standard card shadow, bright `--ink` label at rest that shifts to violet
 * `--primary`/`--selected-ring` only on hover) rather than `GhostButton`'s default small, muted
 * toolbar/pager sizing — used here and on `OllamaStatusCard`'s Test connection button, the Ollama
 * tab's other primary action, so the two read as a matched pair.
 */
export const PRIMARY_ACTION_BUTTON_SX = {
  height: 40,
  px: '14px',
  fontSize: 13,
  borderRadius: '11px',
  color: 'text.primary',
  boxShadow: (theme: Theme) => theme.custom.cardShadow,
  '&:hover': {
    color: 'primary.main',
    borderColor: (theme: Theme) => alpha(theme.palette.primary.main, 0.32),
  },
} as const;

/**
 * A model at or above this size is flagged with a warning rather than
 * filtered out of the dropdown — an operator who deliberately pulled a large
 * model can still select and save it; the UI just says so may be slow on
 * typical local hardware.
 */
const LARGE_MODEL_PARAMETER_COUNT_BILLIONS_THRESHOLD = 13;

/**
 * Ollama always runs on the operator's own machine, so the host is hardcoded rather than
 * editable — sending telemetry-adjacent connection details to an arbitrary, user-typed host would
 * defeat the point of keeping "Analyze trace" local. The form only lets the operator pick the
 * port; `OllamaClient` appends its own fixed `/api/generate` / `/api/tags` paths, so `baseUrl`
 * never carries a path segment and none is exposed here.
 */
const LOCALHOST_BASE_URL_PREFIX = 'http://localhost:';
const LOCALHOST_BASE_URL_PATTERN = /^http:\/\/localhost:(\d+)$/;

const portFromBaseUrl = (baseUrl: string): string => baseUrl.match(LOCALHOST_BASE_URL_PATTERN)?.[1] ?? '';

export interface OllamaConfigurationCardProps {
  ollamaSettings: OllamaSettings | null;
  isOllamaSettingsLoading: boolean;
  baseUrl: string;
  model: string;
  enabled: boolean;
  onBaseUrlChange: (baseUrl: string) => void;
  onModelChange: (model: string) => void;
  onEnabledChange: (enabled: boolean) => void;
  onSave: () => void;
  isSaving: boolean;
  saveError: Error | null;
  isSaved: boolean;
  ollamaModels: OllamaModel[];
  isOllamaModelsLoading: boolean;
}

/**
 * Editable Ollama connection: the Enabled/Disabled toggle for the whole feature, plus the port /
 * model form. The host is hardcoded to `localhost` (see the module-level comment above) — the
 * form only exposes a port field, and reassembles it into the full `baseUrl` the container's
 * state and the save mutation expect. The reachability probe ("Test connection") and the
 * Overridden/Using default chip live in the sibling `OllamaStatusCard`, not here — see this
 * page's CLAUDE.md for why the two were split.
 *
 * Follows this page's `PurgeDryRunCard` as the closest precedent for an
 * editable-form-with-action section: the form values and every async result
 * live in the container (`SettingsPage.tsx`), this component is pure props in
 * / JSX out, same as `PurgeDryRunCard` itself (only its own ephemeral
 * disclosure state — none needed here — would live locally).
 *
 * Saving persists a DB override (effective config = override if set, else the
 * `ollama.*` application.yml default); blanking baseUrl/model back out and
 * saving clears that field's override, while `enabled` — a toggle has no
 * "blank" state — always saves as an explicit true/false. A successful save
 * shows a "Settings saved." banner (`isSaved`, the mutation's own
 * `isSuccess`) that clears itself the moment any field is edited again — same
 * `.reset()`-on-change idiom the container already applies to the
 * test-connection result on the status card, so a stale confirmation never
 * sits under freshly typed, unsaved values.
 *
 * When `enabled` is false, the port and model fields dim and disable (nothing
 * to edit or test while the feature is off — the status card handles its own
 * share of that gating via its own `enabled` prop for Test connection). Save
 * deliberately stays enabled: it's the only way to persist turning the toggle
 * off in the first place, since flipping it to Disabled must itself be
 * saveable — disabling Save whenever `!enabled` would make an operator's
 * "turn this off" edit impossible to commit. The client-side field dimming is
 * a convenience, not the enforcement either way:
 * `TraceAnalysisService.regenerate` re-checks the effective `enabled` flag
 * server-side before calling Ollama, so a stale tab or a client that skipped
 * this dimming still cannot trigger an analysis while the feature is off.
 *
 * The Model field is a `freeSolo` `Autocomplete`, not a plain `TextField`:
 * `ollamaModels` (fetched automatically by the container, keyed on the live
 * base URL, independent of "Test connection") populates the dropdown with
 * models actually installed on the target Ollama instance, but the operator
 * can still type any name — e.g. one they're about to `ollama pull` — since
 * a model that isn't installed yet is a perfectly valid, if not-yet-usable,
 * value to save. An empty or failed fetch just means an empty options list;
 * the field keeps working as free text either way.
 *
 * Each fetched model also carries a parameter size. `renderOption` renders it as a second,
 * separately-colored span next to the name — dim by default, the style guide's warn color
 * (`severity.warning`, matching `--warn`/`#e6952b`) for a model at or above the large-model
 * threshold, a visual cue in the dropdown itself before the warning banner below the field even
 * appears — purely for display: `getOptionLabel` still resolves to the bare name, so picking an
 * option (or typing free text) sets `inputValue`/calls `onModelChange` with just the model name,
 * never the size string; the saved `model` field must stay something Ollama itself would
 * recognize. Large models are warned about, not filtered — every fetched model stays selectable —
 * because an operator who deliberately pulled a large model still needs to be able to pick it; a
 * model that isn't in the fetched list at all (free-typed, not yet pulled, or the list failed to
 * load) shows no warning since there's nothing to check its size against.
 */
const OllamaConfigurationCard = ({
  ollamaSettings,
  isOllamaSettingsLoading,
  baseUrl,
  model,
  enabled,
  onBaseUrlChange,
  onModelChange,
  onEnabledChange,
  onSave,
  isSaving,
  saveError,
  isSaved,
  ollamaModels,
  isOllamaModelsLoading,
}: OllamaConfigurationCardProps) => {
  const port = portFromBaseUrl(baseUrl);
  const handlePortChange = (nextPort: string) => {
    const digitsOnlyPort = nextPort.replace(/\D/g, '');
    onBaseUrlChange(digitsOnlyPort === '' ? '' : `${LOCALHOST_BASE_URL_PREFIX}${digitsOnlyPort}`);
  };

  // Only warns when the currently typed/selected model matches a fetched
  // entry by name — a free-typed model not in the list has nothing to check
  // its size against, so it stays silent rather than warning on every
  // keystroke.
  const currentOllamaModel = ollamaModels.find((ollamaModel) => ollamaModel.name === model);
  const isCurrentModelLarge =
    currentOllamaModel?.parameterCountBillions != null &&
    currentOllamaModel.parameterCountBillions >= LARGE_MODEL_PARAMETER_COUNT_BILLIONS_THRESHOLD;

  if (isOllamaSettingsLoading && !ollamaSettings) {
    return (
      <Paper variant="outlined" sx={{ p: '22px 24px' }}>
        <Skeleton variant="text" width="70%" sx={{ mb: 2 }} />
        <Skeleton variant="rounded" height={40} sx={{ mb: 1.5 }} />
        <Skeleton variant="rounded" height={40} />
      </Paper>
    );
  }

  return (
    <Paper variant="outlined" sx={{ p: '22px 24px' }}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.5}
        sx={{ alignItems: { sm: 'center' }, justifyContent: 'space-between', mb: 1 }}
      >
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
          Ollama connection
        </Typography>
        <FormControlLabel
          labelPlacement="start"
          control={
            <Switch
              checked={enabled}
              onChange={(event) => onEnabledChange(event.target.checked)}
              slotProps={{ input: { 'aria-label': 'Ollama enabled' } }}
            />
          }
          label={enabled ? 'Enabled' : 'Disabled'}
          sx={{ ml: 0 }}
        />
      </Stack>

      <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.5, mb: 2 }}>
        Where "Analyze trace" sends spans/logs for a local Ollama read. Leave a
        field blank and save to fall back to the{' '}
        <Typography component="span" sx={{ typography: 'mono', fontSize: 12.5 }}>
          ollama.*
        </Typography>{' '}
        default in application.yml. Ollama always runs on the operator's own
        machine, so only the port is editable here — the host is fixed to
        localhost.
      </Typography>

      <Stack spacing={1.5} sx={{ maxWidth: 480 }}>
        <Box>
          <FieldLabel>Port</FieldLabel>
          <TextField
            value={port}
            onChange={(event) => handlePortChange(event.target.value)}
            placeholder="11434"
            size="small"
            fullWidth
            disabled={!enabled}
            inputMode="numeric"
            sx={{ opacity: enabled ? 1 : 0.45 }}
            slotProps={{
              htmlInput: { 'aria-label': 'Port', style: PORT_INPUT_STYLE },
              input: {
                sx: OLLAMA_FIELD_SX,
                startAdornment: (
                  <InputAdornment position="start" sx={{ ...MONO_INPUT_STYLE, color: dimTextColor }}>
                    {LOCALHOST_BASE_URL_PREFIX}
                  </InputAdornment>
                ),
              },
            }}
          />
        </Box>
        <Box>
          <FieldLabel>Model</FieldLabel>
          <Autocomplete
            freeSolo
            size="small"
            fullWidth
            disabled={!enabled}
            loading={isOllamaModelsLoading}
            options={ollamaModels}
            sx={{ opacity: enabled ? 1 : 0.45 }}
            slotProps={{
              paper: { sx: MODEL_DROPDOWN_PAPER_SX },
              listbox: { sx: MODEL_DROPDOWN_LISTBOX_SX },
            }}
            getOptionLabel={(option) => (typeof option === 'string' ? option : option.name)}
            renderOption={(props, option) => {
              const isLargeModelOption =
                option.parameterCountBillions != null &&
                option.parameterCountBillions >= LARGE_MODEL_PARAMETER_COUNT_BILLIONS_THRESHOLD;
              return (
                <li {...props} key={option.name}>
                  <Box component="span">{option.name}</Box>
                  {option.parameterSize && (
                    <Box
                      component="span"
                      sx={{
                        ml: 1,
                        fontSize: 11.5,
                        color: isLargeModelOption ? severity.warning : dimTextColor,
                      }}
                    >
                      {option.parameterSize}
                    </Box>
                  )}
                </li>
              );
            }}
            inputValue={model}
            onInputChange={(_event, newInputValue) => onModelChange(newInputValue)}
            renderInput={(params) => (
              <TextField
                {...params}
                placeholder="llama3.1"
                slotProps={{
                  ...params.slotProps,
                  htmlInput: {
                    ...params.slotProps.htmlInput,
                    'aria-label': 'Model',
                    style: MONO_INPUT_STYLE,
                  },
                  input: {
                    ...params.slotProps.input,
                    sx: OLLAMA_FIELD_SX,
                  },
                }}
              />
            )}
          />
        </Box>
      </Stack>

      {enabled && isCurrentModelLarge && currentOllamaModel && (
        <Alert severity="warning" sx={{ mt: 1.5, maxWidth: 480 }}>
          This is a {currentOllamaModel.parameterSize ?? `${currentOllamaModel.parameterCountBillions}B`}{' '}
          model — local inference may be slow and memory-intensive on typical hardware.
        </Alert>
      )}

      <Stack direction="row" sx={{ mt: 2 }}>
        <GhostButton onClick={onSave} disabled={isSaving} sx={PRIMARY_ACTION_BUTTON_SX}>
          {isSaving ? 'Saving…' : 'Save'}
        </GhostButton>
      </Stack>

      {saveError && (
        <Typography variant="body2" sx={{ mt: 1.5, color: 'error.main' }}>
          {saveError.message}
        </Typography>
      )}

      {isSaved && !saveError && (
        <Alert severity="success" sx={{ mt: 1.5, maxWidth: 480 }}>
          Settings saved.
        </Alert>
      )}
    </Paper>
  );
};

export default OllamaConfigurationCard;
