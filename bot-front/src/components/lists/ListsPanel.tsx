import { Box, Button, Checkbox, IconButton, Stack, Tooltip, Typography } from "@mui/material";
import { DeleteOutlined } from "@mui/icons-material";
import type { ListItem } from "../../api/listApi";

type ListsPanelProps = {
  items: ListItem[];
  onToggle: (item: ListItem) => void;
  onDelete: (item: ListItem) => void;
  onClearDone: (listName: string) => void;
};

const LIST_LABELS: Record<string, string> = {
  shopping: "🛒 Shopping",
  todo: "✅ To-do",
  uni: "🎓 Uni",
  work: "💼 Work",
  gym: "🏋️ Gym",
};

function listLabel(name: string) {
  return LIST_LABELS[name] ?? `📝 ${name.charAt(0).toUpperCase()}${name.slice(1)}`;
}

export default function ListsPanel({ items, onToggle, onDelete, onClearDone }: ListsPanelProps) {
  const byList = new Map<string, ListItem[]>();
  for (const item of items) {
    byList.set(item.listName, [...(byList.get(item.listName) ?? []), item]);
  }
  // Lists with open items first, then the rest.
  const lists = [...byList.entries()].sort(
    ([a, aItems], [b, bItems]) =>
      Number(bItems.some((i) => !i.done)) - Number(aItems.some((i) => !i.done)) || a.localeCompare(b),
  );

  if (lists.length === 0) {
    return (
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
        <Typography sx={{ fontSize: 30, mb: 1 }}>🛒</Typography>
        <Typography variant="body2" sx={{ fontWeight: 600, color: "text.primary" }}>
          No lists yet
        </Typography>
        <Typography variant="caption" component="p" sx={{ mt: 0.5 }}>
          Just tell me in the chat, like “add milk and eggs to my shopping list”, then ask “what's on my
          list?” when you're at the market.
        </Typography>
      </Box>
    );
  }

  return (
    <Stack spacing={2} sx={{ flex: 1, minHeight: 0, overflowY: "auto", px: 1.5, pb: 2 }}>
      {lists.map(([name, listItems]) => {
        const open = listItems.filter((i) => !i.done);
        const done = listItems.filter((i) => i.done);
        return (
          <Box key={name}>
            <Stack direction="row" sx={{ alignItems: "center", px: 1, mb: 0.5 }}>
              <Typography variant="body2" sx={{ fontWeight: 700, flex: 1 }}>
                {listLabel(name)}
              </Typography>
              <Typography variant="caption" sx={{ color: "text.secondary" }}>
                {open.length} left
              </Typography>
            </Stack>
            <Box
              sx={{
                borderRadius: 3,
                bgcolor: (t) => t.glass.surfaceSubtle,
                border: (t) => `1px solid ${t.glass.border}`,
                py: 0.5,
              }}
            >
              {[...open, ...done].map((item) => (
                <Stack
                  key={item.id}
                  direction="row"
                  sx={{
                    alignItems: "center",
                    pr: 0.5,
                    opacity: item.done ? 0.55 : 1,
                    "&:hover .list-delete": { opacity: 1 },
                  }}
                >
                  <Checkbox
                    size="small"
                    checked={item.done}
                    onChange={() => onToggle(item)}
                    slotProps={{ input: { "aria-label": `Mark "${item.text}" as ${item.done ? "not done" : "done"}` } }}
                    sx={{ "&.Mui-checked": { color: "secondary.main" } }}
                  />
                  <Typography
                    variant="body2"
                    sx={{
                      flex: 1,
                      minWidth: 0,
                      overflowWrap: "anywhere",
                      textDecoration: item.done ? "line-through" : "none",
                    }}
                  >
                    {item.text}
                  </Typography>
                  <Tooltip title="Remove">
                    <IconButton
                      className="list-delete"
                      size="small"
                      onClick={() => onDelete(item)}
                      aria-label={`Remove "${item.text}"`}
                      sx={{
                        opacity: { xs: 0.7, md: 0 },
                        transition: "opacity 0.15s ease",
                        color: "text.secondary",
                        "&:hover": { color: "error.main" },
                      }}
                    >
                      <DeleteOutlined sx={{ fontSize: 17 }} />
                    </IconButton>
                  </Tooltip>
                </Stack>
              ))}
            </Box>
            {done.length > 0 && (
              <Button size="small" onClick={() => onClearDone(name)} sx={{ mt: 0.5, ml: 0.5 }}>
                Clear {done.length} ticked
              </Button>
            )}
          </Box>
        );
      })}
    </Stack>
  );
}
