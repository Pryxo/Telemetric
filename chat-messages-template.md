# Telemetric chat messages — editable template

Edit anything below and send the file back. Not committed / not read by the mod — it's a spec for the agent.
Pre-filled in **Melinoe's style** (taken 1:1 from Melinoe 1.2.0 sources + 1.3.1 jar), with Telemetric names.

---------------------------------------------------------------------------------------------------------
## 1. Syntax (what you can write)

| Write | Means |
|---|---|
| `<#RRGGBB>text</#>` | colour (hex). `</#>` closes the last colour |
| `<c:name>text</c>` | colour from the palette in section 2 (e.g. `<c:error>`) |
| `<gradient:#A:#B:#C>text</gradient>` | gradient over the text (2+ stops) |
| `<shiny>text</shiny>` | animated shiny text (our Chat Colors effect) |
| `<bold> <italic> <underline> <st> <obf>` … `</bold>` etc. | styles |
| `<reset>` | drop all colours/styles |
| `<hover:text>…</hover>` | tooltip when hovering the wrapped part (may contain tags, `\n` = new line) |
| `<click:run:/cmd>…</click>` / `<click:suggest:/cmd>…</click>` / `<click:url:https://…>…</click>` / `<click:copy:text>…</click>` | click actions |
| `<center>…</center>` | centre the line in chat (Melinoe has this) |
| `<break>` | a full-width strikethrough line (Melinoe's "chat break") |
| `<prefix>` | the watermark from section 3 |
| `\` before `<` | literal `<` (e.g. `\<status>` shows `<status>`) |

### Placeholders (filled in by the mod)
| Placeholder | Value |
|---|---|
| `<mod_name>` | Telemetric |
| `<current_version>` | e.g. 0.40.0 |
| `<latest_version>` | newest release (only if we add an update check) |
| `<root>` / `<alias>` | `/telemetric` / `/tm` |
| `<player>` | your name |
| `<status>` | head status display name |
| `<count>` / `<total>` | numbers (statuses loaded, tables downloaded / total) |
| `<overridden>` / `<failed>` | tables from local overrides / failed tables |
| `<error>` | short error text |
| `<boss>` / `<caller>` | Boss Tracker: boss name / player who called it |
| `<item>` `<item_sprite>` `<rarity_glyph>` `<pity>` `<loot_boost>` `<lb_base>` `<lb_contrib>` | Pity drop line |
| `<text>` | free text (callouts etc.) |

---------------------------------------------------------------------------------------------------------
## 2. Palette (used as `<c:name>`)

```
text      = #AAAAAA     # Melinoe TEXT
muted     = #808080     # Melinoe MUTED
separator = #606060     # Melinoe SEPARATOR (the › after the name)
command   = #FFD700     # Melinoe COMMAND (gold command names)
success   = #00FF00
error     = #FF3333
warning   = #FFFF00
info      = #55FFFF
highlight = #FFFF55     # values inside sentences (names, numbers)
```
Optional: `palette = theme` → take these from the active ClickGUI theme instead of fixed hex.

---------------------------------------------------------------------------------------------------------
## 3. Watermark / prefix + chat tag

```
prefix      = <bold><gradient:#B8FFE1:#7CFFB2:#2E8F78><mod_name></gradient></bold> <bold><c:separator>›</c></bold><reset>
# Melinoe: "Melinoe ›" in a teal→green bold gradient, gray bold ›

tag.color   = #B8FFE1                         # the coloured bar on the left of the chat line
tag.hover   = Message from <mod_name>          # tooltip when hovering that bar
tag.log     = Telemetric                       # name in the log
```
Every message below starts with `<prefix> ` automatically unless it says `raw:`.

Shortcut message types (like Melinoe's `Message.success/error/warning/info`):
```
success = <c:success><text>
error   = <c:error><text>
warning = <c:warning><text>
info    = <c:info><text>
```

---------------------------------------------------------------------------------------------------------
## 4. /telemetric (help) — sent as ONE message (Melinoe sends the help as one block)

```
help.header = <c:text>Command Help: <c:muted>(v<current_version>)
help.line   = <c:muted><bold>› </bold><reset><c:command><cmd> <c:muted>- <reset><c:text><desc>
#              ^ one per command; <cmd> and <desc> come from the list below
help.footer =                                   # empty = none
```
Commands (`<cmd>` | `<desc>`):
```
<root><c:muted>, <c:command><alias> | Shows this help
<root> update-cache                | Downloads and reloads all shared data
<root> status get                  | Shows your head status
<root> status set \<status>         | Sets your head status (Tab lists your top 25)
<root> status clear                | Removes your head status
<root> status refresh              | Reloads the status list
```
`/telemetric status` alone → same help, only the status lines:
```
status.help.header = <c:text>Status Commands:
```

---------------------------------------------------------------------------------------------------------
## 5. Command replies

```
# /telemetric status get
status.no_account   = error:   No signed-in account.
status.get.failed   = error:   Could not get your status <c:muted>(<error>)
status.get.none     = <c:text>You have no head status.
status.get.ok       = <c:text>Your head status: <status>          # <status> keeps its own rank colours

# /telemetric status set <status>
status.not_loaded   = warning: Status list not loaded yet. Try <click:run:/telemetric status refresh><c:command><underline>/telemetric status refresh</underline></click>
status.unknown      = error:   "<status>" is not a status. <c:muted>Use Tab to see the list.
status.top25.failed = error:   Could not load your top 25 <c:muted>(<error>)
status.not_top25    = error:   You are not top 25 in <c:highlight><status><c:error>. <c:muted>Use Tab to see yours.
status.set.failed   = error:   Could not set your status <c:muted>(<error>)
status.set.ok       = success: Status set to <c:highlight><status><c:success>.

# /telemetric status clear
status.clear.failed = error:   Could not remove your status <c:muted>(<error>)
status.clear.ok     = success: Head status removed.

# /telemetric status refresh
status.refresh.start  = <c:muted>Loading the status list…
status.refresh.failed = error:   Status list failed <c:muted>(<error>)
status.refresh.ok     = success: <c:highlight><count><c:success> statuses loaded.

# /telemetric update-cache
cache.running   = warning: Data update already running…
cache.start     = <c:muted>Updating shared data…
cache.failed    = error:   Data update failed <c:muted>(see the log)
cache.ok        = success: <c:highlight><count>/<total><c:success> tables updated and reloaded.
cache.partial   = warning: <c:highlight><count>/<total><c:warning> tables updated<c:muted>, <overridden> from local overrides<c:warning>, <c:error><failed> failed <c:muted>(kept old data)
#                 ", <overridden> from local overrides" is left out when 0

# unknown sub-command / wrong usage (new)
cmd.unknown     = error:   Unknown command. <click:run:/telemetric><c:command><underline>/telemetric</underline></click><c:error> for help.
```

---------------------------------------------------------------------------------------------------------
## 6. Module messages (currently plain coloured lines without prefix)

```
# Visual → Boss Tracker (teleport keybind)
boss.no_target  = error:   Look at a boss waypoint someone is fighting to teleport.
boss.is_you     = error:   You are the one fighting <c:highlight><bold><boss></bold>
boss.teleport   = <c:text>Teleporting to <c:highlight><underline><caller></underline><c:text> at <c:highlight><bold><boss></bold>

# HUD → Pity Counter (drop line) — raw: keeps its own tag (glyph) instead of the watermark
raw: pity.drop  = <rarity_glyph> <c:muted>- <c:text>Dropped <item_sprite> <item> <c:text>at <c:highlight><pity><c:text> pity from <boss><hover:<c:text>Loot Boost\n<c:highlight>Base: +<lb_base>%\n<c:highlight>Contribution: +<lb_contrib>%><c:highlight> [+<loot_boost>% LB]</hover> <hover:<c:text>Click to share in chat!><click:suggest:<text>><c:text><bold>⧉</bold></click></hover>

# Social → Callouts
callout         = <c:text><text>
```

---------------------------------------------------------------------------------------------------------
## 7. Your extra ideas
(Add new messages / commands here, e.g. a join message `Telemetric v<current_version> loaded`, update notice, …)
