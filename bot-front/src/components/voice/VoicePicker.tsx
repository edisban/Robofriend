import { useState } from "react";
import { Chip, IconButton, ListItemIcon, ListSubheader, Menu, MenuItem, Tooltip, Typography } from "@mui/material";
import { Check, RecordVoiceOver } from "@mui/icons-material";
import {
  isNaturalVoice,
  preferredVoice,
  rememberVoice,
  shortVoiceName,
  useVoices,
  voicesFor,
  type VoiceLang,
} from "../../utils/voices";
import { GLASS_BUTTON } from "./voiceStyles";

const LANG_LABEL: Record<VoiceLang, string> = { el: "Greek", en: "English" };

type VoicePickerProps = {
  /** Called after the user picks a voice, so the screen can show its name. */
  onChange: () => void;
};

/** The voice button in the corner and its menu: Greek and English voices, natural ones marked. */
export default function VoicePicker({ onChange }: VoicePickerProps) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const voices = useVoices();

  const choose = (lang: VoiceLang, name: string) => {
    rememberVoice(lang, name);
    setAnchor(null);
    onChange();
  };

  return (
    <>
      <Tooltip title="Choose RoboFriend's voice">
        <IconButton
          onClick={(e) => setAnchor(e.currentTarget)}
          aria-label="Choose voice"
          sx={{ ...GLASS_BUTTON, width: 44, height: 44, position: "absolute", top: { xs: 16, md: 28 }, right: { xs: 16, md: 28 } }}
        >
          <RecordVoiceOver fontSize="small" />
        </IconButton>
      </Tooltip>

      <Menu
        anchorEl={anchor}
        open={anchor !== null}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: "bottom", horizontal: "right" }}
        transformOrigin={{ vertical: "top", horizontal: "right" }}
        sx={{ zIndex: 1500 }}
        slotProps={{ paper: { sx: { maxHeight: "70vh", minWidth: 280 } } }}
      >
        {(["el", "en"] as VoiceLang[]).flatMap((lang) => {
          const options = voicesFor(voices, lang);
          const current = preferredVoice(voices, lang);
          return [
            <ListSubheader key={`h-${lang}`} sx={{ bgcolor: "transparent", lineHeight: "32px", fontWeight: 700 }}>
              {LANG_LABEL[lang]}
            </ListSubheader>,
            ...(options.length === 0
              ? [
                  <MenuItem key={`none-${lang}`} disabled>
                    No {LANG_LABEL[lang]} voices in this browser
                  </MenuItem>,
                ]
              : options.map((v) => (
                  <MenuItem key={`${lang}-${v.name}`} selected={current?.name === v.name} onClick={() => choose(lang, v.name)}>
                    <ListItemIcon>{current?.name === v.name && <Check fontSize="small" />}</ListItemIcon>
                    <Typography variant="body2" sx={{ flex: 1 }}>
                      {shortVoiceName(v)}
                    </Typography>
                    {isNaturalVoice(v) && (
                      <Chip label="natural" size="small" color="secondary" variant="outlined" sx={{ ml: 1, height: 20 }} />
                    )}
                  </MenuItem>
                ))),
            ...(lang === "el" && !options.some(isNaturalVoice)
              ? [
                  <Typography key="hint-el" variant="caption" component="p" sx={{ px: 2, pb: 1, color: "text.secondary", maxWidth: 280 }}>
                    Tip: open RoboFriend in Microsoft Edge for natural-sounding Greek voices.
                  </Typography>,
                ]
              : []),
          ];
        })}
      </Menu>
    </>
  );
}
