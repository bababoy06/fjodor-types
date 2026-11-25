# fjodor-types

A dark page with a blinking cursor. After a moment it types *hello*, deletes it, and starts talking to you —
a single strange voice, written in the spirit of Dostoevsky, Kafka and company. You never get to answer.

Every visit picks a monologue you haven't heard yet (tracked in `localStorage`), and some only play at night or in the morning.
If you try to type, or leave the tab and come back, it notices — at the next pause, not mid-sentence.

## Run it

Two static files, no build step: open `index.html` (it loads `monologues.js` next to it), or serve the folder with any web server.

## Writing monologues

All the text lives in `monologues.js` — you never need to touch `index.html` to add or change one. One line = one paragraph.

| Syntax | Effect |
|---|---|
| `~` on its own line | long pause (also where interruptions may happen) |
| `---` on its own line | screen fades out and starts fresh |
| `> text` | line set in italic, like a quotation |
| `[p:800]` | pause 800 ms mid-line |
| `[del:6]` | erase the last 6 characters (a "correction") |
| `{clock}` `{weekday}` `{part}` | current time, day, part of day |
| `{visitline}` | changes with the visit count |
| `{device}` | different line for phone vs. computer |
| `hours: [22, 5]` | only play between those hours (wraps past midnight) |

Give every new monologue its own `id` — visitors' browsers remember which ids they've already heard.
