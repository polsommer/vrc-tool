import datetime
import queue
import threading
import tkinter as tk
from tkinter import ttk

from pythonosc import dispatcher
from pythonosc import osc_server


DEFAULT_HOST = "0.0.0.0"
DEFAULT_PORT = 9001
LOG_FILE = "vrc_admin_log.txt"


class AdminToolApp:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title("VRC Admin Tool")
        self.event_queue: queue.Queue[str] = queue.Queue()
        self.users: set[str] = set()
        self.server: osc_server.ThreadingOSCUDPServer | None = None
        self.server_thread: threading.Thread | None = None

        self._build_ui()
        self._schedule_queue_poll()

    def _build_ui(self) -> None:
        self.root.geometry("900x600")
        main = ttk.Frame(self.root, padding=12)
        main.pack(fill=tk.BOTH, expand=True)

        settings = ttk.LabelFrame(main, text="OSC Listener", padding=10)
        settings.pack(fill=tk.X)

        ttk.Label(settings, text="Host").grid(row=0, column=0, sticky=tk.W)
        self.host_var = tk.StringVar(value=DEFAULT_HOST)
        ttk.Entry(settings, textvariable=self.host_var, width=24).grid(
            row=0, column=1, padx=6, sticky=tk.W
        )

        ttk.Label(settings, text="Port").grid(row=0, column=2, sticky=tk.W)
        self.port_var = tk.IntVar(value=DEFAULT_PORT)
        ttk.Entry(settings, textvariable=self.port_var, width=10).grid(
            row=0, column=3, padx=6, sticky=tk.W
        )

        self.status_var = tk.StringVar(value="Stopped")
        ttk.Label(settings, textvariable=self.status_var).grid(
            row=0, column=4, padx=6, sticky=tk.W
        )

        self.start_button = ttk.Button(
            settings, text="Start Listening", command=self.start_listener
        )
        self.start_button.grid(row=0, column=5, padx=6)

        self.stop_button = ttk.Button(
            settings, text="Stop", command=self.stop_listener, state=tk.DISABLED
        )
        self.stop_button.grid(row=0, column=6, padx=6)

        settings.columnconfigure(7, weight=1)

        body = ttk.Frame(main)
        body.pack(fill=tk.BOTH, expand=True, pady=(12, 0))

        log_frame = ttk.LabelFrame(body, text="Event Log", padding=10)
        log_frame.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

        self.log_text = tk.Text(log_frame, height=20, wrap=tk.WORD)
        self.log_text.pack(fill=tk.BOTH, expand=True)
        self.log_text.configure(state=tk.DISABLED)

        user_frame = ttk.LabelFrame(body, text="Users in Lobby", padding=10)
        user_frame.pack(side=tk.RIGHT, fill=tk.Y)

        self.user_list = tk.Listbox(user_frame, height=20)
        self.user_list.pack(fill=tk.BOTH, expand=True)

        controls = ttk.Frame(user_frame)
        controls.pack(fill=tk.X, pady=(10, 0))

        self.user_entry_var = tk.StringVar()
        ttk.Entry(controls, textvariable=self.user_entry_var).pack(
            fill=tk.X, pady=(0, 6)
        )

        ttk.Button(
            controls, text="Add User", command=self.add_manual_user
        ).pack(fill=tk.X)
        ttk.Button(
            controls, text="Remove Selected", command=self.remove_selected_user
        ).pack(fill=tk.X, pady=4)
        ttk.Button(
            controls, text="Scan Users", command=self.scan_users
        ).pack(fill=tk.X)

    def _schedule_queue_poll(self) -> None:
        self._process_queue()
        self.root.after(200, self._schedule_queue_poll)

    def _process_queue(self) -> None:
        while not self.event_queue.empty():
            message = self.event_queue.get_nowait()
            self._append_log(message)

    def _append_log(self, message: str) -> None:
        timestamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        line = f"[{timestamp}] {message}"
        self.log_text.configure(state=tk.NORMAL)
        self.log_text.insert(tk.END, line + "\n")
        self.log_text.see(tk.END)
        self.log_text.configure(state=tk.DISABLED)
        with open(LOG_FILE, "a", encoding="utf-8") as log_file:
            log_file.write(line + "\n")

    def start_listener(self) -> None:
        if self.server:
            return
        host = self.host_var.get()
        port = self.port_var.get()
        osc_dispatcher = dispatcher.Dispatcher()
        osc_dispatcher.map("/*", self._handle_osc)
        self.server = osc_server.ThreadingOSCUDPServer(
            (host, port), osc_dispatcher
        )
        self.server_thread = threading.Thread(
            target=self.server.serve_forever, daemon=True
        )
        self.server_thread.start()
        self.status_var.set(f"Listening on {host}:{port}")
        self.start_button.configure(state=tk.DISABLED)
        self.stop_button.configure(state=tk.NORMAL)
        self.event_queue.put("OSC listener started")

    def stop_listener(self) -> None:
        if not self.server:
            return
        self.server.shutdown()
        self.server.server_close()
        self.server = None
        self.status_var.set("Stopped")
        self.start_button.configure(state=tk.NORMAL)
        self.stop_button.configure(state=tk.DISABLED)
        self.event_queue.put("OSC listener stopped")

    def _handle_osc(self, address: str, *args: object) -> None:
        user = self._extract_user(args)
        event = self._infer_event(address, args)
        if event == "join" and user:
            self._add_user(user)
            self.event_queue.put(f"{user} joined the lobby")
        elif event == "leave" and user:
            self._remove_user(user)
            self.event_queue.put(f"{user} left the lobby")
        else:
            payload = ", ".join(str(arg) for arg in args)
            if user:
                payload = f"{user} | {payload}"
            self.event_queue.put(f"OSC {address} -> {payload}")

    def _extract_user(self, args: tuple[object, ...]) -> str | None:
        for arg in args:
            if isinstance(arg, str) and arg.strip():
                return arg.strip()
        return None

    def _infer_event(self, address: str, args: tuple[object, ...]) -> str:
        lowered = address.lower()
        if "join" in lowered or "enter" in lowered:
            return "join"
        if "leave" in lowered or "exit" in lowered:
            return "leave"
        for arg in args:
            if isinstance(arg, str):
                arg_lower = arg.lower()
                if "joined" in arg_lower:
                    return "join"
                if "left" in arg_lower:
                    return "leave"
        return "event"

    def _add_user(self, user: str) -> None:
        if user in self.users:
            return
        self.users.add(user)
        self.user_list.insert(tk.END, user)

    def _remove_user(self, user: str) -> None:
        if user not in self.users:
            return
        self.users.remove(user)
        items = list(self.user_list.get(0, tk.END))
        for index, name in enumerate(items):
            if name == user:
                self.user_list.delete(index)
                break

    def add_manual_user(self) -> None:
        user = self.user_entry_var.get().strip()
        if not user:
            return
        self._add_user(user)
        self.event_queue.put(f"Manual add: {user}")
        self.user_entry_var.set("")

    def remove_selected_user(self) -> None:
        selection = self.user_list.curselection()
        if not selection:
            return
        user = self.user_list.get(selection[0])
        self._remove_user(user)
        self.event_queue.put(f"Removed: {user}")

    def scan_users(self) -> None:
        if not self.users:
            self.event_queue.put("Scan complete: no users detected")
            return
        summary = ", ".join(sorted(self.users))
        self.event_queue.put(f"Scan complete: {summary}")


if __name__ == "__main__":
    root = tk.Tk()
    app = AdminToolApp(root)
    root.mainloop()
