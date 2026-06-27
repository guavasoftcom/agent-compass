import { TracesExplorerProvider } from './TracesExplorerContext';
import TracesPageView from './TracesPageView';

export default function TracesPage() {
  return (
    <TracesExplorerProvider>
      <TracesPageView />
    </TracesExplorerProvider>
  );
}
