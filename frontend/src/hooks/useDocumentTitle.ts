import { useEffect, useSyncExternalStore } from "react";

const DEFAULT_TITLE = "Aurral";

let pageTitle = "";
let nowPlayingTitle = "";
const pageTitleListeners = new Set<() => void>();

function applyTitle() {
  document.title = nowPlayingTitle || (pageTitle ? `${pageTitle} - Aurral` : DEFAULT_TITLE);
  for (const listener of pageTitleListeners) listener();
}

function subscribeToPageTitle(listener: () => void) {
  pageTitleListeners.add(listener);
  return () => pageTitleListeners.delete(listener);
}

export function usePageTitle() {
  return useSyncExternalStore(subscribeToPageTitle, () => pageTitle);
}

export function useDocumentTitle(title: string | null | undefined) {
  useEffect(() => {
    pageTitle = title?.trim() || "";
    applyTitle();
    return () => {
      pageTitle = "";
      applyTitle();
    };
  }, [title]);
}

export function useNowPlayingTitle(title: string | null | undefined) {
  useEffect(() => {
    nowPlayingTitle = title?.trim() || "";
    applyTitle();
    return () => {
      nowPlayingTitle = "";
      applyTitle();
    };
  }, [title]);
}
