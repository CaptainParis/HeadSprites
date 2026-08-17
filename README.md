# HeadSprites

HeadSprites shows custom 8x8 pixel art as player heads in chat. You draw a
sprite in a small web editor, or you paste an image. The plugin makes a signed
skin with MineSkin. You then type a short tag in chat, for example
`<head:heart>`, to show the sprite. You do not need a resource pack.

The plugin works because Minecraft 1.21.9 and later can show head object
components in a line of text. Every player sees the sprite. No player installs
software.

## Requirements

- Paper 1.21.9 or later. The plugin uses Adventure object components.
- Java 21.
- A free MineSkin API key from https://mineskin.org/. You need the key to make
  sprites.

## Install

Do these steps in sequence:

1. Build the JAR file. Refer to "Build" below. You can also get a release JAR
   file.
2. Put `HeadSprites-1.1.jar` in the `plugins/` folder of your server.
3. Start the server one time. The server makes the file
   `plugins/HeadSprites/sprites.yml`.
4. Stop the server.
5. Open `sprites.yml` and put your MineSkin key in the `mineskin-api-key` field.
6. Start the server again.

## Config

The config file is `plugins/HeadSprites/sprites.yml`. The plugin makes the file
on the first start. The file has default values.

```yaml
mineskin-api-key: ""      # your MineSkin key; set it to make sprites
web:
  enabled: false          # set to true to start the drawing editor
  port: 8765              # the port for the editor
  bind-address: "0.0.0.0" # the bind address; 0.0.0.0 is all interfaces
  token: "change-me-..."  # the password for the editor; change it
sprites: {}               # the saved sprites; the plugin controls this
sequences: {}             # the saved sequences; the plugin controls this
animations: {}            # the saved animations; the plugin controls this
```

After you change the file, run `/sprite reload`, or start the server again.

## Web editor

The editor is the easiest way to make sprites. To start the editor, set
`web.enabled: true` in `sprites.yml`. Then reload the plugin, or start the
server again.

Open the editor in a web browser at this address:

```
http://YOUR_SERVER_IP:8765/
```

Put the `web.token` value from `sprites.yml` in the token box. Then click
Connect.

You can do these tasks in the editor:

- Draw on an 8-pixel grid with the Brush, Eraser, Fill, and Pick tools.
- Click a recent color to use it again.
- Upload a PNG, or paste an image with Ctrl+V. Then fit the image to the grid.
- Upload an animated GIF. The editor splits the GIF into frames automatically.
- Set the mode to Sequence or Animation with the buttons above the grid.
- Set the Heads value to draw more than one 8x8 head.
- Set the FPS in Animation mode to control the speed.
- Click Play to see a preview of the animation.
- Type a name, then click Generate & Save. The plugin sends each head to
  MineSkin, and saves the signed result.

The mode controls how the plugin uses the heads:

- **Sequence mode.** Each head is one 8x8 block. The plugin links the heads into
  one wider picture. One head makes one sprite for `<head:name>`. More than one
  head makes one sprite for each head, and one sequence for `<seq:name>`.
- **Animation mode.** You draw one frame at a time. The Heads value sets how wide
  each frame is, so a frame can be one head or a row of heads. You add frames and
  page through them like a book, and the plugin makes one animation for
  `<anim:name>`.

### Animation frames (book-style editing)

In Animation mode you edit one frame at a time on the grid:

- Set the Heads value first. This sets the width of every frame. All frames use
  the same width.
- Click **+ Add frame** to make a new empty frame after the current frame.
- Use **Prev** and **Next**, or the left and right arrow keys, to page through
  the frames. The counter shows the current frame number.
- Click **Delete frame** to remove the current frame. An animation needs at
  least one frame.
- Click **Play** to preview the frames in order at the set FPS.
- A GIF upload makes one frame for each GIF frame.

When you click Generate & Save, the plugin makes one sprite for each head in each
frame. Then it saves the animation. The tag `<anim:name>` shows the current
frame, and the frame is a full row of heads.

The token protects the editor. Keep the token secret. Open the port only to
persons that you trust.

## Sprites, sequences, and animations in chat

Type these tags in chat. The plugin changes each tag into a head:

- `<head:name>` - shows a saved sprite by name. If the name is an animation, the
  tag shows the first frame. If the name is not a sprite or an animation, the
  plugin uses the name as a player name or UUID, and shows that head.
- `<sprite:key>` or `<sprite:atlas:key>` - shows a built-in texture-atlas icon.
- `<seq:name>` - shows a saved sequence as one connected picture.
- `<anim:name>` - shows one frame of an animation. In chat the frame does not
  change, because chat text cannot change. Refer to "Animations on live
  surfaces" for animation that moves.

You can put heads together to make words, for example
`<head:letter_h><head:letter_i>`.

All players can use the four tags. A player with the `headsprites.chatformat`
permission can also use full MiniMessage format (colors, bold, and more) in a
message.

## Animations on live surfaces

A line of chat cannot change after the server sends it. So an animation in chat
shows only one frame. To show an animation that moves, use a surface that the
server can refresh, for example:

- the action bar
- a boss bar
- the scoreboard sidebar
- the tab list header or footer
- a text display entity
- a name above a player (a "gamertag")

