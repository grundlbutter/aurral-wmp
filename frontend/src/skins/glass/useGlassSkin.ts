import { useSyncExternalStore } from "react";
import { subscribeToTheme } from "../../utils/theme.ts";
import { GLASS_THEME } from "./theme.ts";

const isGlassPainted = () => document.documentElement.dataset.themeId === GLASS_THEME.id;

export function useGlassSkin() {
  return useSyncExternalStore(subscribeToTheme, isGlassPainted);
}
