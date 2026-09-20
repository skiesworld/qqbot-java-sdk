package io.github.skiesworld.qqbot.examples;

import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.model.SendStreamMessageResponse;
import io.github.skiesworld.qqbot.model.request.SendC2CStreamMessageRequest;
import io.github.skiesworld.qqbot.websocket.Intent;

import java.util.List;

/**
 * 流式回复（打字机效果）：首片不带 stream_msg_id，由服务端在响应的 {@code id} 中返回，
 * 之后每片携带上一片的 id、index 递增、input_state=1，最后一片 input_state=10 结束。
 *
 * <pre>
 *   QQ_APP_ID=... QQ_APP_SECRET=... ./gradlew runExample -Pexample=StreamingBot
 * </pre>
 */
public final class StreamingBot {

    private static final long STATE_GENERATING = 1L;
    private static final long STATE_FINISHED = 10L;

    private StreamingBot() {
    }

    public static void main(String[] args) throws Exception {
        QQBotClient bot = Env.client(Intent.GROUP_AND_C2C_EVENT);

        bot.events().on(EventType.C2C_MESSAGE_CREATE, event -> {
            String userOpenid = event.conversationId();
            String msgId = event.rawObject().get("id").getAsString();
            replyInChunks(bot, userOpenid, msgId, List.of("正在", "思考", "……已完成"));
        });

        bot.connectAndAwaitReady(20_000);
        Env.log("streaming bot online");
        Runtime.getRuntime().addShutdownHook(new Thread(bot::close));
        Thread.currentThread().join();
    }

    static void replyInChunks(QQBotClient bot, String userOpenid, String msgId, List<String> chunks) {
        String streamMsgId = null;
        for (int index = 0; index < chunks.size(); index++) {
            boolean last = index == chunks.size() - 1;
            SendC2CStreamMessageRequest request = new SendC2CStreamMessageRequest();
            request.inputMode = "append";
            request.inputState = last ? STATE_FINISHED : STATE_GENERATING;
            request.index = (long) index;
            request.contentRaw = chunks.get(index);
            request.msgId = msgId;
            request.streamMsgId = streamMsgId;

            SendStreamMessageResponse response = bot.api().c2c().sendC2CStreamMessage(userOpenid, request);
            if (index == 0 && response != null) {
                streamMsgId = response.id;
                Env.log("stream started as %s", streamMsgId);
            }
        }
    }
}
