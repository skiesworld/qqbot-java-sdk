package io.github.skiesworld.qqbot.examples;

import io.github.skiesworld.qqbot.BotConfig;
import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.callback.WebhookServer;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.handler.BotEvent;
import io.github.skiesworld.qqbot.message.MessageSegments;
import io.github.skiesworld.qqbot.websocket.Intent;

/**
 * HTTP 回调模式：入口形态是配置项（{@link BotConfig.Transport#WEBHOOK}），验签、地址校验、回 ACK 都在 SDK 里，
 * 业务代码与网关模式一行都不差——事件同样落进 {@code bot.events()}。
 *
 * <p>多个 bot 共用一个监听端口：每个 bot 的路由默认是 {@code /qq/{appId}}，往同一个 {@link WebhookServer} 上
 * mount 即可，各 bot 的验签密钥与总线互相独立，A 的签名在 B 的路由上只会得到 401。
 *
 * <pre>
 *   QQ_APP_ID=... QQ_APP_SECRET=... ./gradlew runExample -Pexample=WebhookBot
 *   # 平台侧回调地址：http(s)://你的公网域名:8080/qq/&lt;AppID&gt;   （端口只认 80/443/8080/8443）
 * </pre>
 *
 * <p>只有一个 bot 也可以偷懒：把 {@link BotConfig.Builder#webhook(int, String)} 写进配置，然后
 * {@code bot.start()}，端点由 SDK 自己起、随 {@code bot.close()} 关。已经有 Web 框架的话谁都不用起：把
 * {@code bot.webhook()} 挂到你自己的路由上，拿它的返回值当响应体。
 */
public final class WebhookBot {

    private WebhookBot() {
    }

    public static void main(String[] args) throws Exception {
        QQBotClient first = bot("QQ_APP_ID", "QQ_APP_SECRET");
        QQBotClient second = System.getenv("QQ_APP_ID_2") == null ? null
                : bot("QQ_APP_ID_2", "QQ_APP_SECRET_2");

        WebhookServer endpoint = new WebhookServer("0.0.0.0", 8080).start();
        endpoint.mount(first);
        if (second != null) {
            endpoint.mount(second);
        }
        Env.log("serving routes %s on http://0.0.0.0:%d", endpoint.paths(), endpoint.port());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            endpoint.close();
            first.close();
            if (second != null) {
                second.close();
            }
        }));
        Thread.currentThread().join();
    }

    private static QQBotClient bot(String appIdEnv, String secretEnv) {
        String appId = Env.required(appIdEnv);
        String secret = Env.required(secretEnv);
        String botSecret = System.getenv("QQ_BOT_SECRET");

        BotConfig config = BotConfig.builder(appId)
                .clientSecret(secret)
                // 回调验签用的 Bot Secret 通常就是 AppSecret；不同才需要单独给
                .botSecret(botSecret == null || botSecret.isBlank() ? secret : botSecret)
                .intents(Intent.GROUP_AND_C2C_EVENT)
                .transport(BotConfig.Transport.WEBHOOK)
                .build();

        QQBotClient bot = QQBotClient.create(config);
        bot.handlers().register(new Callbacks());
        bot.events().on(EventType.C2C_MESSAGE_CREATE, event -> Env.log("[{}] c2c {}: {}", appId,
                event.targetId(), MessageSegments.of(event).text()));
        return bot;
    }

    /** 与 HandlerBot 里的写法完全相同：换传输不换业务代码。 */
    public static class Callbacks {

        @BotEvent(EventType.GROUP_AT_MESSAGE_CREATE)
        public void onGroup(io.github.skiesworld.qqbot.event.QQEvent event) {
            Env.log("group %s: %s", event.targetId(), MessageSegments.of(event).text());
        }
    }
}
