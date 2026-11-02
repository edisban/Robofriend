import { useState } from "react";
import { Box, Tab, Tabs } from "@mui/material";
import ReminderVault from "../reminders/ReminderVault";
import ListsPanel from "../lists/ListsPanel";
import type { useOrganizer } from "../../hooks/useOrganizer";

type SidePanelProps = {
  organizer: ReturnType<typeof useOrganizer>;
};

/** Reminders and lists, as two tabs. Used in the side column and in the mobile drawer. */
export default function SidePanel({ organizer }: SidePanelProps) {
  const [tab, setTab] = useState<"reminders" | "lists">("reminders");
  const { upcomingCount, openListCount } = organizer;

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0 }}>
      <Tabs
        value={tab}
        onChange={(_, value) => setTab(value)}
        variant="fullWidth"
        sx={{
          px: 1.5,
          pt: 1,
          minHeight: 44,
          "& .MuiTab-root": { minHeight: 44, textTransform: "none", fontWeight: 600 },
        }}
      >
        <Tab value="reminders" label={upcomingCount ? `Reminders · ${upcomingCount}` : "Reminders"} />
        <Tab value="lists" label={openListCount ? `Lists · ${openListCount}` : "Lists"} />
      </Tabs>
      <Box sx={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column", pt: 1 }}>
        {tab === "reminders" ? (
          <ReminderVault
            reminders={organizer.reminders}
            onDoneToggle={organizer.toggleReminder}
            onDelete={organizer.removeReminder}
            showHeader={false}
          />
        ) : (
          <ListsPanel
            items={organizer.listItems}
            onToggle={organizer.toggleListItem}
            onDelete={organizer.removeListItem}
            onClearDone={organizer.clearDone}
          />
        )}
      </Box>
    </Box>
  );
}
