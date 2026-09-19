# QQ 官方机器人 Java SDK (API v2)

面向 [QQ 开放平台机器人 API v2](https://bot.q.qq.com/wiki/develop/api-v2/) 的 Java 17 SDK。
单模块、依赖极少（OkHttp + Gson + SLF4J），覆盖官方给出的全部服务端接口与事件：单聊（C2C）、群聊、频道（Guild / 子频道）、富媒体、菜单与指令面板、互动、Webhook 回调。

- JDK：**17+**（以 `--release 17` 编译，可在 JDK 17/21 上构建）
- 构建：Gradle（Kotlin DSL）+ Wrapper，无需本机预装 Gradle
- 传输：OkHttp（REST + WebSocket），序列化：Gson
- 组织方式：`@BotEvent` handler（可选 `ServiceLoader` 自动发现）、`MessageSegments` 读取、`@Command` 命令匹配

## 安装

```kotlin
// build.gradle.kts
dependencies {
    // groupId 取决于你在 gradle.properties 里设置的 GROUP，见「发布与 CI」
    implementation("io.github.skiesworld:qqbot-java-sdk:0.0.2")
}
```

尚未发布到 Maven Central 时，可直接用本地构建产物或 JitPack。

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

`connect()` 之后不需要任何轮询：网关收 `HELLO` → 发 `IDENTIFY` → `READY`，掉线后带 `session_id` + `seq` 发 `RESUME` 补收漏掉的事件。

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
        .build();

QQBotClient bot = QQBotClient.create(config);
```

鉴权细节：`POST {apiBase}/app/getAppAccessToken`，请求头 `Authorization: QQBot {access_token}`。
`access_token` 有效期约 7200 秒，SDK 缓存并在到期前 60 秒自动刷新；收到 401 时会作废缓存并重新获取一次。

## 三种接收事件的方式

传输有三条（网关、进程内监听、Webhook），业务代码怎么组织是另一件事：见 [用注解组织机器人逻辑](#用注解组织机器人逻辑)。

### 1. WebSocket 网关（默认）

```java
Gateway gateway = bot.gateway();
gateway.addListener(new Gateway.Listener() {
    public void onReady(String sessionId, JsonElement user) { /* 上线成功 */ }
    public void onResumed() { /* 断线后补收事件完成 */ }
    public void onStateChange(Gateway.State from, Gateway.State to) { /* 连接状态 */ }
    public void onError(Throwable t) { /* 网关异常，含 4xxx 关闭码 */ }
});
bot.connect();
```

网关关闭码语义已内建处理：`4009/4008` 走 Resume，`4006/4007/49xx` 重新 Identify，`4914/4915`（下架/封禁）判定为致命错误并停止重连。

### 2. 事件监听

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
event.id();     // 事件 id，被动回复时作为 event_id 传回
event.seq();    // 网关序列号 s
event.name();   // t
event.raw();    // d（JsonElement）
event.data();   // 生成的事件模型，例如 C2CMessageCreate
event.targetId();  // group_openid / user_openid / channel_id / author 中可用的会话对象
```

### 3. HTTP 回调（Webhook）

把任意 HTTP 框架接到 `WebhookHandler`，它负责 Ed25519 验签、地址校验（opcode 13）与事件分发，返回值即响应体：

```java
WebhookHandler handler = new WebhookHandler(botSecret, appid, bot.events());

String responseBody = handler.handle(rawBody,
        request.header("X-Signature-Timestamp"),
        request.header("X-Signature-Ed25519"),
        request.header("X-Bot-Appid"));
```

验签规则与官方一致：以 Bot Secret 重复填充出 32 字节 seed 派生 Ed25519 密钥，签名体为 `timestamp + body`。验签失败抛 `SignatureException`，请务必当作拒绝处理。

## 用注解组织机器人逻辑

监听器适合一两个事件，机器人长大了更好用的是「一个类，一个方法一个事件」：

```java
@BotHandlers("chat")
public class ChatHandlers implements BotHandler {

    @BotEvent(EventType.C2C_MESSAGE_CREATE)
    public void onPrivate(C2CMessageCreate msg, QQEvent raw) { }      // payload + 信封

    @BotEvent({EventType.FRIEND_ADD, EventType.FRIEND_DEL})
    public void onFriendToggle(QQEvent raw, Api api) { }             // 一个方法多个事件

    @BotEvent(name = "GROUP_SOMETHING_NEW")                            // 尚未建模的事件名
    public void onFuture(JsonObject body) { }
}

bot.handlers().register(new ChatHandlers());     // 反射装配，编译期不需要任何处理器
bot.events().register(new ChatHandlers());       // 同样可用，只是没有 client 可注入
```

参数按类型注入：`QQEvent`、`EventType`、`JsonObject`/`JsonElement`、`Api`、`QQBotClient`，其余引用类型一律当 payload 反序列化。绑定不了的（基本类型、数组、没有 client 却声明 `Api`）在 **注册时** 就抛 `IllegalArgumentException`，不会等到第一条消息才暴露；payload 缺失或对不上号时跳过这次调用并打 debug 日志，用户代码的异常也永远到不了网关线程。

想让别人写的 handler 自动被找到，就让它实现 `BotHandler`（无方法的标记接口）并带上 `@BotHandlers`，由本 SDK 自带的注解处理器生成 `META-INF/services` 清单，运行时 `bot.handlers().registerDiscovered()` 用 `ServiceLoader` 装配——不在运行时扫 classpath，因此 MC 模组那种嵌套 classloader/shade 环境下同样可预期。JDK 21+ 需在消费者构建里显式开启注解处理（`-proc:full` 或配置 `--processor-path`）；不启用处理器时，上面那两行 register 完全够用。

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

### 命令匹配与角色

命令层就是「事件监听 + 文本匹配」，所以 handler 能同时拿到 payload 与匹配结果：

```java
@Command(value = {"签到", "checkin"}, description = "每日签到")
public void checkIn(CommandContext ctx) {
    ctx.reply("已签到 " + ctx.args());                     // 回到来源会话，msg_seq 自动递增
}

@Command(value = "mute (\\S+) (\\d+)", kind = Command.Kind.REGEX, role = Role.ADMIN)
public void mute(CommandContext ctx) {
    mute(ctx.groups().get(0), Long.parseLong(ctx.groups().get(1)));   // 捕获组按 0 起下标
}

bot.commands().usePrefixes("/", "").register(new AdminCommands());
bot.commands().describe();                                   // 给 /help 用的一行一条
```

默认不需要前缀（群消息本就必须 @ 机器人，且平台已把该 mention 从 `content` 中剥掉），`usePrefixes("/")` 之后没带前缀的消息不再匹配。`role` 比较的是群里 `author.member_role`（member < admin < owner）；单聊与私信不报角色，那里角色门不起作用——只按角色限制群命令，别指望它在私聊里挡住谁。只匹配 `content`，纯图片/卡片消息不会触发命令，用 `CommandContext#segments()` 读它们。

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
} catch (ApiException e) {
    e.errCode();     // 业务错误码，判错只认它，不要认 message 文案
    e.httpStatus();  // HTTP 状态码
    e.traceId();     // 找官方排查时提供
    e.rawBody();     // 原始响应
    if (HttpTransport.auditPending(e)) {
        // 304023 / 304024：消息进入人工审核，属异步成功
    }
}
```

异常层级：`QQBotException` → `ApiException` / `AuthException` / `WsException`（含 4xxx 关闭码与是否可 Resume 判定）/ `SignatureException`。

## 目录结构

```
src/main/java/io/github/skiesworld/qqbot/
├── QQBotClient          入口：api() / events() / handlers() / commands() / gateway() / media()
├── BotConfig            appId、密钥、intents、分片、超时与重试
├── api                  按官方模块分组的接口方法 + endpoint/Endpoints 常量
├── auth                 access_token 获取与缓存刷新
├── callback             Webhook：Ed25519 验签、地址校验
├── command              @Command / CommandContext / CommandRegistry / Role
├── error                异常与网关关闭码
├── event                EventType / QQEvent / EventBus
├── handler              @BotHandlers / @BotEvent / HandlerRegistry + 可选注解处理器
├── http                 Endpoint / Params / HttpTransport（鉴权、退避、err_code）
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
python tools/docgen/gen_java.py   # 依 spec.json + naming.json 生成 model / api 层 Java 源码
```

生成文件头部带有 `Generated by tools/docgen/gen_java.py` 标记，改动请回到生成器与 `naming.json`，不要手改。

`naming.json` 固化了「官方中文接口名 → Java 方法名」的映射，新增接口时会显式报错，避免自动生成出难用的名字。
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
| `HandlerBot` | 注解 handler（按类型注入参数）、消息段读取、`@Command` 命令与角色限制 |
| `WebhookServer` | HTTP 回调模式：JDK 自带 HttpServer + 验签 + opcode 13 地址校验 |

`QQ_API_BASE` 可指向沙箱或本地桩，`QQ_DEMO_FILE` 指定 `MediaSendBot` 上传的文件。

## 测试

```bash
./gradlew test           # 164 个离线测试
./gradlew build          # 编译 + 测试 + jar + sources + javadoc
```

测试全部离线（MockWebServer 打桩），无需真实凭据：REST 鉴权头与 `err_code` 语义、429/5xx 退避与 `Retry-After`、401 换证、GET 请求体展开为查询参数、multipart 与预签名分片 PUT、access_token 缓存/边际刷新/单飞、网关 IDENTIFY→READY→心跳→RESUME、op7/op9、4914/4915 致命码停止重连、Webhook 验签与地址校验、事件反序列化与 `Endpoint` 覆盖对账、注解 handler 的参数绑定与注册期报错、消息段解析与三种出站降级、各场景回复路径与 `msg_seq` 递增、命令前缀/别名/正则捕获组与角色门。注解处理器用 `ToolProvider.getSystemJavaCompiler()` 现场编译样例源码，断言生成的 `META-INF/services` 清单能被 `ServiceLoader` 读回并真正派发事件。

## 已知边界

- 事件模型来自官方 autogen 文档；公域/私域差异（如 `GUILD_MESSAGES`、`FORUMS_EVENT` 仅私域机器人可用）由平台在鉴权时校验，SDK 只做透传与订阅位提示。
- 官方「安全和授权」页给出的示例签名值在其自身公开的公钥/seed 下无法验证（文档样例过期），因此签名相关断言使用与独立实现交叉校验的基准值，seed 与公钥仍严格对齐官方示例。
- 频控由平台侧执行；SDK 侧策略是 `Retry-After` + 指数退避 + 401 单次换证，不内置本地令牌桶。
- 官方文档的「小程序」章节（应用子频道开放数据域、`getGuildAndUserinfo` 等）描述的是运行在 QQ 客户端 JS 侧的能力，不属于服务端 OpenAPI，故不在本 SDK 范围内；与之相关的服务端消息类型（Ark / Embed / 模板 Markdown）均已覆盖。
- 文档未给响应体的操作（如 `PUT /interactions/{interaction_id}`）返回 `void`，失败仍以 `ApiException` 抛出。
- 收到的消息里文本与附件是平级字段，`content` 内没有占位符或偏移，因此 `MessageSegments` 只能给出「文本 + 提及 + 附件 + 卡片」的字段分组，不会假装还原气泡内的排布；子频道/私信发送体也没有 `msg_type` 与 `msg_seq` 字段，富媒体与键盘在该场景不可用（`toChannel()` 会明确拒绝）。
- `Role` 只能依据群场景上报的 `author.member_role`；单聊、私信与官方未给 `member_role` 的事件视为「无角色」，角色门在那里不生效。
- 事件模型覆盖官方给出载荷结构的 22 个事件，以及文档写明「内容为 Message / MessageAudited / MessageReaction 对象」的频道事件（`AT_MESSAGE_CREATE`、`MESSAGE_CREATE`、`DIRECT_MESSAGE_CREATE`、`MESSAGE_AUDIT_*`、`MESSAGE_REACTION_*`）。`GUILD_MEMBER_*`、`FORUM_*`、`AUDIO_*`、`MESSAGE_DELETE` 等官方只在 Intents 表里列出名字、未给事件体结构，SDK 仍会投递，请用 `event.raw()` / `onName(...)` 读取，不要假设字段。

## License

Apache-2.0，见 `LICENSE`。Copyright © 2026 SkiesWorld.
