# Release Notes

## v2.4.15

**修复**

- 服务端聊天历史下发不再能把进服玩家踢下线：历史包遇到损坏条目改为跳过该行，下发或配置同步失败只在服务端记日志，进服流程不受影响（Forge / NeoForge / Fabric）；历史缓冲改加锁访问，混服与异步聊天桥不再能把半截快照交给编码器。

**更改**

- 玩家进服收到的最近 50 条不再因为本地已有记录而被整包丢弃：现在按发送者与内容逐条比对后合并进本地历史（同一人重复说的话按次数抵消），位置落在本地历史之后、本次进服提示之前，不会把已经在屏幕上的消息顶掉或重复显示。

**说明**

- `history_enabled` 仍默认关闭，服务端需在 `serverconfig/e33chat-server.toml` 或 `/e33chat gui` 打开才会下发。
- 下发内容来自服务端内存缓冲，不跨重启，且只收录真实玩家发言（服务端代发的消息、插件广播不进历史）。

----

**Fixed**

- Joining players can no longer be kicked ("Invalid player data") by the server-side chat-history delivery: a damaged row is now skipped instead of aborting the packet, a failed delivery is logged on the server and never touches the login flow, and the history buffer is lock-guarded so hybrid servers and async chat bridges can no longer hand the encoder a half-built snapshot (Forge / NeoForge / Fabric).

**Changed**

- The 50-message backlog sent on join is merged instead of dropped: lines the player already has are matched by sender and text, repeats cancel out by count, and the rest land between the restored local history and this session's join notice, so nothing already on screen is displaced or shown twice.

**Notes**

- `history_enabled` still defaults to false, so a server has to turn it on in `serverconfig/e33chat-server.toml` or via `/e33chat gui`.
- The backlog lives in memory only: it does not survive a restart and it contains player chat alone, so plugin and server-issued messages are not part of it.

## v2.4.14

**修复**

- 服务端系统提示不再被误认成玩家消息——EasyBot 冒号形态（`[标签] 昵称：内容`）此前只对标签做精确匹配，`[玩家系统] 请使用以下命令登录: /log <密码>` 被当成名为「请使用以下命令登录」的玩家气泡；现在系统系标签（含 系统/公告/服务器/广播/提示/通知 等词，或结尾为 插件/助手）与 `/` 开头的命令内容一律不认领，角度括号形态行为不变。空 sender 的 chat/disguised 包也不再被认领成无名气泡。名字前的分隔符守卫不再误伤括号内装饰：`[Lv.10|VIP] Steve: hello`、`【Lv.10|VIP】Steve » hello`、`[世界] [Lv.10|VIP] Steve: hello` 恢复正常归属，`系统>>Steve`、`VIP|Steve`、`[系统|公告]Steve` 等广播仿冒保持拒绝。

**改进**

- 服务端精确模板也接入无 sender 的 disguised 通道，命中即归属；模板日志按来源打 `System(template)` 或 `Disguised(template)` 标签，排查时一眼能看出消息走的哪条通道。

----

**Fixed**

- server system prompts are no longer claimed as player messages. The EasyBot colon shape (`[label] nick: content`) only exact-matched its label, so `[玩家系统] 请使用以下命令登录: /log <密码>` became a bubble sent by 「请使用以下命令登录」; system-domain labels (containing 系统/公告/服务器/广播/提示/通知 or ending in 插件/助手) and `/`-prefixed command content are now rejected, while the angle-bracket shape is unchanged. Blank-sender chat and disguised packets are no longer claimed as nameless bubbles either, and the pre-name separator guard no longer misfires on separators inside balanced decorations: `[Lv.10|VIP] Steve: hello`, `【Lv.10|VIP】Steve » hello` and `[世界] [Lv.10|VIP] Steve: hello` parse again, while `系统>>Steve`, `VIP|Steve` and `[系统|公告]Steve` stay rejected. Server-declared exact templates now also apply to senderless disguised lines, and template logs carry a System/Disguised source tag.

## v2.4.13

全量代码审计后的加固版本（P0 级问题为零，修复覆盖全部 P1/P2）。

**修复**

- 恶意数据包不再能让对端无限分配内存（网络解码器全线加计数/长度上限，超出直接拒收）、NeoForge 端连发服务器中转 JPEG 不再双倍消耗下载额度（2.4.12 修复漏掉的一端）、Fabric 切换主题不再永久丢失面板背景图与取景、Fabric 模板命令保存后不再悄悄关掉服务器托管图片、超过 200 个群时目录数据错位、大屏幕（GUI 缩放 1）下侧栏背景下半截消失、看过的动图纹理永久占用显存（缓存改为有上限并随断线释放）、清空历史的两击确认永不过期（时钟错位）、弹层关闭后立刻重开会被旧计时器误关、横幅头像淡入比文字更暗、表情面板右侧空白处点击误发下一个表情、带频道前缀的普通聊天丢失样式前缀。

**加固**

