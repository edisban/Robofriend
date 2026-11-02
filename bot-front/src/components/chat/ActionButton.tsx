import type { ReactNode } from "react";
import { IconButton, Tooltip } from "@mui/material";

type ActionButtonProps = {
  /** Shown as a tooltip and used as the accessible name. */
  title: string;
  onClick: () => void;
  children: ReactNode;
};

/** A small icon button in the row under a message (copy, edit, delete...). */
export default function ActionButton({ title, onClick, children }: ActionButtonProps) {
  return (
    <Tooltip title={title}>
      <IconButton
        size="small"
        aria-label={title}
        onClick={onClick}
        sx={{ p: 0.5, color: "text.secondary", "&:hover": { color: "text.primary" } }}
      >
        {children}
      </IconButton>
    </Tooltip>
  );
}
