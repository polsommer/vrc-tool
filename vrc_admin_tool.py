import datetime
import json
import os
import queue
import random
import re
import threading
import tkinter as tk
import urllib.error
import urllib.request
import urllib.parse
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

QUICK_ANNOUNCEMENTS = [
    "Quick reminder: keep it friendly and respectful.",
    "Please avoid yelling or mic spam. Thanks!",
    "No hate speech or harassment. Be kind.",
    "Keep conversations PG-13 in public areas.",
    "If you need help, ping an admin.",
    "Please respect personal space and boundaries.",
    "No disruptive avatars or effects in shared areas.",
    "We are recording moderation actions. Behave accordingly.",
    "New here? Ask for the rules anytime.",
    "Let’s keep the room chill and welcoming.",
]

AI_DEFAULT_ENDPOINT = "http://localhost:11434/v1/chat/completions"
AI_DEFAULT_MODEL = "llama3.1"
AI_TIMEOUT_SECONDS = 20
AI_MEMORY_FILE = "ai_memory.jsonl"
AI_MEMORY_MAX_ITEMS = 12

PLAYER_JOIN_RE = re.compile(r"OnPlayerJoined\s+(.+)$")
PLAYER_LEFT_RE = re.compile(r"OnPlayerLeft\s+(.+)$")

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
        self.ai_busy = False
        self.active_players = []

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

        quick_box = ttk.LabelFrame(main, text="Quick Announcements", padding=10)
        quick_box.pack(fill=tk.X, pady=10)

        self.quick_list = tk.Listbox(quick_box, height=6)
        self.quick_list.pack(fill=tk.X, expand=True, pady=(0, 6))
        for message in QUICK_ANNOUNCEMENTS:
            self.quick_list.insert(tk.END, message)
        self.quick_list.bind("<Double-Button-1>", self.send_quick_announcement)

        ttk.Button(
            quick_box,
            text="Send Selected Quick Announcement",
            command=self.send_quick_announcement
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

        ttk.Label(joinbox, text="VRChat Log File").pack(anchor=tk.W, pady=(6, 0))
        self.log_path_var = tk.StringVar(value=self._find_latest_vrchat_log() or "")
        ttk.Entry(joinbox, textvariable=self.log_path_var).pack(fill=tk.X)
        ttk.Button(
            joinbox,
            text="Scan VRChat Log for Players",
            command=self.load_players_from_log
        ).pack(fill=tk.X, pady=4)

        players_box = ttk.LabelFrame(main, text="Active Players (from log)", padding=10)
        players_box.pack(fill=tk.BOTH, pady=10)

        self.player_list = tk.Listbox(players_box, height=6)
        self.player_list.pack(fill=tk.BOTH, expand=True)

        # AI Assistant
        ai_box = ttk.LabelFrame(main, text="AI Assistant (Ideas + Code)", padding=10)
        ai_box.pack(fill=tk.BOTH, pady=10)

        self.ai_status = tk.StringVar(value="Idle")
        ttk.Label(ai_box, textvariable=self.ai_status).pack(anchor=tk.W, pady=(0, 6))

        self.ai_endpoint_var = tk.StringVar(value=AI_DEFAULT_ENDPOINT)
        self.ai_model_var = tk.StringVar(value=AI_DEFAULT_MODEL)
        self.ai_key_var = tk.StringVar(value="")
        self.ai_temp_var = tk.StringVar(value="0.6")
        self.ai_tokens_var = tk.StringVar(value="350")
        self.ai_use_amd_var = tk.BooleanVar(value=True)
        self.ai_local_only_var = tk.BooleanVar(value=True)
        self.ai_memory_var = tk.BooleanVar(value=True)

        settings = ttk.Frame(ai_box)
        settings.pack(fill=tk.X)
        settings.columnconfigure(1, weight=1)
        settings.columnconfigure(3, weight=1)

        ttk.Label(settings, text="Endpoint (OpenAI-compatible)").grid(
            row=0, column=0, sticky=tk.W
        )
        ttk.Entry(settings, textvariable=self.ai_endpoint_var).grid(
            row=0, column=1, sticky=tk.EW, padx=(6, 12)
        )
        ttk.Label(settings, text="Model").grid(row=0, column=2, sticky=tk.W)
        ttk.Entry(settings, textvariable=self.ai_model_var, width=20).grid(
            row=0, column=3, sticky=tk.EW
        )

        ttk.Label(settings, text="API Key (optional)").grid(
            row=1, column=0, sticky=tk.W, pady=(6, 0)
        )
        ttk.Entry(settings, textvariable=self.ai_key_var, show="*").grid(
            row=1, column=1, sticky=tk.EW, padx=(6, 12), pady=(6, 0)
        )
        ttk.Label(settings, text="Temp").grid(row=1, column=2, sticky=tk.W, pady=(6, 0))
        ttk.Entry(settings, textvariable=self.ai_temp_var, width=8).grid(
            row=1, column=3, sticky=tk.W, pady=(6, 0)
        )

        ttk.Label(settings, text="Max Tokens").grid(
            row=2, column=0, sticky=tk.W, pady=(6, 0)
        )
        ttk.Entry(settings, textvariable=self.ai_tokens_var, width=8).grid(
            row=2, column=1, sticky=tk.W, padx=(6, 12), pady=(6, 0)
        )
        ttk.Checkbutton(
            settings,
            text="Prefer AMD GPU (ROCm)",
            variable=self.ai_use_amd_var
        ).grid(row=2, column=2, columnspan=2, sticky=tk.W, pady=(6, 0))
        ttk.Checkbutton(
            settings,
            text="Local-only (localhost)",
            variable=self.ai_local_only_var
        ).grid(row=3, column=0, columnspan=2, sticky=tk.W, pady=(6, 0))
        ttk.Checkbutton(
            settings,
            text="Enable self-learning memory",
            variable=self.ai_memory_var
        ).grid(row=3, column=2, columnspan=2, sticky=tk.W, pady=(6, 0))

        actions = ttk.Frame(ai_box)
        actions.pack(fill=tk.X, pady=(8, 6))
        ttk.Button(
            actions,
            text="Generate Admin Idea",
            command=self.generate_admin_idea
        ).pack(side=tk.LEFT)
        ttk.Button(
            actions,
            text="Generate Code Snippet",
            command=self.generate_code_snippet
        ).pack(side=tk.LEFT, padx=6)

        self.ai_output = tk.Text(ai_box, height=8, wrap=tk.WORD)
        self.ai_output.pack(fill=tk.BOTH, expand=True)
        self.ai_output.configure(state=tk.DISABLED)

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

    def send_quick_announcement(self, _event=None):
        selection = self.quick_list.curselection()
        if not selection:
            return
        text = self.quick_list.get(selection[0]).strip()
        if not text:
            return
        self.send_chatbox(f"[ADMIN] {text}")
        self.log_event(f"ADMIN QUICK: {text}")

    def log_join(self):
        name = self.join_var.get().strip()
        if not name:
            return
        self.log_event(f"USER JOINED: {name}")
        self.join_var.set("")

    def load_players_from_log(self):
        log_path = self.log_path_var.get().strip()
        if not log_path:
            log_path = self._find_latest_vrchat_log()
            if log_path:
                self.log_path_var.set(log_path)

        if not log_path or not os.path.exists(log_path):
            self.log_event("VRCHAT LOG NOT FOUND: Set the log path and try again.")
            return

        try:
            with open(log_path, "r", encoding="utf-8", errors="ignore") as file:
                lines = file.readlines()
        except OSError as exc:
            self.log_event(f"VRCHAT LOG READ ERROR: {exc}")
            return

        players = self._extract_active_players(lines)
        self._update_player_list(players)
        self.log_event(f"VRCHAT PLAYERS FOUND: {len(players)}")

    def send_chatbox(self, text):
        self.chat.send_message(
            "/chatbox/input",
            [text, True, True]
        )

    def on_osc(self, addr, *args):
        payload = ", ".join(str(a) for a in args) if args else "-"
        self.queue.put(f"OSC {addr} -> {payload}")

    # ---------------- AI Assistant ----------------

    def generate_admin_idea(self):
        self._start_ai_task("admin")

    def generate_code_snippet(self):
        self._start_ai_task("code")

    def _start_ai_task(self, mode):
        if self.ai_busy:
            return
        self.ai_busy = True
        self.ai_status.set("Thinking...")
        thread = threading.Thread(target=self._run_ai_task, args=(mode,), daemon=True)
        thread.start()

    def _run_ai_task(self, mode):
        try:
            output = self._ai_generate(mode)
        except Exception as exc:
            output = f"AI Error: {exc}"
        self.root.after(0, lambda: self._finish_ai_task(mode, output))

    def _finish_ai_task(self, mode, output):
        self.ai_busy = False
        self.ai_status.set("Idle")
        self._set_ai_output(output)
        self._append_ai_memory(mode, output)
        self.queue.put(f"AI {mode.upper()} RESULT -> {output.splitlines()[0][:160]}")

    def _set_ai_output(self, text):
        self.ai_output.configure(state=tk.NORMAL)
        self.ai_output.delete("1.0", tk.END)
        self.ai_output.insert(tk.END, text)
        self.ai_output.see(tk.END)
        self.ai_output.configure(state=tk.DISABLED)

    def _ai_generate(self, mode):
        prompt = self._build_ai_prompt(mode)
        endpoint = self.ai_endpoint_var.get().strip()
        model = self.ai_model_var.get().strip()
        if endpoint and model:
            if self.ai_local_only_var.get() and not self._is_local_endpoint(endpoint):
                self.queue.put("AI REQUEST BLOCKED -> Non-local endpoint while local-only is enabled.")
                return self._fallback_ai(mode)
            response = self._request_ai_completion(endpoint, model, prompt)
            if response:
                return response
        return self._fallback_ai(mode)

    def _build_ai_prompt(self, mode):
        amd_detected = self._detect_amd_gpu()
        amd_prefer = self.ai_use_amd_var.get()
        hardware_hint = "AMD GPU preferred" if amd_prefer else "GPU optional"
        if amd_prefer and not amd_detected:
            hardware_hint += " (no AMD GPU detected locally)"

        if mode == "admin":
            request = (
                "Generate 5 concise, high-impact ideas to improve a VRChat admin tool. "
                "Focus on safety, automation, moderation workflows, and community health. "
                "Each idea should be 1-2 sentences with a short title."
            )
        else:
            request = (
                "Provide a Python code snippet for a VRChat admin tool feature. "
                "Keep it under 40 lines, use standard library only, and include brief comments."
            )

        prompt = (
            "You are an assistant for a VRChat admin tool that supports self-learning workflows. "
            f"Hardware hint: {hardware_hint}. "
            "Return plain text only.\n\n"
            f"{request}"
        )
        if self.ai_memory_var.get():
            memory = self._load_ai_memory()
            if memory:
                prompt = (
                    f"{prompt}\n\nRecent local memory (for self-learning context):\n{memory}"
                )
        return prompt

    def _request_ai_completion(self, endpoint, model, prompt):
        headers = {"Content-Type": "application/json"}
        api_key = self.ai_key_var.get().strip()
        if api_key:
            headers["Authorization"] = f"Bearer {api_key}"

        temperature = self._parse_float(self.ai_temp_var.get(), 0.6)
        max_tokens = self._parse_int(self.ai_tokens_var.get(), 350)
        payload = {
            "model": model,
            "messages": [
                {"role": "system", "content": "You are a helpful assistant."},
                {"role": "user", "content": prompt},
            ],
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
        data = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(endpoint, data=data, headers=headers)

        try:
            with urllib.request.urlopen(request, timeout=AI_TIMEOUT_SECONDS) as response:
                raw = response.read().decode("utf-8")
        except (urllib.error.HTTPError, urllib.error.URLError) as exc:
            self.queue.put(f"AI REQUEST FAILED -> {exc}")
            return ""

        try:
            data = json.loads(raw)
            return data["choices"][0]["message"]["content"].strip()
        except (KeyError, IndexError, json.JSONDecodeError) as exc:
            self.queue.put(f"AI RESPONSE PARSE ERROR -> {exc}")
            return ""

    def _fallback_ai(self, mode):
        if mode == "admin":
            ideas = [
                "Instant Incident Review: summarize the last 15 minutes of joins, kicks, and warnings so admins can respond faster.",
                "Respect Radar: detect repeat offenders by matching names and join patterns to recent moderation actions.",
                "Quiet Hours Automations: auto-announce reduced volume rules and enforce stricter caps during late hours.",
                "Mentor Match: pair trusted users with newcomers based on shared interests to improve onboarding.",
                "Rapid Context Cards: one-click macros that explain rules with a short, calm, and consistent tone.",
            ]
            return "\n".join(f"- {idea}" for idea in ideas)

        snippets = [
            [
                "def summarize_recent(log_lines, max_items=5):",
                "    summary = []",
                "    for line in log_lines[-max_items:]:",
                "        summary.append(line.split('] ', 1)[-1])",
                "    return '\\n'.join(summary)",
            ],
            [
                "def should_warn_user(message, banned_words):",
                "    lowered = message.lower()",
                "    return any(word in lowered for word in banned_words)",
            ],
            [
                "def format_admin_alert(username, action):",
                "    return f\"[ADMIN ALERT] {username} -> {action}\"",
            ],
        ]
        snippet = random.choice(snippets)
        return "```python\n" + "\n".join(snippet) + "\n```"

    def _is_local_endpoint(self, endpoint):
        try:
            parsed = urllib.parse.urlparse(endpoint)
        except ValueError:
            return False
        hostname = (parsed.hostname or "").lower()
        return hostname in {"localhost", "127.0.0.1", "::1"}

    def _append_ai_memory(self, mode, output):
        if not self.ai_memory_var.get() or not output:
            return
        record = {
            "timestamp": datetime.datetime.now().isoformat(),
            "mode": mode,
            "output": output.strip(),
        }
        try:
            with open(AI_MEMORY_FILE, "a", encoding="utf-8") as handle:
                handle.write(json.dumps(record) + "\n")
        except OSError as exc:
            self.queue.put(f"AI MEMORY WRITE FAILED -> {exc}")

    def _load_ai_memory(self):
        if not os.path.exists(AI_MEMORY_FILE):
            return ""
        try:
            with open(AI_MEMORY_FILE, "r", encoding="utf-8") as handle:
                lines = handle.readlines()[-AI_MEMORY_MAX_ITEMS:]
        except OSError as exc:
            self.queue.put(f"AI MEMORY READ FAILED -> {exc}")
            return ""
        entries = []
        for line in lines:
            try:
                data = json.loads(line)
            except json.JSONDecodeError:
                continue
            mode = data.get("mode", "unknown")
            output = data.get("output", "").strip()
            if output:
                entries.append(f"- {mode}: {output}")
        return "\n".join(entries)

    def _detect_amd_gpu(self):
        return any(
            os.path.exists(path)
            for path in ("/sys/module/amdgpu", "/opt/rocm", "/dev/kfd")
        )

    def _default_vrchat_log_dir(self):
        home = os.path.expanduser("~")
        windows_path = os.path.join(
            home,
            "AppData",
            "LocalLow",
            "VRChat",
            "VRChat",
        )
        return windows_path

    def _find_latest_vrchat_log(self):
        log_dir = self._default_vrchat_log_dir()
        if not os.path.isdir(log_dir):
            return ""
        candidates = []
        for name in os.listdir(log_dir):
            if name.startswith("output_log") and name.endswith(".txt"):
                path = os.path.join(log_dir, name)
                candidates.append(path)
        if not candidates:
            return ""
        return max(candidates, key=os.path.getmtime)

    def _extract_active_players(self, lines):
        status = {}
        order = []
        for line in lines:
            joined = PLAYER_JOIN_RE.search(line)
            if joined:
                name = joined.group(1).strip()
                status[name] = True
                if name not in order:
                    order.append(name)
                continue
            left = PLAYER_LEFT_RE.search(line)
            if left:
                name = left.group(1).strip()
                status[name] = False
                if name not in order:
                    order.append(name)
        active = [name for name in order if status.get(name)]
        return active

    def _update_player_list(self, players):
        self.active_players = players
        self.player_list.delete(0, tk.END)
        for name in players:
            self.player_list.insert(tk.END, name)

    @staticmethod
    def _parse_float(value, fallback):
        try:
            return float(value)
        except ValueError:
            return fallback

    @staticmethod
    def _parse_int(value, fallback):
        try:
            return int(value)
        except ValueError:
            return fallback

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