- 群组数据改为原子写入并在失败时告知玩家、损坏的配置文件保留备份而不是被覆盖、单机切换世界不再沿用上一个世界的服务端配置与聊天残留、侧栏通配符屏蔽（如 Islot_*）在 Forge/NeoForge 上从未生效、Fabric 上含方括号的屏蔽规则导致崩溃——三端现已共享同一实现。
- 开发：TemplateMatcher 收编进共享层、钉等守卫从 10 条扩到 18 条、新增协议恶意输入/群组校验/表情网格等 6 个测试类（三端 446/416/447 全绿）。

----

**Fixed**

- a full-code audit pass that closed every P1 and P2 finding with zero P0s. Hostile packets can no longer force unbounded memory allocations (every network decoder now caps counts and lengths and rejects the packet instead of silently clamping), NeoForge no longer burns double the download quota on server-hosted JPEGs (the one end the 2.4.12 fix missed), switching themes on Fabric keeps the custom panel background image, opacity and crop, the /e33chat template command no longer silently disables server media hosting after saving, the group directory no longer desyncs above 200 groups, the sidebar background no longer vanishes below y=999 on large GUIs, viewed animations are cached with an LRU and released on disconnect instead of living forever in VRAM, the clear-history double-click confirm now actually expires (a clock-domain mixup), popups reopened within 150ms of closing are no longer killed by the stale close timer, banner avatars fade in step with their text instead of darker, emoji-grid clicks in the right-hand padding band no longer send the wrong emote, and labelled player chat keeps its styled channel/title prefixes when the EasyBot colon shape claims it.

**Hardened**

- group data saves atomically and tells the player when it fails, corrupt config files are kept as backups instead of being overwritten, singleplayer world switches no longer inherit the previous world's server config or chat backlog, the sidebar wildcard hide patterns (e.g. Islot_*) that never worked on Forge/NeoForge and crashed Fabric on brackets now share one correct implementation across all three platforms. Development: TemplateMatcher moved into the shared layer, the pinned-equality guard grew from 10 to 18 entries, and six new test classes cover hostile decoder input, group validation and the emoji grid (446/416/447 tests green across Forge/Fabric/NeoForge).

## v2.4.12

**修复**

- 发出的 GIF 不会动（动图改为原字节直传，超限明确提示而不是静默变静态）、QQ 群转发整行变灰字（解析器接受「[标签] 名字：内容」形状，在线玩家保留头像）、1.21.1 两端空输入按 Tab 后无法输入、打开聊天面板时物品栏 HUD 消失（聊天面板不再隐藏 HUD）、自己发的图片随机“加载失败”（JPEG 不再白发探测 + 自己上传的本地缓存 + NeoForge/Fabric 补上去重修复 + 服务端限流 4→16）、壁纸取景界面一片空白。

**新增**

- 自定义面板背景图的取景编辑器——锁定面板宽高比的选取框，拖动平移、滚轮缩放，改面板宽度/窗口大小自动适配。

**更改**

- 动图上限对齐 AtomChat——帧数 48→120、改用 8M 像素单图预算裁帧而不是拒绝；表情包上限 10→32；表情面板里的 GIF 改为静态缩略图 + GIF 角标（发到聊天里照常动）。

**调试**

- 指令补全列表无法鼠标点击——本版加入临时诊断日志，开 `debug_log` 复现一次即可定位，修复留待下一版。

----

**Fixed**

- sent GIFs losing their animation (animated sources now pass through as raw bytes, with a clear toast instead of a silent still image when over the limits), QQ group relays rendering as grey system text (the parser accepts the "[tag] name: content" shape and keeps online players' avatars), Tab on an empty chat input locking all typing on both 1.21.1 loaders, the hotbar HUD vanishing while the chat panel was open (the panel no longer hides the HUD), our own uploaded images randomly "failing to load" (no wasted probes on JPEGs, a local cache of own uploads, the de-duplication fix finally landed on NeoForge and Fabric, server rate limit 4 -> 16), and the blank background-framing screen.

**Added**

- a framing editor for the custom panel background (a selection box locked to the panel's aspect ratio; drag to pan, scroll to zoom, re-fits automatically when the panel width changes).

**Changed**

- animation limits aligned with AtomChat (120 frames with an 8M-pixel decoded budget that trims instead of rejecting), custom emote cap raised from 10 to 32, still GIF thumbnails with a badge in the emote panel (sent messages keep animating).

**Debug**

- temporary diagnostics for the suggestion list not responding to mouse clicks (enable debug_log and reproduce once; the fix lands next version).

## v2.4.11

**新增**

- 聊天群组（mod 内置服务端路由 + 页签管理）、自定义面板背景图、GIF/WebP 动图消息、玩家资料卡。

**修复**

- 打开聊天面板崩溃、面板打开时 HUD 遮挡第一人称手部、服务器图片连发被限流、群组弹层点击闪烁、中文群名发送失败、名字误加下划线与点击事件。

----

**Added**

- in-mod chat groups (in-mod server routing with tab management), custom panel background, animated GIF/WebP messages, player profile card.

**Fixed**

- chat-open crash, HUD hiding the first-person hand while the panel is open, rate-limited server images, group popup flicker, CJK group-name send failures, stray name underlines.

