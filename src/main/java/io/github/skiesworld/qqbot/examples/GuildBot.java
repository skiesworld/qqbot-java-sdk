package io.github.skiesworld.qqbot.examples;

import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.model.MessageMarkdown;
import io.github.skiesworld.qqbot.model.request.SendChannelMessageRequest;
import io.github.skiesworld.qqbot.websocket.Intent;

/**
 * 频道（Guild）侧演示：AT 消息回复、Markdown、表情表态、置顶，以及上线后列出频道与子频道。
 *
 * <pre>
 *   QQ_APP_ID=... QQ_APP_SECRET=... ./gradlew runExample -Pexample=GuildBot
 * </pre>
 */
public final class GuildBot {

    private GuildBot() {
    }

    public static void main(String[] args) throws Exception {
        QQBotClient bot = Env.client(Intent.PUBLIC_GUILD_MESSAGES, Intent.GUILDS,
                Intent.GUILD_MESSAGE_REACTIONS);

        bot.events().on(EventType.AT_MESSAGE_CREATE, event -> {
            String channelId = event.rawObject().get("channel_id").getAsString();
            String messageId = event.rawObject().get("id").getAsString();

            SendChannelMessageRequest reply = new SendChannelMessageRequest();
            reply.markdown = new MessageMarkdown();
            reply.markdown.content = "**已收到** <@" + event.rawObject().getAsJsonObject("author")
                    .get("id").getAsString() + ">";
            reply.msgId = messageId;
            bot.api().channelMessages().sendChannelMessage(channelId, reply);

            bot.api().channelMessages().addMessageReaction(channelId, messageId, "1", "7706");
            bot.api().channelContent().pinMessage(channelId, messageId);
            Env.log("answered, reacted and pinned in channel %s", channelId);
        });

        bot.events().on(EventType.MESSAGE_REACTION_ADD, event ->
                Env.log("reaction added on %s",
                        event.rawObject().getAsJsonObject("target").get("id").getAsString()));
        bot.events().on(EventType.GUILD_CREATE, event ->
                Env.log("bot added to guild %s", event.rawObject().get("id").getAsString()));

        bot.connectAndAwaitReady(20_000);
        bot.api().me().listBotGuilds(null, null, 5L).guilds.forEach(guild ->
                bot.api().channel().listChannels(guild.id).channels.forEach(channel ->
                        Env.log("%s / %s (type %s, sub %s)", guild.name, channel.name, channel.type,
                                channel.subType)));

        Runtime.getRuntime().addShutdownHook(new Thread(bot::close));
        Thread.currentThread().join();
    }
}
