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

## The drop archive

`drop.fjodor-types.com` is where visitors leave their own monologues.

1. **Writing:** a dark page with one big text box. *See the pauses* shows how the voice will type it.
   An unsent draft survives a reload.
2. **Pauses:** the server turns the text into the script format (`server/.../Pauses.java`). Each paragraph
   break becomes `~` and each sentence its own line; lines the writer broke by hand stay as they are; `...` and
   long dashes get a short `[p:]`. Writers can use the script syntax themselves; pauses are capped at 6 seconds.
3. **Review:** each submission is posted in your Discord review channel (chosen with `/setup`) with **Approve**,
   **Edit pauses** and **Reject**. *Edit pauses* opens the script in a Discord form. Only the IDs in `discord.reviewer-ids` can press
   them. A submission that couldn't be posted (Discord down) is posted again when the service restarts.
4. **Live:** approved pieces are served at `fjodor-types.com/community.js`, in the same shape as
   `monologues.js`, with ids `drop-<id>`. Visitors' browsers treat them as unheard.

**Against abuse:** at most 3 submissions an hour and 6 a day per address (kept in memory only), 80–3000
characters, a hidden field only bots fill in, too-fast sends silently ignored, a required "I wrote this myself"
box, and at most 100 waiting at once. Submitted text is only ever shown as text, never as HTML.

Everything is kept in `/opt/fjodor/submissions.json`: what the writer sent, the script with your edits, the
optional signature, and who decided.

## Running it on the VPS

It runs like Pearl'It All: its own `fjodor` user in `/opt/fjodor`, `fjodor.service` on `127.0.0.1:8810`, nginx
with HTTPS in front, a read-only deploy key for pulling. The Java service serves both addresses; there's no
`/var/www`. No libraries: the build needs nothing but Java 21.

### 1. DNS

`A` records for `@`, `www` and `drop` → the VPS's IP.

### 2. Install

```bash
curl -fsSLO https://raw.githubusercontent.com/bababoy06/fjodor-types/main/deploy/setup.sh   # or copy it over
bash setup.sh
```

The repo is private, so if the download asks for a login, paste the file's contents into `setup.sh` by hand.
The script makes the user and folder, shows a deploy key to add on GitHub, clones, builds, installs the
service and copies `config.example.properties` to `/opt/fjodor/config.properties`.

### 3. nginx and HTTPS

`/etc/nginx/sites-available/fjodor`:

```nginx
server {
    server_name fjodor-types.com drop.fjodor-types.com;
    client_max_body_size 64k;

    location / {
        proxy_pass http://127.0.0.1:8810;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}

server {
    server_name www.fjodor-types.com;
    return 301 https://fjodor-types.com$request_uri;
}
```

```bash
sudo ln -s /etc/nginx/sites-available/fjodor /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d fjodor-types.com -d www.fjodor-types.com -d drop.fjodor-types.com
```

### 4. The Discord bot

One bot does everything: the review channel, the rules check and the Night Desk.

1. [discord.com/developers/applications](https://discord.com/developers/applications) → **New Application**
   (e.g. *The Archivist*).
2. **General Information:** copy the **Public Key** (= `discord.public-key`).
3. **Bot:** **Reset Token** and copy it (= `discord.bot-token`). Turn on **Server Members Intent**: it lets the
   bot see people join, to give them the visitor role.
4. **Invite it** to your server: OAuth2 → URL Generator, scopes `bot` and `applications.commands`, permissions
   **Manage Roles**, **Manage Channels**, **View Channels**, **Send Messages**, **Embed Links**,
   **Read Message History**, **Create Private Threads**, **Send Messages in Threads** and **Manage Threads**.
   Open the link.
5. Fill in the token, the public key and your user ID (`discord.reviewer-ids`):
   `sudo nano /opt/fjodor/config.properties`, then `sudo systemctl restart fjodor`.
6. Back in **General Information**, set the **Interactions Endpoint URL** to
   `https://drop.fjodor-types.com/discord/interactions` (Discord checks it right away, so the site has to be running).

### 5. `/setup`: choose the channels and roles

Make the channels and roles you want first (for example `#rules`, `#the-night-desk`, `#archive-review`, and the
roles *Those Who Stayed*, *The Night Desk*, optionally *Visitor*). Then, in Discord, as someone with
**Manage Server**:

```
/setup rules:#rules night_desk:#the-night-desk member_role:@Those Who Stayed night_desk_role:@The Night Desk
       visitor_role:@Visitor review_channel:#archive-review
```

| Option | What it's for |
|---|---|
| `rules` | The verify button goes here. Everyone can read it; nobody can write in it. |
| `night_desk` | The Night Desk button goes here. Letters become private threads in this channel. |
| `member_role` | Given by the verify button. Opens the public channels. |
| `night_desk_role` | Sees every letter and can close them. Also sees the review channel. |
| `visitor_role` *(optional)* | Given the moment someone joins, taken away when they verify. |
| `review_channel` *(optional)* | Where new drop submissions are posted with Approve / Edit / Reject. |
| `broadcast_channel` *(optional)* | Every night at 3:00 a monologue is typed here, live. |
| `fix_permissions` *(default yes)* | Sets the permissions on these channels for you (see below). |
| `hide_other_channels` *(default no)* | Every other channel `@everyone` can see now becomes visible only to the member role and the Night Desk. |

The bot remembers your choices (`/opt/fjodor/discord.json`), sets the permissions, posts the two buttons
(replacing old ones if you run `/setup` again), and tells you what it did and what it couldn't. Run it again
any time to change something.

**What `fix_permissions` sets:**
- **rules:** `@everyone` can read, can't send, react or start threads.
- **Night Desk channel:** hidden from `@everyone`. The member role can see it but only write *inside* their own
  letter. The Night Desk role sees and manages every letter. The Night Desk role is made mentionable, so it's
  pulled into each new letter.
- **review channel:** hidden from everyone except the Night Desk role.

**One thing to do by hand:** drag the bot's own role **above** the member and visitor roles (Server Settings →
Roles). Discord only lets a bot hand out roles below its own. `/setup` warns you if it's in the wrong place.

**The visitor role** needs the always-on connection (`discord.gateway=on`, the default) and the Server
Members Intent. Without a visitor role, `@everyone` is simply the "unverified" state.

### The 3 a.m. broadcast

Every night at **3:00** (Brussels time) the bot types one monologue, live, in the `broadcast_channel` from
`/setup`: one line at a time, with the typing indicator, and a pause at every `~`. It uses the same texts as the
site (`monologues.js` plus approved drops), skips hidden ones and ones meant for other hours, and doesn't repeat
any of the last 10. A small line at the end says when it was typed.

- `/broadcast` (Manage Server) starts one right away, to try it out.
- Change the time or zone with `broadcast.time` and `broadcast.timezone` in `config.properties`.

### 6. Updating

```bash
~/update-fjodor.sh
```

Pulls as the `fjodor` user, then builds and restarts. Changes to the texts (`index.html`, `monologues.js`,
`drop/index.html`) are live right after the pull, since they're read from the checkout on every request.

### 7. Backups

Everything visitors sent is in `/opt/fjodor/submissions.json`, and your `/setup` choices in
`/opt/fjodor/discord.json`. Add them to the nightly backup:

```bash
sudo cp /opt/fjodor/submissions.json /home/<you>/backups/fjodor-submissions-$(date +%F).json
sudo cp /opt/fjodor/discord.json /home/<you>/backups/fjodor-discord-$(date +%F).json
```
