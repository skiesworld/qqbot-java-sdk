# QQ 官方机器人 Java SDK (API v2)

面向 [QQ 开放平台机器人 API v2](https://bot.q.qq.com/wiki/develop/api-v2/) 的 Java 17 SDK。
单模块、依赖极少（OkHttp + Gson + SLF4J），覆盖官方给出的全部服务端接口与事件：单聊（C2C）、群聊、频道（Guild / 子频道）、富媒体、菜单与指令面板、互动、Webhook 回调。

- JDK：**17+**（以 `--release 17` 编译，可在 JDK 17/21 上构建）
- 构建：Gradle（Kotlin DSL）+ Wrapper，无需本机预装 Gradle
- 传输：OkHttp（REST + WebSocket），序列化：Gson

## 安装

```kotlin
// build.gradle.kts
dependencies {
    // groupId 取决于你在 gradle.properties 里设置的 GROUP，见「发布与 CI」
    implementation("io.github.skiesworld:qqbot-java-sdk:0.0.1")
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
src/main/java/io/github/qqbot/
├── QQBotClient          入口：api() / events() / gateway() / media()
├── BotConfig            appId、密钥、intents、分片、超时与重试
├── api                  按官方模块分组的接口方法 + endpoint/Endpoints 常量
├── auth                 access_token 获取与缓存刷新
├── callback             Webhook：Ed25519 验签、地址校验
├── error                异常与网关关闭码
├── event                EventType / QQEvent / EventBus
├── http                 Endpoint / Params / HttpTransport（鉴权、退避、err_code）
├── media                富媒体分片上传
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
2. **准备签名私钥**，一条无口令或已知口令的 ed25519/RSA 密钥均可：
   ```bash
   gpg --full-generate-key                       # 或导入既有密钥
   gpg --keyid-format short --list-secret-keys   # 记下 8 位 key id
   gpg --export-secret-keys --armor <keyid> > private.asc
   ```
3. **配置仓库 Secrets**（Settings → Secrets and variables → Actions）：

| Secret | 对应 Gradle 属性 | 内容 |
| --- | --- | --- |
| `MAVEN_CENTRAL_USERNAME` | `mavenCentralUsername` | Central Portal user token 的用户名 |
| `MAVEN_CENTRAL_PASSWORD` | `mavenCentralPassword` | 对应 token |
| `SIGNING_KEY` | `signingInMemoryKey` | `private.asc` 全文（含 BEGIN/END 行） |
| `SIGNING_KEY_ID` | `signingInMemoryKeyId` | 8 位 key id（可选） |
| `SIGNING_KEY_PASSWORD` | `signingInMemoryKeyPassword` | 密钥口令，无口令留空 |

本地演练（不上传）可以随时验证 POM 与产物是否齐备：

```bash
./gradlew generatePomFileForMavenPublication   # 生成 build/publications/maven/pom-default.xml
./gradlew assemble plainJavadocJar -PVERSION_NAME=0.0.1-test
# 有密钥但没有 Central 账号时，可发到本地仓库看目录结构：
./gradlew publishToMavenLocal -PVERSION_NAME=0.0.1-test && ls ~/.m2/repository/io/github/skiesworld/
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
| `WebhookServer` | HTTP 回调模式：JDK 自带 HttpServer + 验签 + opcode 13 地址校验 |

`QQ_API_BASE` 可指向沙箱或本地桩，`QQ_DEMO_FILE` 指定 `MediaSendBot` 上传的文件。

## 测试

```bash
./gradlew test           # 111 个离线测试
./gradlew build          # 编译 + 测试 + jar + sources + javadoc
```

测试全部离线（MockWebServer 打桩），无需真实凭据：REST 鉴权头与 `err_code` 语义、429/5xx 退避与 `Retry-After`、401 换证、GET 请求体展开为查询参数、multipart 与预签名分片 PUT、access_token 缓存/边际刷新/单飞、网关 IDENTIFY→READY→心跳→RESUME、op7/op9、4914/4915 致命码停止重连、Webhook 验签与地址校验、事件反序列化与 `Endpoint` 覆盖对账。

## 已知边界

- 事件模型来自官方 autogen 文档；公域/私域差异（如 `GUILD_MESSAGES`、`FORUMS_EVENT` 仅私域机器人可用）由平台在鉴权时校验，SDK 只做透传与订阅位提示。
- 官方「安全和授权」页给出的示例签名值在其自身公开的公钥/seed 下无法验证（文档样例过期），因此签名相关断言使用与独立实现交叉校验的基准值，seed 与公钥仍严格对齐官方示例。
- 频控由平台侧执行；SDK 侧策略是 `Retry-After` + 指数退避 + 401 单次换证，不内置本地令牌桶。
- 官方文档的「小程序」章节（应用子频道开放数据域、`getGuildAndUserinfo` 等）描述的是运行在 QQ 客户端 JS 侧的能力，不属于服务端 OpenAPI，故不在本 SDK 范围内；与之相关的服务端消息类型（Ark / Embed / 模板 Markdown）均已覆盖。
- 文档未给响应体的操作（如 `PUT /interactions/{interaction_id}`）返回 `void`，失败仍以 `ApiException` 抛出。
- 事件模型覆盖官方给出载荷结构的 22 个事件，以及文档写明「内容为 Message / MessageAudited / MessageReaction 对象」的频道事件（`AT_MESSAGE_CREATE`、`MESSAGE_CREATE`、`DIRECT_MESSAGE_CREATE`、`MESSAGE_AUDIT_*`、`MESSAGE_REACTION_*`）。`GUILD_MEMBER_*`、`FORUM_*`、`AUDIO_*`、`MESSAGE_DELETE` 等官方只在 Intents 表里列出名字、未给事件体结构，SDK 仍会投递，请用 `event.raw()` / `onName(...)` 读取，不要假设字段。

## License

Apache-2.0，见 `LICENSE`。Copyright © 2026 SkiesWorld.
