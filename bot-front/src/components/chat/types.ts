/** One chat bubble as the screen shows it. */
export type Message = {
  /** The server's id; missing until the message is saved. */
  id?: number;
  role: "bot" | "user";
  text: string;
  createdAt?: string;
  /** True while the reply is still being written. */
  streaming?: boolean;
};
