package io.github.skiesworld.qqbot.examples;

import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.model.request.SendC2CMessageRequest;
import io.github.skiesworld.qqbot.model.request.SendGroupMessageRequest;
import io.github.skiesworld.qqbot.websocket.Intent;

/**
 * 单聊 + 群聊回声：演示被动回复（携带 msg_id）、主动召回消息与好友/推送开关事件。
 *
 * <pre>
 *   QQ_APP_ID=... QQ_APP_SECRET=... ./gradlew runExample -Pexample=EchoBot
 * </pre>
 */
public final class EchoBot {

    private EchoBot() {
    }

    public static void main(String[] args) throws Exception {
        QQBotClient bot = Env.client(Intent.GROUP_AND_C2C_EVENT, Intent.GROUP_MEMBER_EVENT);

        bot.events().on(EventType.C2C_MESSAGE_CREATE, event -> {
            String text = event.rawObject().has("content") ? event.rawObject().get("content").getAsString() : "";
            SendC2CMessageRequest reply = new SendC2CMessageRequest();
            reply.msgType = 0L;
            reply.content = "你说：" + text;
            reply.msgId = event.rawObject().get("id").getAsString();
            reply.msgSeq = 1L;
            String userOpenid = event.conversationId();
            Env.log("c2c reply to %s", userOpenid);
            bot.api().c2c().sendC2CMessage(userOpenid, reply);
        });

        bot.events().on(EventType.GROUP_AT_MESSAGE_CREATE, event -> {
            String groupId = event.rawObject().get("group_openid").getAsString();
            SendGroupMessageRequest reply = new SendGroupMessageRequest();
            reply.msgType = 0L;
            reply.content = "收到";
            reply.msgId = event.rawObject().get("id").getAsString();
            reply.msgSeq = 1L;
            bot.api().group().sendGroupMessage(groupId, reply);
            Env.log("group reply in %s", groupId);
        });

        // 富媒体被动回复：先上传拿 file_info，再用 msg_type=7 发出
        bot.events().on(EventType.GROUP_MESSAGE_CREATE, event -> {
            if (!event.rawObject().has("attachments")) {
                return;
            }
            Env.log("group message with %d attachment(s)",
                    event.rawObject().getAsJsonArray("attachments").size());
        });

        bot.events().on(EventType.FRIEND_ADD, event ->
                Env.log("new friend %s", event.conversationId()));
        bot.events().on(EventType.FRIEND_DEL, event -> Env.log("friend removed %s", event.conversationId()));
        bot.events().on(EventType.C2C_MSG_RECEIVE, event -> Env.log("push enabled by %s", event.conversationId()));
        bot.events().on(EventType.C2C_MSG_REJECT, event -> Env.log("push disabled by %s", event.conversationId()));

        bot.connectAndAwaitReady(20_000);
        Env.log("echo bot online, press Ctrl+C to exit");
        Runtime.getRuntime().addShutdownHook(new Thread(bot::close));
        Thread.currentThread().join();
    }
}
