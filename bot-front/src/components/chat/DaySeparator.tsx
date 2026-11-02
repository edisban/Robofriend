import { Box, Typography } from "@mui/material";

/** The "Today" / "Yesterday" pill between messages of different days. */
export default function DaySeparator({ label }: { label: string }) {
  return (
    <Box sx={{ display: "flex", justifyContent: "center", my: 1.5 }}>
      <Typography
        variant="caption"
        sx={{
          px: 1.5,
          py: 0.4,
          borderRadius: 999,
          color: "text.secondary",
          bgcolor: (t) => t.glass.surfaceSubtle,
          border: (t) => `1px solid ${t.glass.border}`,
          backdropFilter: "blur(12px)",
          fontWeight: 600,
        }}
      >
        {label}
      </Typography>
    </Box>
  );
}
