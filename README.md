# QQ 官方机器人 Java SDK (API v2)

面向 [QQ 开放平台机器人 API v2](https://bot.q.qq.com/wiki/develop/api-v2/) 的 Java 17 SDK。
单模块、依赖极少（OkHttp + Gson + SLF4J），覆盖官方给出的全部服务端接口与事件：单聊（C2C）、群聊、频道（Guild / 子频道）、富媒体、菜单与指令面板、互动、Webhook 回调。

- JDK：**17+**（以 `--release 17` 编译，可在 JDK 17/21 上构建）
- 构建：Gradle（Kotlin DSL）+ Wrapper，无需本机预装 Gradle
- 传输：OkHttp（REST + WebSocket），序列化：Gson
- 组织方式：`@On` 路由（参数类型就是事件集合）、事件信封、命令与门禁、`Bots` 多账号注册表

## 安装

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.skiesworld:qqbot-java-sdk:0.0.3")
}
```

`0.0.4` 起是本文其余部分描述的 API：`@BotEvent` + `@Command` 合成一个 `@On`，事件多了信封类型，bot 管理进了 `Bots`。0.0.3 的坐标不会被撤回，旧 API 在 dev 分支上已不存在。

从源码构建：

```bash
./gradlew build          # 编译 + 全部测试 + jar
./gradlew test           # 只跑测试
```

## 30 秒上手

```java
import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.event.EventType;

try (QQBotClient bot = QQBotClient.create("你的AppID", "你的AppSecret")) {
    bot.events().on(EventType.C2C_MESSAGE_CREATE, event -> {
        C2CMessageCreate msg = event.data();               // 生成的事件模型
        SendC2CMessageRequest reply = new SendC2CMessageRequest();
        reply.msgType = 0L;                                 // 0=纯文本
        reply.content = "你说的是：" + msg.content;
        reply.msgId = msg.id;                               // 被动回复：5 分钟内、每条最多 4 次
        bot.api().c2c().sendC2CMessage(msg.author.userOpenid, reply);
    });
    bot.connect();          // 非阻塞，SDK 负责鉴权、心跳、断线 Resume
    Thread.currentThread().join();
}
```

## 配置

```java
BotConfig config = BotConfig.builder("AppID")
        .clientSecret("AppSecret")            // 用 appId+secret 自动换取 access_token
        // .accessToken("已有的 token")        // 或者自带 token，SDK 不再请求凭证接口
        .intents(Intent.GROUP_AND_C2C_EVENT,  // 只订阅需要的事件大类，无权限的位会导致连接被拒
                 Intent.PUBLIC_GUILD_MESSAGES)
        .shard(0, 1)                          // 分片：shard_id = (guild_id >> 22) % num_shards
        .apiBase("https://api.bot.qq.com")    // 沙箱/代理环境改这里
        // .wsUrl("wss://.../websocket")      // 指定网关地址，跳过 GET /gateway/bot
        .maxRetries(3)                        // 429/5xx/IO 失败重试次数（指数退避 + Retry-After）
        .tokenRefreshMargin(Duration.ofSeconds(60))
        // .transport(BotConfig.Transport.WEBHOOK)          // 不连网关，改成平台回调
        // .webhook(8080, null)                             // 回调模式 + 自己起端点（单 bot 偷懒写法）
        // .webhookHost("127.0.0.1").botSecret("回调验签用的 Bot Secret")   // 验签密钥默认沿用 clientSecret
        .build();

QQBotClient bot = QQBotClient.create(config);
```

`access_token` 由 SDK 缓存并自动刷新，401 时作废重取一次。

### 传输：网关或回调

默认走网关，`bot.connect()` 就够了。要平台回调，换个配置项，业务代码一行不改：

```java
BotConfig config = BotConfig.builder(appId).clientSecret(secret)
        .transport(BotConfig.Transport.WEBHOOK)   // 每个 bot 的回调路由默认是 /qq/{appId}
        .build();
```

回调地址是按 bot 登记的，所以多个 bot 共用一个端口：

```java
try (WebhookServer endpoint = new WebhookServer("0.0.0.0", 8080).start()) {
    endpoint.mount(botA).mount(botB);     // /qq/<A> 与 /qq/<B>，各自的验签密钥与总线互不相干
}
```

只有一个 bot 时把端口写进配置（`.webhook(8080, null)`），端点随 `bot.start()` 起、随 `bot.close()` 关，`bot.webhookServer().port()` 给出实际端口（配 0 就是系统分配的）。已经有 Web 框架就两个都不用起，拿返回值当响应体：

```java
String body = bot.webhook().handle(rawBody, timestamp, signature, appid);
```

### 多个 bot：`Bots`

一个进程跑几个账号时，注册表负责查找与生命周期，回调 socket 也只有一个：

```java
Bots bots = new Bots().webhookEndpoint("0.0.0.0", 8080);   // 不写这行就各起各的端口
bots.register(botA, botB).startAll();

