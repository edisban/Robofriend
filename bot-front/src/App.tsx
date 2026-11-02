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

const STORAGE_KEY = "robofriend-user";
const THEME_KEY = "robofriend-theme";

interface SessionUser {
  userId: number;
  username: string;
  token: string;
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
  const [currentUser, setCurrentUser] = useState<SessionUser | null>(() => {
    const storedUser = localStorage.getItem(STORAGE_KEY);
    if (storedUser) {
      try {
        const parsedUser = JSON.parse(storedUser) as SessionUser;
        if (parsedUser?.userId && parsedUser?.username && parsedUser?.token) {
          return parsedUser;
        }
      } catch {
        localStorage.removeItem(STORAGE_KEY);
      }
    }
    return null;
  });
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [authError, setAuthError] = useState("");
  const [isAuthenticating, setIsAuthenticating] = useState(false);
  const [screen, setScreen] = useState<Screen>(() =>
    currentUser ? "chat" : "login",
  );
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

  useEffect(() => {
    const handleExpired = () => {
      localStorage.removeItem(STORAGE_KEY);
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

      const data = await apiFetch<{ userId: number; username?: string; token?: string }>(
        endpoint,
        {
          method: "POST",
          body: JSON.stringify({ username: trimmedUsername, password }),
        },
      );

      const user = {
        userId: Number(data.userId),
        username: data.username || trimmedUsername,
        token: data.token || "",
      };

      localStorage.setItem(STORAGE_KEY, JSON.stringify(user));
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

  const handleLogout = () => {
    localStorage.removeItem(STORAGE_KEY);
    setCurrentUser(null);
    setUsername("");
    setPassword("");
    setScreen("login");
  };

  const renderScreen = () => {
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
