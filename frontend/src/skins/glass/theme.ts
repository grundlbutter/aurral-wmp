export const GLASS_THEME = {
  id: "glass",
  name: "Glass",
  light: {
    background: "#ffffff",
    text: "#1e1e1e",
    accent: "#1f5fb4",
    info: "#1f5fb4",
  },
  dark: {
    background: "#0a111d",
    text: "#e3ebf6",
    accent: "#4a8ad6",
    info: "#6fb6ff",
    success: "#4fc38a",
  },
} as const;

let styles: Promise<unknown> | null = null;

export function loadGlassSkin() {
  styles ||= import("./glass.css").catch(() => {
    styles = null;
  });
  return styles;
}