bots.get(appId);              // Optional<QQBotClient>，按 app id 找
bots.getBot();                // 单 bot 进程的简写；0 个或多个时直接报错
bots.getBots();               // 注册顺序；要给每个 bot 做同一个管理动作就自己 for 一遍
bots.isOnline(appId);         // 是否已经在线，与「是否注册」是两个问题
bots.startAll();              // 按各自的 transport 拉起
bots.close();                 // 逆序关掉每个 bot，再释放共享端点
```

同一个 app id 只接受一次——两个 bot 会把同一批事件各答一遍。挂在共享端点上的 bot 被 `close()` 时只摘掉自己那条路由，端口留给别人；注册表关的时候才真正释放 socket。这里故意**不提供**「一条消息发给所有 bot / 所有会话」的便捷方法：`getBots()` 已经在手上了，少一个看起来很顺手、但语义上等于「不查对象就把喇叭对准全部人」的入口。`READY` / `RESUMED` 也在总线上（`@On(EventType.READY)`），「上线后拉一次全量」这类逻辑因此可以写成事件处理器。

网关状态想监听就挂 `Gateway.Listener`（`onReady` / `onResumed` / `onStateChange` / `onError`）；心跳、断线 Resume 与 `4xxx` 关闭码的重连判定都在 SDK 里。

## 监听事件

```java
EventBus bus = bot.events();
bus.on(EventType.GROUP_AT_MESSAGE_CREATE, e -> { });             // 原始事件
bus.on(EventType.C2C_MESSAGE_CREATE, C2CMessageCreate.class,
        d -> System.out.println(d.content));                     // 强类型
bus.onName("SOME_NEW_EVENT", e -> e.raw());                      // 尚未建模的事件名
bus.onAny(e -> log(e.name(), e.seq()));
EventBus.Subscription sub = bus.on(EventType.FRIEND_ADD, e -> { });
sub.close();                                                     // 取消订阅
```

`QQEvent` 保留信封与原始数据，因此官方新增字段无需等 SDK 升级：

```java
event.id();            // 事件 id，被动回复时作为 event_id 传回
event.seq();           // 网关序列号 s
event.name();          // t
event.raw();           // d（JsonElement）
event.data();          // 生成的事件模型，例如 C2CMessageCreate
event.conversationId();// group_openid / user_openid / channel_id / guild_id / author 中可用的会话对象
event.scene();         // 这个会话是哪一种（ReplyTarget），决定回复走哪个接口
```

### 事件信封：参数类型就是「哪些事件」

按路由表，一个 dispatch 会被包成三种形态之一，方法声明哪种参数就只收那类事件：

| 信封 | 覆盖 | 提供 |
| --- | --- | --- |
| `QQMessageEvent` | 6 个带 `content` 的消息事件 | `content()` / `author()` / `senderId()` / `segments()` / `reply(...)` |
| `QQNoticeEvent` | 其余所有 intent 事件（含互动、麦位、审核结论） | `actor()` / `subject()`（`Optional<String>`），人也可能没有 |
| `GroupJoinRequestEvent` / `InteractionEvent` | 各自那一个「欠一个回答」的事件 | `approve()` / `deny(reason)`、`acknowledge()` |
| `QQEvent` | 上面之外（`READY`、`RESUMED`、官方新增的名字） | 只有信封字段与 `raw()` |

`reply()` 与 `approve()` 走的是这个 bot 自己的凭证——信封在构造时拿到了出站句柄，所以 handler 不必再传一遍 `bot`。`actor()` / `subject()` 只报 openid 空间的人：`CHANNEL_CREATE` 的 `owner_id` 是老的数值 id，混进门禁白名单会静默判否，所以它不出现在这两个方法里，要读就用 `data()` 的具名字段。

### 注解 handler

一个类、一个方法一个事件，比一串 lambda 好维护：

```java
@BotHandlers("chat")
public class ChatHandlers implements BotHandler {

    @On                                    // 参数没写事件 → 从 QQMessageEvent 推出「所有消息事件」
    public void onAnyMessage(QQMessageEvent msg) { msg.reply("收到 " + msg.content()); }

    @On({EventType.FRIEND_ADD, EventType.FRIEND_DEL})
    public void onFriendToggle(QQEvent raw, Api api) { }             // 一个方法多个事件

    @On(command = {"签到", "checkin"})                                // 命令也是一种路由
    public void checkIn(OnContext ctx) { ctx.reply("已签到 " + ctx.args()); }

