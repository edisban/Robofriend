import { apiFetch } from "./apiClient";

export type ListItem = {
  id: number;
  listName: string;
  text: string;
  done: boolean;
  createdAt?: string;
  doneAt?: string | null;
};

export async function getLists(): Promise<ListItem[]> {
  return apiFetch<ListItem[]>("/api/lists");
}

export async function setListItemDone(itemId: number, done: boolean): Promise<void> {
  await apiFetch<void>(`/api/lists/items/${itemId}`, {
    method: "PATCH",
    body: JSON.stringify({ done }),
  });
}

export async function deleteListItem(itemId: number): Promise<void> {
  await apiFetch<void>(`/api/lists/items/${itemId}`, { method: "DELETE" });
}

export async function clearDoneItems(listName: string): Promise<void> {
  await apiFetch<void>(`/api/lists/${encodeURIComponent(listName)}/done`, {
    method: "DELETE",
  });
}
