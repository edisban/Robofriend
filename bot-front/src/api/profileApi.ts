import { apiFetch } from "./apiClient";

export type Profile = {
  username: string;
  displayName: string | null;
  /** yyyy-MM-dd */
  birthday: string | null;
  memberSince: string;
};

export async function getProfile(): Promise<Profile> {
  return apiFetch<Profile>("/api/profile");
}

export async function updateProfile(changes: { displayName: string; birthday: string | null }): Promise<Profile> {
  return apiFetch<Profile>("/api/profile", { method: "PUT", body: JSON.stringify(changes) });
}

/** Permanently deletes the account and everything in it; rejects with "Wrong password." if so. */
export async function deleteAccount(password: string): Promise<void> {
  await apiFetch<void>("/api/profile", { method: "DELETE", body: JSON.stringify({ password }) });
}