    @On(name = "GROUP_SOMETHING_NEW")                                // 尚未建模的事件名
    public void onFuture(JsonObject body) { }
}

bot.handlers().register(new ChatHandlers());      // 就这一行
```

参数按声明的类型逐个填：`QQEvent` 与其信封、`OnContext`、`EventType`、`JsonObject`/`JsonElement`、`Api`、`QQBotClient`，其余引用类型按 payload 反序列化——并且只接受**该事件的模型类或其父类**，声明一个字段名碰巧对得上的自造类会被跳过（那是「payload 对不上」，不是「字段少了」）。绑不上的（基本类型、数组、没有 client 却声明 `Api`）在注册时就抛 `IllegalArgumentException`；同理，`@On(EventType.FRIEND_ADD)` 却接 `QQMessageEvent` 也会当场报错——那种方法永远不会运行。

跨 jar 提供的 handler 用 `bot.handlers().registerDiscovered()`：类实现 `BotHandler` 并带上 `@BotHandlers`，本 SDK 自带的注解处理器在编译期生成 `META-INF/services` 清单（JDK 21+ 要显式开启注解处理：`-proc:full` 或 `--processor-path`）。没有清单也不影响上面的 `register(...)`。

自己应用里的对象也想按类型注入，就把它挂进同一个绑定器，不需要容器：`bot.handlers().bind(Player.class, event -> roster.playerOf(event))`——返回 null 等于「这条 dispatch 不该走这个方法」，跳过并记 debug 日志。信封、`OnContext` 这些引擎自己填的类型不允许被替换。

### 消息段与出站构造

`MessageSegments` 把收到的消息读成段序列，`MessageBuilder` 把要发的内容降级到各场景真正支持的字段：

```java
MessageSegments msg = MessageSegments.of(event);
msg.text();                                   // content 原文
msg.segmentsOfType(Segment.Media.class);       // 图片/视频/语音/文件，按 content_type 定型
msg.card();                                   // ark_data（message_type=3）
msg.elements();                               // msg_elements（103 引用、102 聊天记录），可递归

bot.api().c2c().sendC2CMessage(openid, MessageBuilder.of("你好").replyTo(event).seq(1L).toC2C());
bot.api().group().sendGroupMessage(groupOpenid, MessageBuilder.create().media(fileInfo).replyTo(event)
        .seq(2L).toGroup());                  // msg_type=7
bot.api().channelMessages().sendChannelMessage(channelId, MessageBuilder.create().ark(card).toChannel());
```

要清楚两件事：官方 payload 里文本与附件是**平级字段**，`content` 中没有任何占位符或偏移能告诉你图片原本插在句子的哪里，所以段序列是字段的分组而非气泡的复原；子频道/私信的发送体没有 `msg_type`（图片走 `image` URL、卡片走 `ark`），把富媒体 `file_info` 或 `keyboard` 交给 `toChannel()` 会直接抛异常，而不是发一个平台必拒的请求。没被 builder 覆盖的字段（`embed`、`input_notify`、模板 markdown）仍可改返回对象的 public 字段。

### 命令与门禁

命令就是「事件路由 + 文本匹配」写在同一行，所以方法同时拿到匹配结果与它所属的消息：

```java
@On(command = {"签到", "checkin"}, description = "每日签到")
public void checkIn(OnContext ctx) {
    ctx.reply("已签到 " + ctx.args());                     // 回到来源会话，msg_seq 自动递增
}

@On(command = "mute (\\S+) (\\d+)", kind = On.Kind.REGEX,
        requires = {Permissions.Group.class, Permissions.GroupAdmin.class})
public void mute(OnContext ctx) {
    mute(ctx.groups().get(0), Long.parseLong(ctx.groups().get(1)));   // 捕获组按 0 起下标
}

bot.handlers().usePrefixes("/", "").register(new AdminCommands());
bot.handlers().describe();                                   // 给 /help 用的一行一条
```

默认不需要前缀（群消息本就必须 @ 机器人，且平台已把该 mention 从 `content` 中剥掉），`usePrefixes("/")` 之后没带前缀的消息不再匹配；单个命令可以用 `prefix = "!"` 钉住自己的前缀。只匹配 `content`，纯图片/卡片消息不会触发命令，用 `OnContext#segments()` 读它们。命令只能落在带 `content` 的事件上：`@On(command = "x", value = EventType.FRIEND_ADD)` 在注册期就被拒，那种命令永远匹配不上；想限定「只在群里」就写 `value = EventType.GROUP_AT_MESSAGE_CREATE`。