The plugin selects the current frame from the system clock. So the frame is
correct each time that a surface shows the animation. Refresh the surface on a
timer to make the animation move.

The plugin gives you three hooks:

1. **The `<anim:name>` tag.** Use the tag in text that the plugin renders, or in
   your own MiniMessage text.
2. **The PlaceholderAPI placeholder `%headsprites_anim_<name>%`.** The
   placeholder gives you the current frame as a JSON text component. Parse the
   JSON into a component. Then set the component on your surface. Refresh it on a
   timer.
3. **The Java API.** Call `SpriteManager.getInstance().buildAnimation(name)` to
   get the current frame as a component. Call `buildAnimationFrame(animation,
   index)` to get one specific frame.

### Example: a boss bar with Skript and SkBee

This example refreshes a value every two ticks. You need PlaceholderAPI and
SkBee. Set the value on a boss bar, an action bar, or a text display.

```applescript
every 2 ticks:
    loop all players:
        set {_frame} to text component from json "%headsprites_anim_spin%"
        # Set {_frame} on a boss bar, an action bar, or a text display.
```

Set the frame speed with the FPS control in the editor. The plugin saves the
speed as milliseconds for each frame in `sprites.yml`.

## Use with Skript

You can run a custom chat format with Skript, or with a plugin that cancels the
chat event and builds the message again. In that case the built-in renderer does
not run, and the sprite tags stay as plain text. To correct this, HeadSprites
gives you a PlaceholderAPI placeholder. The placeholder gives you the full
rendered message.

### Requirements

- [PlaceholderAPI](https://www.spigotmc.org/resources/6245/). The plugin
  registers the bridge and the placeholder only if PlaceholderAPI is installed.
  If PlaceholderAPI is not installed, the plugin uses the built-in renderer.
- A Skript add-on that can make a text component from a JSON string, for example
  [SkBee](https://github.com/ShaneBeee/SkBee) (`text component from json ...`).

### The placeholder

`%headsprites_msg%` gives you the chat message of the sender as a JSON text
component. The plugin changes all `<head:…>`, `<sprite:…>`, `<seq:…>`, and
`<anim:…>` tags into heads first. The plugin also applies MiniMessage for a
player with the `headsprites.chatformat` permission.

The plugin captures the value at the start of the chat event, before your Skript
cancels the event. So the value is always available. If there is nothing to
show, the placeholder gives an empty but correct component (`{"text":""}`). So
the parse operation does not fail.

A head is an Adventure object component. You cannot write it as a MiniMessage
string. Do not put `%headsprites_msg%` in a `formatted "…"` string. Instead,
make the prefix as one component, and the message as a JSON component. Then put
the two components together:

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

### The priority is important

Run your Skript before the `NORMAL` priority. Use `with priority low` or
`lowest`. The built-in `ChatListener` runs at `NORMAL` and changes the message
into rendered components. If your Skript reads `message` or `unformatted
message` after this point, the head tags become empty text. Then your plain-text
lines (Discord or console) lose the tags. Read the raw text before `NORMAL` to
keep the tags. The placeholder `%headsprites_msg%` still gives you the rendered
message for in-game chat.

If you cancel the event, the server does not show the default message. So you do
not get a duplicate line from the built-in renderer.

## Commands

A `/sprite` subcommand needs the `headsprites.admin` permission.

- `/sprite generate <name> <imageUrl> [fallback]` - makes a sprite from an image
  URL with MineSkin, and saves the sprite. The optional fallback text shows on a
  client that cannot show a head.
- `/sprite list` - shows all saved sprites.
- `/sprite anim list` - shows all saved animations with the frame count and the
  frame speed.
- `/sprite anim display <name>` - spawns a text display at your position. The
  plugin refreshes the display on a timer, so the animation moves. A player must
  run this command.
- `/sprite anim stop` - removes all animated text displays that the plugin
  spawned, and stops their timers.
- `/sprite reload` - reads `sprites.yml` again.

Most people use the web editor and not the `generate` command. Use the command
if you already have an image on a web server.

## Permissions

- `headsprites.admin` - use the `/sprite` command. Default: op.
- `headsprites.chatformat` - use full MiniMessage format in chat. Default: op.

## Build

You need Java 21 and Maven. Run this command:

```
mvn clean package
```

The plugin file is `target/HeadSprites-1.1.jar`. This is the shaded JAR file. It
contains all dependencies. Put this file in the `plugins/` folder.

## How it works

- The editor sends your drawing to the plugin as a PNG.
- The plugin scales the drawing into the 8x8 face area on a clear 64x64 skin.
- The plugin sends the skin to MineSkin. MineSkin returns a signed texture value
  and a signature.
- The plugin saves the signed texture in `sprites.yml`. The plugin shows the
  texture in chat as a head object component. So you do not need a resource
  pack.
- An animation is a list of frames. Each frame is one sprite. The plugin saves
  the list and the frame speed in the `animations` section.

## Notes

- A fully clear pixel stays clear. So you can make a shape that is not square.
- MineSkin has rate limits. A wide sequence, or an animation with many frames,
  makes one request for each head. So a large picture needs more time.

## License

Add your license here.
