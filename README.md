# fjodor-types

A dark page with a blinking cursor. After a moment it types *hello*, deletes it, and starts talking to you —
a single strange voice, written in the spirit of Dostoevsky, Kafka and company. You never get to answer.

Every visit picks a monologue you haven't heard yet (tracked in `localStorage`), and some only play at night or in the morning.
If you try to type, or leave the tab and come back, it notices — at the next pause, not mid-sentence.

## Run it

It's a single static file: open `index.html`, or serve the folder with any web server.

## Writing monologues

Monologues live in the `M` array in `index.html`. One line = one paragraph.

| Syntax | Effect |
|---|---|
| `~` on its own line | long pause (also where interruptions may happen) |
| `---` on its own line | screen fades out and starts fresh |
| `[p:800]` | pause 800 ms mid-line |
| `[del:6]` | erase the last 6 characters (a "correction") |
| `{clock}` `{weekday}` `{part}` | current time, day, part of day |
| `{visitline}` | changes with the visit count |
| `{device}` | different line for phone vs. computer |
| `when: h => h >= 22 \|\| h < 5` | only play during those hours |