`OnContext` 只给命令该有的东西：`command()` / `text()` / `rest()` / `args()` / `groups()` / `message()`（就是那条 `QQMessageEvent`）/ `reply()` / `api()`。声明了 `OnContext` 却没写 `command` 的方法在注册期报错，不会给你一个字段全空的上下文。

门禁对命令和普通事件方法都适用，一条注解写完，名字和类型可以混用；声明顺序即判定顺序，第一个拒绝就短路。

```java
@On(command = "清档") @Check({"groupAdmin", "superUser"})
public void wipe(OnContext ctx) { ... }

@On(command = "重置", requires = Permissions.ToMe.class)          // requires 与 @Check 可以并用来点名规则
public void reset(OnContext ctx) { ... }

@Check                                  // 门禁本体：返回 boolean，参数照旧按需声明
boolean groupAdmin(QQMessageEvent msg) {
    return "admin".equalsIgnoreCase(msg.author().memberRole);
}
```

- 字符串 = 本类或父类里的 `@Check` 方法；类型 = 可复用规则。SDK 内置 `Permissions.Group/Private/Channel/Direct/GroupAdmin/GroupOwner/ToMe`，以及要带配置的 `Permissions.scene(...)`、`Permissions.senderIn(ids)`——后者这类没有无参构造的规则，用 `bot.handlers().permission(MyRule.class, () -> new MyRule(ids))` 注册后即可在 `requires` 里点名。
- 规则类也可以只写一个 `boolean check(...)`，参数与 handler 一样按类型注入（`check(QQNoticeEvent notice)` 就只会被通知事件问到）；没有这个方法就用 `allows(event, bot)`，lambda 走的是后者。
- 门禁在**参数绑定之后**才被问：命令文本没匹配上时根本不会走到它。判定为假、门禁抛异常、参数绑不上，一律按「拒绝」处理并记日志——判不出来就不能放行。
- 名字找不到、方法不返回 `boolean`、同名重载、类型无法实例化，都在注册期抛 `IllegalArgumentException`；被路由的方法必须返回 `void`（要返回判定就标 `@Check`）。
- 角色只存在于群里 `author.member_role`（member < admin < owner）：`Permissions.GroupAdmin` 在单聊与通知事件上返回 false，因为那里没有角色可 honour。

## 并发与线程

监听器**默认同步**跑在入口线程上：网关事件跑在 OkHttp 的 WebSocket 回调线程，回调事件跑在 `WebhookServer` 那 4 个线程之一。一个阻塞的监听者会占住它身后的整条入口——同一条连接上的后续事件排队等它，网关侧连 `HELLO`/`RESUME` 的回包也要延后（心跳是独立线程，照发，但收不到回应就可能触发重连）；回调侧则是响应变慢，平台按超时重试，你会看到同一个 `msg_id` 被推好几次。

想异步就换个 `EventBus`，总线自己不做线程决策：

```java
ExecutorService pool = Executors.newFixedThreadPool(16);
QQBotClient bot = new QQBotClient(config, new HttpTransport(config), new EventBus(pool::execute));
```

给进去之后总线的分工是固定的：一个 dispatch 先在**它所属会话**的轨道上排队（同群的消息按到达顺序被处理，`msg_seq` 不会乱），链路上的每条路由再各自落到 **(路由 × 会话)** 的轨道上。于是慢的那个 handler 只拖它自己在这个群里的前一次调用——同一个事件上的另一条路由不会被它挡住，别的群也不会。JDK 21 上把 `pool` 换成 `Executors.newVirtualThreadPerTaskExecutor()` 就是每调用一个虚拟线程；SDK 只调 `Executor.execute`，不关心它是哪种线程，所以 17 上照旧用平台线程池即可。

两条例外要记住：

- `priority` 决定同一条链上的先后，而 `block = true` 的路由会**内联**跑在会话轨道上并结束这条链——它要的是次序，所以不再并出去。默认 `block = false`，即全部并发。
- 无论同步异步，用户代码抛出的异常都不会中断分发、也不影响别的路由，只记 error 日志；一条路由没处理（参数绑不上、门禁拒绝、文本没匹配上）不算处理过，`block` 因此不会替它刹车。

## 接口调用

`bot.api()` 下按官方模块分组，覆盖文档中的 **94 个操作**（含同一路径上的全员禁言与批量成员禁言两个操作）：

