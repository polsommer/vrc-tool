# vrc-tool

A lightweight VRChat admin helper that listens for OSC events, tracks lobby users, and logs activity.

## VRChat Admin Tool (Python)

### Setup

```bash
pip install -r requirements.txt
```

### Run

```bash
python vrc_admin_tool.py
```

### Notes

* Configure the OSC host/port to match your VRChat OSC settings.
* Logs are appended to `vrc_admin_log.txt` in the working directory.

## Discord Bot (Java + Gradle)

A Discord companion bot for VRChat groups with smart FAQs, event announcements, and safety checks.

### Features

* Slash commands for FAQs, server stats, event announcements, and message purging.
* Auto-welcome messages with links to rules and group details.
* Link/scam detection with mod log reporting.
* Scheduled keyword scans for fast staff visibility on potential issues.

### Setup

```bash
./gradlew build
```

### Run

```bash
DISCORD_TOKEN=your-token \
GUILD_ID=your-guild-id \
WELCOME_CHANNEL_ID=welcome-channel-id \
MOD_LOG_CHANNEL_ID=mod-log-channel-id \
STAFF_ROLE_ID=staff-role-id \
EVENT_PING_ROLE_ID=event-role-id \
RULES_LINK=https://example.com/rules \
GROUP_LINK=https://example.com/group \
SUPPORT_LINK=https://example.com/support \
MOD_SCAN_CHANNEL_IDS=channel-id-1,channel-id-2 \
MOD_SCAN_KEYWORDS=harass,threat,dox,swat,leak \
MOD_SCAN_INTERVAL_SECONDS=5 \
./gradlew run
```

### Slash commands

* `/ping` — Check bot latency.
* `/about` — Overview of bot features.
* `/server-info` — Server stats snapshot.
* `/faq topic:<topic>` — Quick answers for common questions.
* `/event-create name:<name> time:<time> details:<details>` — Announce an event.
* `/purge amount:<1-100> channel:<optional>` — Bulk delete recent messages in a channel.
