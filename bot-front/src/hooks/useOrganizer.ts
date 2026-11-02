import { useCallback, useEffect, useState } from "react";
import {
  clearDoneItems,
  deleteListItem,
  getLists,
  setListItemDone,
  type ListItem,
} from "../api/listApi";
import {
  deleteReminder,
  getReminders,
  updateReminderStatus,
  type ReminderItem,
} from "../api/reminderApi";

const POLL_INTERVAL_MS = 20000;

/**
 * Reminders and lists for the side panel. Changes show immediately (optimistic update); if the
 * server refuses one, the panel reloads from the server.
 */
export function useOrganizer(userId: number) {
  const [reminders, setReminders] = useState<ReminderItem[]>([]);
  const [listItems, setListItems] = useState<ListItem[]>([]);

  const refresh = useCallback(() => {
    getReminders(userId).then((data) => setReminders(data ?? [])).catch(() => undefined);
    getLists().then((data) => setListItems(data ?? [])).catch(() => undefined);
  }, [userId]);

  useEffect(() => {
    refresh();
    const interval = setInterval(refresh, POLL_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [refresh]);

  const toggleReminder = (id: number) => {
    const current = reminders.find((r) => r.id === id);
    const status = current?.status === "DONE" ? "PENDING" : "DONE";
    setReminders((prev) => prev.map((r) => (r.id === id ? { ...r, status } : r)));
    updateReminderStatus(id, status).catch(refresh);
  };

  const removeReminder = (id: number) => {
    setReminders((prev) => prev.filter((r) => r.id !== id));
    deleteReminder(id).catch(refresh);
  };

  const toggleListItem = (item: ListItem) => {
    setListItems((prev) => prev.map((i) => (i.id === item.id ? { ...i, done: !i.done } : i)));
    setListItemDone(item.id, !item.done).catch(refresh);
  };

  const removeListItem = (item: ListItem) => {
    setListItems((prev) => prev.filter((i) => i.id !== item.id));
    deleteListItem(item.id).catch(refresh);
  };

  const clearDone = (listName: string) => {
    setListItems((prev) => prev.filter((i) => !(i.listName === listName && i.done)));
    clearDoneItems(listName).catch(refresh);
  };

  return {
    reminders,
    listItems,
    upcomingCount: reminders.filter((r) => r.status !== "DONE").length,
    openListCount: listItems.filter((i) => !i.done).length,
    refresh,
    toggleReminder,
    removeReminder,
    toggleListItem,
    removeListItem,
    clearDone,
  };
}