| 分组 | 覆盖范围 |
| --- | --- |
| `me()` | 机器人资料、机器人所在频道列表 |
| `c2c()` | 单聊消息、流式消息（思考态）、撤回、富媒体上传与分片上传 |
| `group()` | 群消息、群成员、入群申请与自动审批策略、群禁言、群黑名单、群资料 |
| `guild()` | 频道资料、频道成员查询与移除、全员禁言、批量成员禁言、单成员禁言、发言设置、API 权限查询与申请 |
| `channel()` | 子频道的查询/创建/修改/删除、在线人数 |
| `channelMessages()` | 子频道消息、私信（DMS）、表情表态 |
| `channelContent()` | 置顶、公告、定时日程、论坛帖子、语音麦序与控制 |
| `channelPermissions()` | 身份组增删改查、身份组成员、成员/身份组的子频道权限位图 |
| `menu()` / `panels()` | 全局自定义菜单、指令面板 |
| `interactions()` | 互动事件响应 |
| `gateway()` | 通用/分片 WSS 接入点 |

请求体是公开字段的 Gson DTO（字段名由 `@SerializedName` 绑定官方 wire 名称），直接赋值即可：

```java
// 群聊被动回复
GroupMessageCreate msg = event.data();
SendGroupMessageRequest reply = new SendGroupMessageRequest();
reply.content = "收到";
reply.msgId = msg.id;
reply.msgSeq = 1L;
bot.api().group().sendGroupMessage(msg.groupOpenid, reply);

// 主动消息（互动召回，遵循平台的周期限制）
SendC2CMessageRequest push = new SendC2CMessageRequest();
push.content = "你好";
push.msgType = 0L;
push.isWakeup = true;
bot.api().c2c().sendC2CMessage(userOpenid, push);

// 先上传富媒体，再用 file_info 发 msg_type=7 的消息
MediaFile uploaded = bot.media().uploadFromUrl(MediaTarget.C2C, userOpenid, FileType.IMAGE,
        "https://example.com/a.png");
SendC2CMessageRequest withMedia = new SendC2CMessageRequest();
withMedia.msgType = 7L;
withMedia.media = new MediaInfo();
withMedia.media.fileInfo = uploaded.fileInfo;
bot.api().c2c().sendC2CMessage(userOpenid, withMedia);
```

游标分页接口（群成员、入群申请、黑名单）返回 `next_cursor`，把它回传给下一次调用即可；为空表示已到末页。

官方新增或本版本尚未封装的接口，可直接用逃生舱：

```java
bot.execute(Endpoint.of(Endpoint.Method.GET, "/users/@me", io.github.skiesworld.qqbot.model.User.class),
        Params.of(), null);
```

## 富媒体上传

单聊与群聊的上传通道互相隔离（`/v2/users/{openid}/files` 与 `/v2/groups/{openid}/files`）。
`MediaUploader` 实现了官方四步分片流程：`upload_prepare` → 按 `block_size` 切片 PUT 到预签名 URL → 每片 `upload_part_finish` → 合并拿到 `file_info`。
分片并发、`retry_timeout`、`retry_delay` 都取自服务端下发的 `upload_config`；`prepare` 返回空 `parts` 时走秒传直达合并步骤。

```java
MediaFile f = bot.media().uploadFile(MediaTarget.GROUP, groupOpenid, Path.of("clip.mp4"), FileType.VIDEO);
// f.fileInfo 用于 msg_type=7 的消息；f.ttl 为该凭证有效期（秒）
```

## 错误处理

```java
try {
    bot.api().c2c().sendC2CMessage(openid, request);
} catch (AuditPendingException e) {       // 消息进了人工审核，属异步成功：还没投递，但会投递
    bot.audits().resultOf(e.auditId(), Duration.ofMinutes(5))
       .thenAccept(o -> log.info("审核结论 {} -> {}", o.auditId(), o.status()));
} catch (ApiException e) {
    e.errCode();            // 业务错误码，判错只认它，不要认 message 文案
    e.httpStatus();         // HTTP 状态码
    e.traceId();            // 找官方排查时提供
    e.rawBody();            // 原始响应
    e.isAuditPending();     // 304023 / 304024，或响应里带 audit_id
    e.auditId();            // 上面的 audit_id，没有则 null
}
```

审核结论走的是 `MESSAGE_AUDIT_PASS` / `MESSAGE_AUDIT_REJECT` 两个事件，`bot.audits()` 把「等某个 audit_id 的结论」变成 `CompletableFuture`：平台不保证期限，所以超时的答案是 `AuditStatus.TIMED_OUT` 而不是异常。

异常层级：`QQBotException` → `ApiException`（含 `AuditPendingException`）/ `AuthException` / `WsException`（含 4xxx 关闭码与是否可 Resume 判定）/ `SignatureException`。

## 目录结构

