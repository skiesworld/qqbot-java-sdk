package io.github.skiesworld.qqbot.examples;

import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.model.C2CMessageCreate;
import io.github.skiesworld.qqbot.media.FileType;
import io.github.skiesworld.qqbot.media.MediaFile;
import io.github.skiesworld.qqbot.media.MediaTarget;
import io.github.skiesworld.qqbot.model.MediaInfo;
import io.github.skiesworld.qqbot.model.request.SendC2CMessageRequest;
import io.github.skiesworld.qqbot.websocket.Intent;

import java.nio.file.Path;

/**
 * 富媒体演示：把本地文件分片上传后以 msg_type=7 回发；也支持直接传公网 URL 让平台转存。
 *
 * <pre>
 *   QQ_APP_ID=... QQ_APP_SECRET=... QQ_DEMO_FILE=./a.png ./gradlew runExample -Pexample=MediaSendBot
 * </pre>
 */
public final class MediaSendBot {

    private MediaSendBot() {
    }

    public static void main(String[] args) throws Exception {
        String demoFile = System.getenv().getOrDefault("QQ_DEMO_FILE", "");
        QQBotClient bot = Env.client(Intent.GROUP_AND_C2C_EVENT);

        bot.events().on(EventType.C2C_MESSAGE_CREATE, C2CMessageCreate.class, message -> {
            if (demoFile.isEmpty()) {
                Env.log("set QQ_DEMO_FILE to a local image/video/voice/file to enable the demo");
                return;
            }
            MediaFile uploaded = bot.media().uploadFile(MediaTarget.C2C, message.author.userOpenid,
                    Path.of(demoFile), guessType(demoFile));
            SendC2CMessageRequest request = new SendC2CMessageRequest();
            request.msgType = 7L;
            request.media = new MediaInfo();
            request.media.fileInfo = uploaded.fileInfo;
            request.msgId = message.id;
            request.msgSeq = 1L;
            bot.api().c2c().sendC2CMessage(message.author.userOpenid, request);
            Env.log("sent %s (file_info valid %ss)", demoFile, uploaded.ttl);
        });

        bot.connectAndAwaitReady(20_000);
        Env.log("media bot online");
        Runtime.getRuntime().addShutdownHook(new Thread(bot::close));
        Thread.currentThread().join();
    }

    /** 超过软限制时平台会自动降级为文件类型，这里只按扩展名给一个初始分类。 */
    static FileType guessType(String fileName) {
        String name = fileName.toLowerCase();
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".gif")) {
            return FileType.IMAGE;
        }
        if (name.endsWith(".mp4")) {
            return FileType.VIDEO;
        }
        if (name.endsWith(".silk") || name.endsWith(".wav") || name.endsWith(".mp3")) {
            return FileType.VOICE;
        }
        return FileType.FILE;
    }
}
