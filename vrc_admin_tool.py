import datetime
import queue
import threading
import tkinter as tk
from tkinter import ttk
from pythonosc import dispatcher, osc_server, udp_client

# ---------------- CONFIG ----------------

OSC_LISTEN_IP = "127.0.0.1"
OSC_LISTEN_PORT = 9001

VRC_SEND_IP = "127.0.0.1"
VRC_SEND_PORT = 9000

ANNOUNCE_INTERVAL = 300  # 5 minutes
LOG_FILE = "vrc_admin_log.txt"

ANNOUNCEMENTS = [
    "Welcome! Please follow the world rules.",
    "This instance is moderated. Be respectful.",
    "Admins are present. Harassment will not be tolerated.",
    "If you need help, contact an admin.",
    "Reminder: Keep language and behavior appropriate.",
]

# ---------------------------------------


class VrcAdminTool:
    def __init__(self, root):
        self.root = root
        self.root.title("VRChat Admin Tool")
        self.root.geometry("950x700")

        self.queue = queue.Queue()
        self.server = None
        self.running = False

        self.chat = udp_client.SimpleUDPClient(
            VRC_SEND_IP, VRC_SEND_PORT
        )

        self.announce_index = 0

        self._build_ui()
        self._poll_queue()

    # ---------------- UI ----------------

    def _build_ui(self):
        main = ttk.Frame(self.root, padding=12)
        main.pack(fill=tk.BOTH, expand=True)

        self.status = tk.StringVar(value="Stopped")
        ttk.Label(main, textvariable=self.status).pack(anchor=tk.W)

        ctrl = ttk.Frame(main)
        ctrl.pack(fill=tk.X, pady=6)

        ttk.Button(ctrl, text="Start Admin System", command=self.start).pack(side=tk.LEFT)
        ttk.Button(ctrl, text="Stop", command=self.stop).pack(side=tk.LEFT, padx=6)

        # Admin Chatbox
        chatbox = ttk.LabelFrame(main, text="Admin Chatbox", padding=10)
        chatbox.pack(fill=tk.X, pady=10)

        self.chat_var = tk.StringVar()
        ttk.Entry(chatbox, textvariable=self.chat_var).pack(fill=tk.X, pady=4)

        ttk.Button(
            chatbox,
            text="Send Admin Message",
            command=self.send_admin_message
        ).pack(fill=tk.X)

        # Join Logger
        joinbox = ttk.LabelFrame(main, text="Manual Join Logger", padding=10)
        joinbox.pack(fill=tk.X, pady=10)

        self.join_var = tk.StringVar()
        ttk.Entry(joinbox, textvariable=self.join_var).pack(fill=tk.X)

        ttk.Button(
            joinbox,
            text="Log User Join",
            command=self.log_join
        ).pack(fill=tk.X, pady=4)

        # Log Output
        logbox = ttk.LabelFrame(main, text="System Log", padding=10)
        logbox.pack(fill=tk.BOTH, expand=True)

        self.log = tk.Text(logbox, height=20, wrap=tk.WORD)
        self.log.pack(fill=tk.BOTH, expand=True)
        self.log.configure(state=tk.DISABLED)

    # ---------------- Core ----------------

    def start(self):
        if self.running:
            return

        disp = dispatcher.Dispatcher()
        disp.map("/*", self.on_osc)

        try:
            self.server = osc_server.ThreadingOSCUDPServer(
                (OSC_LISTEN_IP, OSC_LISTEN_PORT),
                disp
            )
        except OSError as e:
            self.log_event(f"OSC ERROR: {e}")
            return

        threading.Thread(
            target=self.server.serve_forever,
            daemon=True
        ).start()

        self.running = True
        self.status.set("Admin System Active")
        self.log_event("Admin system started")

        self.root.after(ANNOUNCE_INTERVAL * 1000, self.announcement_cycle)

    def stop(self):
        if not self.running:
            return

        self.running = False
        if self.server:
            self.server.shutdown()
            self.server.server_close()
            self.server = None

        self.status.set("Stopped")
        self.log_event("Admin system stopped")

    # ---------------- Functions ----------------

    def announcement_cycle(self):
        if not self.running:
            return

        msg = ANNOUNCEMENTS[self.announce_index]
        self.announce_index = (self.announce_index + 1) % len(ANNOUNCEMENTS)

        self.send_chatbox(f"[ADMIN] {msg}")
        self.log_event(f"AUTO ANNOUNCEMENT: {msg}")

        self.root.after(ANNOUNCE_INTERVAL * 1000, self.announcement_cycle)

    def send_admin_message(self):
        text = self.chat_var.get().strip()
        if not text:
            return
        self.send_chatbox(f"[ADMIN] {text}")
        self.log_event(f"ADMIN CHAT: {text}")
        self.chat_var.set("")

    def log_join(self):
        name = self.join_var.get().strip()
        if not name:
            return
        self.log_event(f"USER JOINED: {name}")
        self.join_var.set("")

    def send_chatbox(self, text):
        self.chat.send_message(
            "/chatbox/input",
            [text, True, True]
        )

    def on_osc(self, addr, *args):
        payload = ", ".join(str(a) for a in args) if args else "-"
        self.queue.put(f"OSC {addr} -> {payload}")

    # ---------------- Logging ----------------

    def log_event(self, msg):
        timestamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        line = f"[{timestamp}] {msg}"

        self.log.configure(state=tk.NORMAL)
        self.log.insert(tk.END, line + "\n")
        self.log.see(tk.END)
        self.log.configure(state=tk.DISABLED)

        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(line + "\n")

    def _poll_queue(self):
        while not self.queue.empty():
            self.log_event(self.queue.get())
        self.root.after(150, self._poll_queue)


if __name__ == "__main__":
    root = tk.Tk()
    VrcAdminTool(root)
    root.mainloop()