```
src/main/java/io/github/skiesworld/qqbot/
├── QQBotClient          入口：start() / api() / events() / handlers() / audits() / gateway() / webhook()
├── Bots                 多账号注册表：get / getBot / getBots / isOnline / startAll / close
├── BotConfig            appId、密钥、intents、分片、超时与重试、传输方式（网关 or 回调）
├── api                  按官方模块分组的接口方法 + endpoint/Endpoints 常量
├── audit                审核结论：Audits / AuditOutcome / AuditStatus
├── auth                 access_token 获取与缓存刷新
├── callback             HTTP 回调：WebhookHandler（验签/地址校验/入总线）+ WebhookServer（一个端口挂多个 bot）
├── error                异常与网关关闭码
├── event                EventType / QQEvent 与信封 / EventRouting（生成）/ EventEnvelopes / EventBus
├── handler              @On / OnContext / @Check / Permission(s) + HandlerRegistry + 可选注解处理器
├── http                 Endpoint / Params / HttpTransport（鉴权、退避、err_code、审核中）
├── media                富媒体分片上传
├── message              MessageSegments / Segment / MessageBuilder / ReplyTarget / ReplySequence
├── model                官方数据结构（生成）+ model/request 请求体 + model/constant 取值表
├── examples             可运行示例（不依赖额外日志/框架，见下节）
├── util                 Json、Strings、Digests
└── websocket            Gateway、Intent、OpCode、GatewayPayload
```

## 发布与 CI

工程已接好 Gradle 9.7.1（wrapper 自带，无需本机装 Gradle）与 `com.vanniktech.maven.publish` 0.37.0，
两条 GitHub Actions 工作流在 `.github/workflows/`：

- `ci.yml`：PR / main 推送时校验 wrapper 签名，并在 **JDK 17 与 21** 上 `clean build`；
  另有 `codegen` 作业重跑 `tools/docgen/gen_java.py`，生成结果与提交不一致就报错（防止手改生成码）。
- `release.yml`：打 tag 即发布 —— `git tag v0.0.1 && git push origin v0.0.1`。
  它会校验 tag 是合法语义化版本、校验发布坐标、跑 `publishAndReleaseToMavenCentral`（上传 → 等 Central Portal
  校验通过 → 自动 release），最后用 `gh release create` 建 Release 并附上 jar / sources / javadoc；
  带 `-rcN` 后缀的 tag 会自动标记为 prerelease。

首次发布前要做的三件事：

1. **命名空间**。当前坐标是 `io.github.skiesworld:qqbot-java-sdk`（组织 `skiesworld` 拥有该命名空间，
   子组如 `io.github.skiesworld.xxx` 也自动可用）。在 <https://central.sonatype.com> 的 Namespaces 里
   给组织安装 "Sonatype Nexus" GitHub App 完成验证（拥有 skiesworld.dev 之类域名时也可用 DNS TXT），
   然后生成 **user token**。release 作业会核对：`GROUP` 的 GitHub 账号 == 仓库 owner、
   `POM_URL` == `https://github.com/<POM_GITHUB_REPOSITORY>`、且 `gradle.properties` 里没有 `TODO` 残留。
2. **准备签名密钥**。用 RSA 4096（`RSA (sign only)`），口令可选：

   ```bash
   gpg --full-generate-key                                  # 邮箱要与 Central 账号一致
   gpg --send-keys <完整指纹>                               # 或到 keys.openpgp.org 网页上传公钥
   gpg --armor --export <keyid> > public.asc                # 上传公钥用
   gpg --pinentry-mode loopback --export-secret-keys --armor <keyid> > private.asc
   ```

   公钥**必须**在公网可取：Central Portal 只用可获取到的公钥校验随产物上传的 `.asc`，取不到就是签名校验失败。
   用 keys.openpgp.org 时要点掉它发的邮箱确认链接，否则 user id 不公开。私钥文本只进 Secrets，别提交进仓库。
3. **配置仓库 Secrets**（Settings → Secrets and variables → Actions）：

| Secret | 对应 Gradle 属性 | 内容 |
| --- | --- | --- |
| `MAVEN_CENTRAL_USERNAME` | `mavenCentralUsername` | Central Portal user token 的用户名 |
| `MAVEN_CENTRAL_PASSWORD` | `mavenCentralPassword` | 对应 token |
| `MAVEN_GPG_PRIVATE_KEY` | `signingInMemoryKey` | `gpg --export-secret-keys --armor <id>` 全文（含 BEGIN/END 行） |
| `MAVEN_GPG_PASSPHRASE` | `signingInMemoryKeyPassword` | 密钥口令，无口令留空 |

本地演练可以随时验证 POM 与产物是否齐备：

```bash
./gradlew generatePomFileForMavenPublication assemble plainJavadocJar -PVERSION_NAME=0.0.1-check
cat build/publications/maven/pom-default.xml      # name/description/url/licenses/scm/developers 都要在
```

