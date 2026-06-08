import { Autocomplete, Chip, TextField } from '@mui/material';

export interface LogsFilterBarProps {
  selectedFilters: string[];
  onSelectedFiltersChange: (filters: string[]) => void;
  autocompleteOptions: string[];
  autocompleteInput: string;
  onAutocompleteInputChange: (next: string) => void;
}

const LogsFilterBar = ({
  selectedFilters,
  onSelectedFiltersChange,
  autocompleteOptions,
  autocompleteInput,
  onAutocompleteInputChange,
}: LogsFilterBarProps) => {
  return (
    <Autocomplete
      multiple
      freeSolo
      size="small"
      options={autocompleteOptions}
      value={selectedFilters}
      inputValue={autocompleteInput}
      onInputChange={(_event, next, reason) => {
        if (reason !== 'reset') {
          onAutocompleteInputChange(next);
        }
      }}
      filterOptions={(options, state) => {
        const needle = state.inputValue.toLowerCase();
        return options.filter((option) =>
          option.toLowerCase().includes(needle),
        );
      }}
      onChange={(_event, newValue) => {
        // A key suggestion (ends with `=`) shouldn't commit as a filter — keep
        // it in the input so the user can type a value.
        const added = newValue.find(
          (entry) => !selectedFilters.includes(entry),
        );
        if (typeof added === 'string' && added.endsWith('=')) {
          onAutocompleteInputChange(added);
          return;
        }
        onSelectedFiltersChange(newValue);
        onAutocompleteInputChange('');
      }}
      disableCloseOnSelect
      fullWidth
      renderValue={(value, getItemProps) =>
        value.map((option, index) => {
          const itemProps = getItemProps({ index });
          return (
            <Chip
              {...itemProps}
              key={option}
              label={option}
              size="small"
              sx={{ bgcolor: 'action.selected', color: 'primary.main' }}
            />
          );
        })
      }
      renderInput={(params) => (
        <TextField
          {...params}
          placeholder={
            selectedFilters.length === 0
              ? 'Filter attributes (e.g. event.name=tool_result, tool_name=Read)'
              : ''
          }
        />
      )}
    />
  );
};

export default LogsFilterBar;
