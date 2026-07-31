# HeadSprites

HeadSprites lets you show custom 8x8 pixel art as player heads right inside
chat. You draw a sprite (or paste an image) in a small web editor, the plugin
turns it into a signed skin through MineSkin, and then you drop a short tag like
`<head:heart>` into chat to show it. No resource pack is needed.

It works because Minecraft 1.21.9+ can render head object components inline, so
every player sees the sprite without installing anything.

## Requirements

- Paper 1.21.9 or newer (uses Adventure object components)
- Java 21
- A free MineSkin API key from https://mineskin.org/ (needed to make sprites)

## Install

1. Build the jar (see Build below) or grab a release jar.
2. Drop `HeadSprites-1.0.jar` into your server's `plugins/` folder.
3. Start the server once. This creates `plugins/HeadSprites/sprites.yml`.
4. Stop the server, open `sprites.yml`, and paste your MineSkin key into
   `mineskin-api-key`.
5. Start the server again.

## Config

The config file is `plugins/HeadSprites/sprites.yml`. It is created for you on
first run with sensible defaults.

```yaml
mineskin-api-key: ""      # your MineSkin key; required to make sprites
web:
  enabled: false          # set true to turn on the drawing editor
  port: 8765              # port the editor listens on
  bind-address: "0.0.0.0" # address to bind; 0.0.0.0 = all interfaces
  token: "change-me-..."  # password for the editor; change this
sprites: {}               # saved sprites (managed by the plugin)
sequences: {}             # saved multi-head sequences (managed by the plugin)
```

After editing the file, run `/sprite reload` or restart the server.

## Web editor

The editor is the easiest way to make sprites. Turn it on by setting
`web.enabled: true` in `sprites.yml`, then reload or restart.

Open it in a browser at:

```
http://YOUR_SERVER_IP:8765/
```

Paste the `web.token` value from `sprites.yml` into the token box and click
Connect.

What you can do in the editor:

- Draw on an 8-pixel-tall grid with Brush, Eraser, Fill, and Pick tools.
- Click recent colors to reuse them.
- Upload a PNG, or paste an image straight from your clipboard with Ctrl+V, and
  fit it to the grid.
- Set a head count to draw a wider picture across several linked 8x8 heads.
- Name the sprite and generate it. The plugin sends it to MineSkin and saves the
  signed result.

When you generate a single 8x8 drawing you get one sprite, used as
`<head:name>`. When you use more than one head, each block is saved as its own
sprite and they are linked into a sequence, used as `<seq:name>`.

The editor is guarded by the token. Keep it private, and only expose the port to
people you trust.

## Using sprites in chat

Type these tags in chat and they turn into inline heads:

- `<head:name>` - shows a saved sprite by name. If the name is not a saved
  sprite, it is treated as a player name or UUID and shows that player's head.
- `<sprite:key>` or `<sprite:atlas:key>` - shows a built-in texture-atlas icon.
- `<seq:name>` - shows a saved multi-head sequence as one connected picture.

You can chain heads to spell things out, for example
`<head:letter_h><head:letter_i>`.

Anyone can use the three sprite tags. Players with the `headsprites.chatformat`
permission can also use full MiniMessage formatting (colors, bold, and so on) in
their messages.

## Using with Skript

If you run a custom chat format through Skript (or any plugin that cancels the
chat event and rebuilds the message), the built-in renderer never gets to run,
so sprite tags would show up as plain text. To fix this, HeadSprites exposes a
PlaceholderAPI placeholder that hands you the fully rendered message.

### Requirements

- [PlaceholderAPI](https://www.spigotmc.org/resources/6245/). The bridge and the
  placeholder are only registered when PlaceholderAPI is installed; without it
  the plugin falls back to the vanilla renderer described above.
- A Skript addon that can build a text component from a JSON string, such as
  [SkBee](https://github.com/ShaneBeee/SkBee) (`text component from json ...`).

### The placeholder

`%headsprites_msg%` returns the sender's chat message **already rendered** as a
JSON text component, with all `<head:…>`, `<sprite:…>`, and `<seq:…>` tags
resolved into inline heads (and MiniMessage applied for players with
`headsprites.chatformat`).

The value is captured at the very start of the chat event, before your Skript
cancels it, so it is always available. When there is nothing to show it returns
an empty-but-valid component (`{"text":""}`), so parsing it never throws.

Because inline heads are Adventure object components, they cannot be represented
as a MiniMessage string. Do **not** drop `%headsprites_msg%` into a `formatted
"…"` string. Instead build your prefix as one component and the message as a JSON
component, then combine them:

```applescript
on chat with priority low:
    # The HeadSprites bridge (priority lowest) has already captured the
    # rendered message. "message" here is still the original typed text,
    # which is good for plain-text sinks like Discord or the console.
    cancel event

    set {_prefix} to mini message from "%player's prefix%<white>%player%<gray>: "
    set {_body} to text component from json "%headsprites_msg%"
    broadcast {_prefix} and {_body}

    send "%player%: %message%" to console
```

### Priority matters

Run your Skript **before** `NORMAL` priority (for example `with priority low` or
`lowest`). The built-in `ChatListener` runs at `NORMAL` and rewrites the message
into rendered components; if your Skript reads `message` / `unformatted message`
after that point, the head tags will have collapsed to empty text and your
plain-text (Discord/console) lines lose them. Reading the raw text before
`NORMAL` keeps it intact, while `%headsprites_msg%` still gives you the rendered
version for in-game chat.

Cancelling the event suppresses the vanilla message, so you will not get a
duplicate line from the built-in renderer.

## Commands

All `/sprite` subcommands need the `headsprites.admin` permission.

- `/sprite generate <name> <imageUrl> [fallback]` - makes a sprite from an image
  URL through MineSkin and saves it. The optional fallback text is shown to
  clients that cannot render inline heads.
- `/sprite list` - lists every saved sprite.
- `/sprite reload` - reloads `sprites.yml`.

Most people will use the web editor instead of `generate`, but the command is
there if you already have an image hosted somewhere.

## Permissions

- `headsprites.admin` - use the `/sprite` command. Default: op.
- `headsprites.chatformat` - use full MiniMessage formatting in chat. Default:
  op.

## Build

You need Java 21 and Maven.

```
mvn clean package
```

The finished plugin is `target/HeadSprites-1.0.jar`. This is the shaded jar with
its dependencies bundled, so it is the one you drop into `plugins/`.

## How it works

- The editor sends your drawing to the plugin as a PNG.
- The plugin scales it into the 8x8 head-face area on a transparent 64x64 skin.
- That skin goes to MineSkin, which returns a signed texture value and
  signature.
- The signed texture is saved in `sprites.yml` and rendered in chat as a head
  object component, so no resource pack is required.

## Notes

- Fully transparent pixels stay transparent, so you can make non-square shapes.
- MineSkin has rate limits. Generating many heads at once (a wide sequence)
  makes one request per head, so large pictures take a little time.

## License

Add your license of choice here.