签名不能被跳过：publication 里已经登记了 `.asc` 构件，`-x signMavenPublication` 会让
`publishToMavenLocal` 反过来报 "artifact file does not exist: ...jar.asc"。所以要本地走一遍上传，
就把私钥临时放进 `~/.gradle/gradle.properties`（**不是**项目里），并且用一个一次性版本号——
Central 的版本号不可复用也不可撤回：

```properties
# ~/.gradle/gradle.properties
signingInMemoryKey=-----BEGIN PGP PRIVATE KEY BLOCK-----\n...\n-----END PGP PRIVATE KEY BLOCK-----
signingInMemoryKeyId=<完整指纹>
signingInMemoryKeyPassword=<口令，无口令留空>
mavenCentralUsername=<user token 用户名>
mavenCentralPassword=<user token 密码>
```

```bash
./gradlew publishToMavenLocal -PVERSION_NAME=0.0.0-localcheck                      # 只签名+落本地
./gradlew publishToMavenCentral -PVERSION_NAME=0.0.0-centralcheck \
  -PmavenCentralAutomaticPublishing=false --no-configuration-cache                 # 上传+校验但不 release
```

版本号只来自 tag：工作流用 `-PVERSION_NAME=${tag#v}` 覆盖 `gradle.properties` 里的值。

## 与官方文档同步（代码生成）

接口与事件的模型、方法都由官方文档推导而来，流程保留在 `tools/docgen/`：

```bash
python tools/docgen/crawl.py      # 抓取 /wiki/develop/api-v2/ 全站（169 页）→ tools/docgen/site（已 gitignore）
python tools/docgen/parse2.py     # 解析 → spec.json（149 条接口记录 / 22 个事件 / 138 个类型 / 示例报文 fixtures）
python tools/docgen/gen_java.py   # 依 spec.json + naming.json 生成 model / api / event/EventRouting 源码
```

生成文件头部带有 `Generated by tools/docgen/gen_java.py` 标记，改动请回到生成器与 `naming.json`，不要手改。

`naming.json` 固化了两件机器推不出来的事：「官方中文接口名 → Java 方法名」的映射，以及每个事件的**路由行**（`eventRoles`：这条事件包成哪种信封、`actor` / `subject` 分别是哪些 payload 键）。新增接口或事件时若缺行会显式报错，避免自动生成出难用的名字或静默少一类事件；`EventRoutingTest` 拿这张表与 `EventType` 双向对账。
`ApiCoverageTest` 从两个方向对账：每个带路径参数的操作是否有 `Endpoints` 常量，以及同一路径上的操作**数量**是否一致（防止「全员禁言 / 批量成员禁言」这类共用路径的操作被合并掉）。`OfficialEventFixtureTest` 再用官方示例报文逐个回放事件模型。
`OfficialEventFixtureTest` 用官方文档中的示例报文回放每个事件，字段名或结构漂移会当场暴露。

## 可运行示例

示例只需要凭据环境变量，不引入任何额外框架：

```bash
export QQ_APP_ID=... QQ_APP_SECRET=...      # 或 QQ_ACCESS_TOKEN=... 自带凭证
./gradlew runExample -Pexample=EchoBot
```

| 示例 | 演示内容 |
| --- | --- |
| `EchoBot` | 单聊/群 @ 被动回复（`msg_id`+`msg_seq`）、好友与推送开关事件 |
| `GroupAdminBot` | 入群申请审批、成员限时禁言、撤回消息、游标分页拉取申请列表 |
| `GuildBot` | 频道 AT 消息 Markdown 回复、表情表态、置顶、列出频道与子频道 |
| `StreamingBot` | 流式回复：首片由服务端返回 `stream_msg_id`，`index` 递增，`input_state=10` 收尾 |
| `MediaSendBot` | 本地文件分片上传 → `file_info` → `msg_type=7` 发送 |
| `HandlerBot` | `@On` 路由与信封、命令（词/正则/前缀/优先级/block）、`@Check` 与 `requires` 门禁、入群申请与按钮的自应答 |
| `WebhookBot` | 回调模式：`transport(WEBHOOK)` 让 SDK 起端点，同一套 handler 代码不改；自带 Web 框架时改挂 `bot.webhook()` |

`QQ_API_BASE` 可指向沙箱或本地桩，`QQ_DEMO_FILE` 指定 `MediaSendBot` 上传的文件。

## 测试

```bash
./gradlew test           # 237 个离线测试
./gradlew build          # 编译 + 测试 + jar + sources + javadoc
```

