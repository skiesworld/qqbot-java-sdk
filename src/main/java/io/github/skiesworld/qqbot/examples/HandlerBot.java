package io.github.skiesworld.qqbot.examples;

import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.QQBotClient;
import io.github.skiesworld.qqbot.api.Api;
import io.github.skiesworld.qqbot.command.Command;
import io.github.skiesworld.qqbot.command.CommandContext;
import io.github.skiesworld.qqbot.command.Role;
import io.github.skiesworld.qqbot.event.EventType;
import io.github.skiesworld.qqbot.event.QQEvent;
import io.github.skiesworld.qqbot.event.model.C2CMessageCreate;
import io.github.skiesworld.qqbot.event.model.GroupAtMessageCreate;
import io.github.skiesworld.qqbot.handler.BotEvent;
import io.github.skiesworld.qqbot.handler.BotHandler;
import io.github.skiesworld.qqbot.handler.BotHandlers;
import io.github.skiesworld.qqbot.message.MessageBuilder;
import io.github.skiesworld.qqbot.message.MessageSegments;
import io.github.skiesworld.qqbot.message.Segment;
import io.github.skiesworld.qqbot.websocket.Intent;

/**
 * 注解式机器人：handler 类按参数类型注入、消息段读取、@Command 命令匹配。
 *
 * <pre>
 *   QQ_APP_ID=... QQ_APP_SECRET=... ./gradlew runExample -Pexample=HandlerBot
 * </pre>
 *
 * <p>{@code bot.handlers().register(...)} 与 {@code bot.commands().register(...)} 都走反射装配，编译期不需要
 * 注解处理器。想用 ServiceLoader 自动发现：handler 类实现 {@link BotHandler} 并保留 @BotHandlers，在自己的构建里
 * 启用本 SDK 附带的处理器（JDK 21+ 需要 {@code -proc:full}，或显式配置 {@code --processor-path}），然后把两行
 * register 换成 {@code registerDiscovered()}。
 */
public final class HandlerBot {

    private HandlerBot() {
    }

    public static void main(String[] args) throws Exception {
        QQBotClient bot = Env.client(Intent.GROUP_AND_C2C_EVENT, Intent.PUBLIC_GUILD_MESSAGES);

        bot.handlers().register(new ChatHandlers());
        bot.commands().usePrefixes("/", "").register(new ChatCommands());
        Env.log("commands: {}", bot.commands().describe());

        bot.connectAndAwaitReady(20_000);
        Env.log("annotated bot online, press Ctrl+C to exit");
        Runtime.getRuntime().addShutdownHook(new Thread(bot::close));
        Thread.currentThread().join();
    }

    /** 事件层：参数按类型注入，方法名与顺序随你安排。 */
    @BotHandlers("chat")
    public static class ChatHandlers implements BotHandler {

        @BotEvent(EventType.C2C_MESSAGE_CREATE)
        public void onPrivateMessage(C2CMessageCreate msg, QQEvent raw) {
            MessageSegments segments = MessageSegments.of(raw);
            Env.log("c2c {}: {} ({} segment(s))", msg.author.username, segments.text(),
                    segments.segments().size());
            // 图片、语音等在 attachments 里，content 文本中没有可回填的位置
            for (Segment.Media media : segments.segmentsOfType(Segment.Media.class)) {
                Env.log("  {} {} {}", media.kind(), media.attachment().filename, media.attachment().url);
            }
        }

        @BotEvent(EventType.GROUP_AT_MESSAGE_CREATE)
        public void onGroupMessage(GroupAtMessageCreate msg, QQEvent raw, Api api) {
            api.group().sendGroupMessage(msg.groupOpenid, MessageBuilder.of("这条群消息有 "
                    + MessageSegments.of(raw).mentions().size() + " 个 @")
                    .replyTo(raw.id()).seq(1L).toGroup());
        }

        /** 官方新增、SDK 还没建模的事件用名字接住。 */
        @BotEvent(name = "GROUP_SOMETHING_NEW")
        public void onUnmodelled(JsonObject body, QQEvent raw) {
            Env.log("raw %s -> %s", raw.name(), body);
        }
    }

    /** 命令层：默认无需前缀（群消息已经要求 @ 机器人），这里额外允许 "/"。 */
    @BotHandlers("commands")
    public static class ChatCommands implements BotHandler {

        @Command(value = {"帮助", "help"}, description = "列出可用命令")
        public void help(CommandContext ctx) {
            ctx.reply(String.join("\n", ctx.client().commands().describe()));
        }

        @Command(value = "复读 (.+)", kind = Command.Kind.REGEX)
        public void repeat(CommandContext ctx) {
            ctx.reply("你说：" + ctx.groups().get(0));
        }

        /** 群里只有管理员能改；单聊不报角色，所以不受这条限制。 */
        @Command(value = "状态", role = Role.ADMIN, description = "仅管理员")
        public void status(CommandContext ctx) {
            ctx.reply(MessageBuilder.create()
                    .markdown("**场景** " + ctx.scene() + "\n**角色** " + ctx.role() + "\n**消息号** "
                            + ctx.messageId()));
        }
    }
}
