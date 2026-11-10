import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as listApi from "../api/listApi";
import * as reminderApi from "../api/reminderApi";
import { useOrganizer } from "./useOrganizer";

vi.mock("../api/listApi", () => ({
  getLists: vi.fn(),
  setListItemDone: vi.fn(),
  deleteListItem: vi.fn(),
  clearDoneItems: vi.fn(),
}));
vi.mock("../api/reminderApi", () => ({
  getReminders: vi.fn(),
  updateReminderStatus: vi.fn(),
  deleteReminder: vi.fn(),
}));

const lists = vi.mocked(listApi);
const reminders = vi.mocked(reminderApi);

const milk = { id: 1, listName: "shopping", text: "milk", done: false } as listApi.ListItem;
const eggs = { id: 2, listName: "shopping", text: "eggs", done: false } as listApi.ListItem;

describe("useOrganizer", () => {
  beforeEach(() => {
    lists.getLists.mockResolvedValue([milk, eggs]);
    reminders.getReminders.mockResolvedValue([]);
  });

  it("ticks an item off at once and keeps it when the server agrees", async () => {
    lists.setListItemDone.mockResolvedValue(undefined);
    const { result } = renderHook(() => useOrganizer(7));
    await waitFor(() => expect(result.current.listItems).toHaveLength(2));

    act(() => result.current.toggleListItem(milk));

    expect(result.current.listItems.find((i) => i.id === 1)?.done).toBe(true);
    expect(result.current.openListCount).toBe(1);
    expect(lists.setListItemDone).toHaveBeenCalledWith(1, true);
  });

  it("goes back to what the server has when it refuses the change", async () => {
    let refuse: (reason: Error) => void = () => undefined;
    lists.setListItemDone.mockReturnValue(new Promise((_, reject) => (refuse = reject)));
    const { result } = renderHook(() => useOrganizer(7));
    await waitFor(() => expect(result.current.listItems).toHaveLength(2));
    const loadsBefore = lists.getLists.mock.calls.length;

    act(() => result.current.toggleListItem(milk));
    expect(result.current.listItems.find((i) => i.id === 1)?.done).toBe(true);

    await act(async () => refuse(new Error("500")));

    await waitFor(() => expect(result.current.listItems.find((i) => i.id === 1)?.done).toBe(false));
    expect(lists.getLists.mock.calls.length).toBe(loadsBefore + 1);
  });

  it("clears ticked-off items of one list only", async () => {
    const callMom = { id: 3, listName: "todo", text: "call mom", done: true } as listApi.ListItem;
    lists.getLists.mockResolvedValue([{ ...milk, done: true }, eggs, callMom]);
    lists.clearDoneItems.mockResolvedValue(undefined);
    const { result } = renderHook(() => useOrganizer(7));
    await waitFor(() => expect(result.current.listItems).toHaveLength(3));

    act(() => result.current.clearDone("shopping"));

    expect(result.current.listItems.map((i) => i.id)).toEqual([2, 3]);
  });
});