测试全部离线（MockWebServer 打桩），无需真实凭据：REST 鉴权头与 `err_code` 语义、429/5xx 退避与 `Retry-After`、401 换证、GET 请求体展开为查询参数、multipart 与预签名分片 PUT、无响应体操作的 `Void` 解码、access_token 缓存/边际刷新/单飞、网关 IDENTIFY→READY→心跳→RESUME、op7/op9、4914/4915 致命码停止重连、Webhook 验签与地址校验、事件反序列化与 `Endpoint` 覆盖对账、路由表的穷举性与角色键、信封的取值与自应答（回复、审批、互动 ack）、`@On` 的事件推断与注册期报错、自定义参数类型的注入与「返回 null 即跳过」、消息段解析与三种出站降级、各场景回复路径与 `msg_seq` 递增、命令前缀/别名/正则捕获组/优先级/block、门禁的名字与类型两条路、审核结论的等待与超时、`Bots` 的查找/重复 id/共享端点、回调端点的真实 socket 往返（验签、opcode 13、405/400/401 与 ACK）。注解处理器用 `ToolProvider.getSystemJavaCompiler()` 现场编译样例源码，断言生成的 `META-INF/services` 清单能被 `ServiceLoader` 读回并真正派发事件。

## 已知边界

- 事件模型来自官方 autogen 文档；公域/私域差异（如 `GUILD_MESSAGES`、`FORUMS_EVENT` 仅私域机器人可用）由平台在鉴权时校验，SDK 只做透传与订阅位提示。
- 官方「安全和授权」页给出的示例签名值在其自身公开的公钥/seed 下无法验证（文档样例过期），因此签名相关断言使用与独立实现交叉校验的基准值，seed 与公钥仍严格对齐官方示例。
- 频控由平台侧执行；SDK 侧策略是 `Retry-After` + 指数退避 + 401 单次换证，不内置本地令牌桶。
- 官方文档的「小程序」章节（应用子频道开放数据域、`getGuildAndUserinfo` 等）描述的是运行在 QQ 客户端 JS 侧的能力，不属于服务端 OpenAPI，故不在本 SDK 范围内；与之相关的服务端消息类型（Ark / Embed / 模板 Markdown）均已覆盖。
- 文档未给响应体的操作（如 `PUT /interactions/{interaction_id}`）返回 `void`，失败仍以 `ApiException` 抛出。
- 收到的消息里文本与附件是平级字段，`content` 内没有占位符或偏移，因此 `MessageSegments` 只能给出「文本 + 提及 + 附件 + 卡片」的字段分组，不会假装还原气泡内的排布；子频道/私信发送体也没有 `msg_type` 与 `msg_seq` 字段，富媒体与键盘在该场景不可用（`toChannel()` 会明确拒绝）。
- 内置回调端点只说 HTTP：平台的回调地址只接受 80/443/8080/8443，要 HTTPS 请让反向代理终结 TLS 后转发到本端点（或直接挂你自己的 Web 框架，用 `bot.webhook()`）。回调路径必须与后台登记的一致，多 bot 共用一个端口时记得每条路由分别是 `/qq/{appId}`。
- `QQMessageEvent` 之外没有「发送者」这一个概念：通知事件的 payload 用六七个不同键名指人（`openid`、`op_member_openid`、`member_openid`、`invited_by`…），而且 `GUILD_*` / `CHANNEL_*` / `MESSAGE_REACTION_*` 报的是老的数值 id，所以 `actor()` / `subject()` 对这些事件返回空，需要的人请从 `data()` 的具名字段读，别把两种 id 混进同一张白名单。
- 事件模型覆盖官方给出载荷结构的 22 个事件，以及文档写明「内容为 Message / MessageAudited / MessageReaction 对象」的频道事件（`AT_MESSAGE_CREATE`、`MESSAGE_CREATE`、`DIRECT_MESSAGE_CREATE`、`MESSAGE_AUDIT_*`、`MESSAGE_REACTION_*`）。`GUILD_MEMBER_*`、`FORUM_*`、`AUDIO_*`、`MESSAGE_DELETE` 等官方只在 Intents 表里列出名字、未给事件体结构，SDK 仍会投递（信封与 `conversationId()` 照常工作），`data()` 返回 null，请用 `event.raw()` / `onName(...)` 读取，不要假设字段。要补齐这些模型，是再抓一次文档页的事，不是手写。
- payload 参数只接受该事件自己的模型类（或其父类）：字段名碰巧对得上的自造类会被跳过并记 debug 日志，而不是静默填出一个半空的对象。
- 路由表（`eventRoles`）为 `EventType` 里每个名字都写了一行，`EventRoutingTest` 双向对账；官方新增名字时，缺行会在 CI 里暴露，而不是让那条事件静默落进「不是消息也不是通知」。

## License

Apache-2.0，见 `LICENSE`。Copyright © 2026 SkiesWorld.
