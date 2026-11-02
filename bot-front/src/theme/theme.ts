import { createTheme, type Theme } from "@mui/material/styles";

type GlassTokens = {
  surface: string;
  surfaceStrong: string;
  surfaceSubtle: string;
  border: string;
  highlight: string;
  shadow: string;
  base: string;
  blobs: [string, string, string, string];
};

declare module "@mui/material/styles" {
  interface Theme {
    glass: GlassTokens;
  }
  interface ThemeOptions {
    glass?: GlassTokens;
  }
}

export const ACCENT_GRADIENT =
  "linear-gradient(135deg, #7c3aed 0%, #a855f7 45%, #ec4899 100%)";
export const FRIEND_GRADIENT =
  "linear-gradient(135deg, #22d3ee 0%, #6366f1 55%, #a855f7 100%)";

const darkGlass: GlassTokens = {
  surface: "rgba(18, 20, 40, 0.48)",
  surfaceStrong: "rgba(30, 33, 62, 0.62)",
  surfaceSubtle: "rgba(255, 255, 255, 0.05)",
  border: "rgba(255, 255, 255, 0.10)",
  highlight: "rgba(255, 255, 255, 0.08)",
  shadow: "0 10px 40px rgba(0, 0, 0, 0.40)",
  base: "#070816",
  blobs: ["#7c3aed", "#0ea5e9", "#ec4899", "#14b8a6"],
};

const lightGlass: GlassTokens = {
  surface: "rgba(255, 255, 255, 0.50)",
  surfaceStrong: "rgba(255, 255, 255, 0.78)",
  surfaceSubtle: "rgba(255, 255, 255, 0.45)",
  border: "rgba(255, 255, 255, 0.75)",
  highlight: "rgba(255, 255, 255, 0.85)",
  shadow: "0 10px 40px rgba(76, 64, 160, 0.14)",
  base: "#eef0fb",
  blobs: ["#c4b5fd", "#7dd3fc", "#f9a8d4", "#99f6e4"],
};

/** Frosted-glass surface: translucent fill, background blur, hairline border. */
export function glassPanel(theme: Theme, strong = false) {
  return {
    background: strong ? theme.glass.surfaceStrong : theme.glass.surface,
    backdropFilter: "blur(24px) saturate(170%)",
    WebkitBackdropFilter: "blur(24px) saturate(170%)",
    border: `1px solid ${theme.glass.border}`,
    boxShadow: `${theme.glass.shadow}, inset 0 1px 0 ${theme.glass.highlight}`,
  };
}

function buildTheme(mode: "dark" | "light") {
  const glass = mode === "dark" ? darkGlass : lightGlass;

  return createTheme({
    glass,
    shape: { borderRadius: 4 },
    typography: {
      fontFamily:
        'Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
      h3: { fontWeight: 800, letterSpacing: -1 },
      h4: { fontWeight: 800, letterSpacing: -0.6 },
      h6: { fontWeight: 700, letterSpacing: -0.3 },
      body2: { fontSize: 14.5, lineHeight: 1.55 },
      caption: { fontSize: 11.5, letterSpacing: 0.2 },
      button: { fontWeight: 600, textTransform: "none" },
    },
    palette:
      mode === "dark"
        ? {
            mode,
            primary: { main: "#a78bfa", light: "#c4b5fd", dark: "#7c3aed", contrastText: "#ffffff" },
            secondary: { main: "#2dd4bf" },
            success: { main: "#34d399" },
            background: { default: glass.base, paper: "#15172e" },
            text: { primary: "#f5f3ff", secondary: "rgba(226, 232, 240, 0.66)" },
            divider: "rgba(255, 255, 255, 0.09)",
          }
        : {
            mode,
            primary: { main: "#7c3aed", light: "#a78bfa", dark: "#5b21b6", contrastText: "#ffffff" },
            secondary: { main: "#0d9488" },
            success: { main: "#10b981" },
            background: { default: glass.base, paper: "#ffffff" },
            text: { primary: "#1e1b4b", secondary: "rgba(30, 27, 75, 0.62)" },
            divider: "rgba(30, 27, 75, 0.10)",
          },
    components: {
      MuiCssBaseline: {
        styleOverrides: {
          body: { backgroundColor: glass.base },
          "*::-webkit-scrollbar": { width: 8, height: 8 },
          "*::-webkit-scrollbar-thumb": {
            background: mode === "dark" ? "rgba(255,255,255,0.14)" : "rgba(30,27,75,0.18)",
            borderRadius: 8,
          },
          "*::-webkit-scrollbar-track": { background: "transparent" },
          "@media (prefers-reduced-motion: reduce)": {
            "*, *::before, *::after": {
              animationDuration: "0.01ms !important",
              animationIterationCount: "1 !important",
              transitionDuration: "0.01ms !important",
            },
          },
        },
      },
      MuiPaper: {
        styleOverrides: {
          root: ({ theme }) => ({
            ...glassPanel(theme),
            backgroundImage: "none",
            borderRadius: 22,
          }),
        },
      },
      MuiMenu: {
        styleOverrides: {
          paper: ({ theme }) => ({ ...glassPanel(theme, true), borderRadius: 14 }),
        },
      },
      MuiButton: {
        styleOverrides: {
          root: {
            borderRadius: 999,
            paddingInline: 20,
            variants: [
              {
                props: { variant: "contained", color: "primary" },
                style: {
                  background: ACCENT_GRADIENT,
                  boxShadow: "0 8px 24px rgba(168, 85, 247, 0.35)",
                  "&:hover": { boxShadow: "0 10px 30px rgba(168, 85, 247, 0.5)" },
                },
              },
            ],
          },
        },
      },
      MuiChip: {
        styleOverrides: {
          root: { borderRadius: 999, fontWeight: 500 },
        },
      },
      MuiTooltip: {
        styleOverrides: {
          tooltip: { borderRadius: 8, fontSize: 12 },
        },
      },
      MuiOutlinedInput: {
        styleOverrides: {
          root: ({ theme }) => ({
            borderRadius: 14,
            background: theme.glass.surfaceSubtle,
            "& .MuiOutlinedInput-notchedOutline": { borderColor: theme.glass.border },
          }),
        },
      },
    },
  });
}

export const darkTheme = buildTheme("dark");
export const lightTheme = buildTheme("light");

export function getTheme(isDarkMode: boolean) {
  return isDarkMode ? darkTheme : lightTheme;
}
