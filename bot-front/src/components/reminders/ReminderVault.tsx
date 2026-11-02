import { Box, Checkbox, Chip, IconButton, Stack, Tooltip, Typography } from "@mui/material";
import { DeleteOutlined, EventNote, Schedule } from "@mui/icons-material";
import type { ReminderItem } from "../../api/reminderApi";

type ReminderVaultProps = {
  reminders: ReminderItem[];
  onDoneToggle: (id: number) => void;
  onDelete: (id: number) => void;
  /** Hide the title when the panel sits under tabs that already name it. */
  showHeader?: boolean;
};

function formatWhen(iso: string) {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  const today = new Date();
  const tomorrow = new Date();
  tomorrow.setDate(today.getDate() + 1);
  const time = date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  if (date.toDateString() === today.toDateString()) return `Today · ${time}`;
  if (date.toDateString() === tomorrow.toDateString()) return `Tomorrow · ${time}`;
  return `${date.toLocaleDateString([], { weekday: "short", month: "short", day: "numeric" })} · ${time}`;
}

function ReminderRow({
  item,
  onDoneToggle,
  onDelete,
}: {
  item: ReminderItem;
  onDoneToggle: (id: number) => void;
  onDelete: (id: number) => void;
}) {
  const done = item.status === "DONE";
  // SENT means the reminder already fired but hasn't been ticked off yet.
  const overdue = item.status === "SENT";

  return (
    <Stack
      direction="row"
      spacing={0.5}
      sx={{
        alignItems: "center",
        pl: 0.5,
        pr: 0.5,
        py: 0.75,
        borderRadius: 3,
        bgcolor: (t) => t.glass.surfaceSubtle,
        border: (t) => `1px solid ${t.glass.border}`,
        opacity: done ? 0.6 : 1,
        transition: "border-color 0.2s ease, transform 0.2s ease",
        "&:hover": { borderColor: "primary.main", transform: "translateY(-1px)" },
        "&:hover .reminder-delete": { opacity: 1 },
      }}
    >
      <Checkbox
        checked={done}
        onChange={() => onDoneToggle(item.id)}
        size="small"
        slotProps={{ input: { "aria-label": `Mark "${item.title}" as done` } }}
        sx={{ "&.Mui-checked": { color: "secondary.main" } }}
      />
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography
          variant="body2"
          sx={{
            fontWeight: 600,
            textDecoration: done ? "line-through" : "none",
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
          }}
        >
          {item.title}
        </Typography>
        <Stack direction="row" spacing={0.5} sx={{ alignItems: "center", color: overdue ? "warning.main" : "text.secondary" }}>
          <Schedule sx={{ fontSize: 13 }} />
          <Typography variant="caption" sx={{ color: "inherit" }}>
            {formatWhen(item.scheduledAt)}
          </Typography>
        </Stack>
      </Box>
      <Tooltip title="Delete">
        <IconButton
          className="reminder-delete"
          size="small"
          onClick={() => onDelete(item.id)}
          aria-label={`Delete "${item.title}"`}
          sx={{
            opacity: { xs: 0.7, md: 0 },
            transition: "opacity 0.15s ease",
            color: "text.secondary",
            "&:hover": { color: "error.main" },
          }}
        >
          <DeleteOutlined fontSize="small" />
        </IconButton>
      </Tooltip>
    </Stack>
  );
}

export default function ReminderVault({
  reminders,
  onDoneToggle,
  onDelete,
  showHeader = true,
}: ReminderVaultProps) {
  const sorted = reminders
    .slice()
    .sort((a, b) => new Date(a.scheduledAt).getTime() - new Date(b.scheduledAt).getTime());
  const open = sorted.filter((r) => r.status !== "DONE");
  const done = sorted.filter((r) => r.status === "DONE");

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0 }}>
      <Stack
        direction="row"
        spacing={1}
        sx={{ alignItems: "center", px: 2.5, pt: 2.25, pb: 1.5, display: showHeader ? "flex" : "none" }}
      >
        <EventNote sx={{ color: "secondary.main" }} />
        <Typography variant="h6" sx={{ fontSize: 17, flex: 1 }}>
          Reminders
        </Typography>
        {open.length > 0 && (
          <Chip label={`${open.length} upcoming`} size="small" color="secondary" variant="outlined" />
        )}
      </Stack>

      {sorted.length === 0 ? (
        <Box
          sx={{
            m: 2,
            mt: 1,
            p: 3,
            textAlign: "center",
            borderRadius: 4,
            border: (t) => `1px dashed ${t.glass.border}`,
            color: "text.secondary",
          }}
        >
          <Typography sx={{ fontSize: 30, mb: 1 }}>🗓️</Typography>
          <Typography variant="body2" sx={{ fontWeight: 600, color: "text.primary" }}>
            Nothing planned yet
          </Typography>
          <Typography variant="caption" component="p" sx={{ mt: 0.5 }}>
            Just tell me in the chat, like “remind me Friday at 6pm to buy flowers”.
          </Typography>
        </Box>
      ) : (
        <Stack spacing={1} sx={{ flex: 1, minHeight: 0, overflowY: "auto", px: 1.5, pb: 2 }}>
          {open.map((item) => (
            <ReminderRow key={item.id} item={item} onDoneToggle={onDoneToggle} onDelete={onDelete} />
          ))}
          {done.length > 0 && (
            <Typography
              variant="caption"
              sx={{ px: 1, pt: open.length ? 1 : 0, color: "text.secondary", fontWeight: 600, textTransform: "uppercase", letterSpacing: 0.8 }}
            >
              Done
            </Typography>
          )}
          {done.map((item) => (
            <ReminderRow key={item.id} item={item} onDoneToggle={onDoneToggle} onDelete={onDelete} />
          ))}
        </Stack>
      )}
    </Box>
  );
}
