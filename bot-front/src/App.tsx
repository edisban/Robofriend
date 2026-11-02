import { useEffect, useState, type FormEvent } from "react";
import {
  ThemeProvider,
  CssBaseline,
  Snackbar,
  Alert,
  Box,
} from "@mui/material";
import AuthScreen from "./components/auth/AuthScreen";
import WelcomeScreen from "./components/auth/WelcomeScreen";
import ChatScreen from "./components/chat/ChatScreen";
import AuroraBackground from "./components/common/AuroraBackground";
import SplashIntro from "./components/common/SplashIntro";
import AppHeader from "./components/layout/AppHeader";
import AppFooter from "./components/layout/AppFooter";
import { AUTH_EXPIRED_EVENT, apiFetch } from "./api/apiClient";
import { getTheme } from "./theme/theme";

const THEME_KEY = "robofriend-theme";
// Older versions kept the user (and their token) here; the session now lives in HttpOnly cookies.
const LEGACY_SESSION_KEY = "robofriend-user";

interface SessionUser {
  userId: number;
  username: string;
}

type Screen = "login" | "welcome" | "chat";

function App() {
  const [darkMode, setDarkMode] = useState(() => {
    try {
      return localStorage.getItem(THEME_KEY) !== "light";
    } catch {
      return true;
    }
  });
  const [authMode, setAuthMode] = useState<"login" | "register">("login");
  const [currentUser, setCurrentUser] = useState<SessionUser | null>(null);
  // Until the server says who is signed in, show nothing behind the intro.
  const [sessionChecked, setSessionChecked] = useState(false);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [authError, setAuthError] = useState("");
  const [isAuthenticating, setIsAuthenticating] = useState(false);
  const [screen, setScreen] = useState<Screen>("login");
  const [snackbar, setSnackbar] = useState<{
    message: string;
    severity: "success" | "warning";
  } | null>(null);

  useEffect(() => {
    try {
      localStorage.setItem(THEME_KEY, darkMode ? "dark" : "light");
    } catch {
      void 0;
    }
  }, [darkMode]);

  // Restore the session from the cookies (renewing it if the access token has expired).
  useEffect(() => {
    try {
      localStorage.removeItem(LEGACY_SESSION_KEY);
    } catch {
      void 0;
    }
    apiFetch<SessionUser>("/api/auth/me", undefined, { notifyExpired: false })
      .then((user) => {
        setCurrentUser(user);
        setScreen("chat");
      })
      .catch(() => setScreen("login"))
      .finally(() => setSessionChecked(true));
  }, []);

  useEffect(() => {
    const handleExpired = () => {
      setCurrentUser(null);
      setScreen("login");
      setSnackbar({
        message: "Your session expired. Please sign in again.",
        severity: "warning",
      });
    };
    window.addEventListener(AUTH_EXPIRED_EVENT, handleExpired);
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, handleExpired);
  }, []);

  const handleAuthSubmit = async (event: FormEvent) => {
    event.preventDefault();
    const trimmedUsername = username.trim();

    if (!trimmedUsername || !password.trim()) {
      setAuthError("Please enter a username and password.");
      return;
    }

    setAuthError("");
    setIsAuthenticating(true);

    try {
      const endpoint =
        authMode === "login" ? "/api/auth/login" : "/api/auth/register";

      const user = await apiFetch<SessionUser>(endpoint, {
        method: "POST",
        body: JSON.stringify({ username: trimmedUsername, password }),
      });

      setCurrentUser(user);
      setScreen("welcome");
      setPassword("");
      setSnackbar({
        message:
          authMode === "login"
            ? "Welcome back! You're signed in."
            : "Your profile is ready. Nice to meet you!",
        severity: "success",
      });
    } catch (error) {
      setAuthError(
        error instanceof Error ? error.message : "Authentication failed.",
      );
    } finally {
      setIsAuthenticating(false);
    }
  };

  const handleLogout = async () => {
    // Ends the session on the server too; the cookies are cleared either way.
    await apiFetch<void>("/api/auth/logout", { method: "POST" }).catch(() => undefined);
    setCurrentUser(null);
    setUsername("");
    setPassword("");
    setScreen("login");
  };

  const renderScreen = () => {
    if (!sessionChecked) {
      return null;
    }
    if (screen === "chat" && currentUser) {
      return (
        <ChatScreen currentUser={currentUser} />
      );
    }
    if (screen === "welcome") {
      return (
        <WelcomeScreen
          username={currentUser?.username}
          onEnterChat={() => setScreen("chat")}
        />
      );
    }
    return (
      <Box
        sx={{ minHeight: "100%", display: "grid", placeItems: "center", px: 2, py: 4 }}
      >
        <AuthScreen
          authMode={authMode}
          username={username}
          password={password}
          authError={authError}
          loading={isAuthenticating}
          onUsernameChange={(value) => setUsername(value)}
          onPasswordChange={(value) => setPassword(value)}
          onToggleMode={() => {
            setAuthError("");
            setAuthMode((prev) => (prev === "login" ? "register" : "login"));
            setPassword("");
          }}
          onSubmit={handleAuthSubmit}
        />
      </Box>
    );
  };

  return (
    <ThemeProvider theme={getTheme(darkMode)}>
      <CssBaseline />
      <AuroraBackground />
      <SplashIntro />
      <Box
        sx={{
          position: "relative",
          zIndex: 1,
          height: "100dvh",
          display: "flex",
          flexDirection: "column",
        }}
      >
        <AppHeader
          username={currentUser && screen !== "login" ? currentUser.username : undefined}
          darkMode={darkMode}
          onToggleTheme={() => setDarkMode((prev) => !prev)}
          onLogout={handleLogout}
        />
        <Box
          component="main"
          sx={{
            flex: 1,
            minHeight: 0,
            overflowY: screen === "chat" ? "hidden" : "auto",
          }}
        >
          {renderScreen()}
        </Box>
        <AppFooter />
      </Box>
      <Snackbar
        open={snackbar !== null}
        autoHideDuration={3500}
        onClose={() => setSnackbar(null)}
        anchorOrigin={{ vertical: "bottom", horizontal: "center" }}
      >
        <Alert
          severity={snackbar?.severity ?? "success"}
          variant="filled"
          sx={{ width: "100%", borderRadius: 3 }}
        >
          {snackbar?.message}
        </Alert>
      </Snackbar>
    </ThemeProvider>
  );
}

export default App;
