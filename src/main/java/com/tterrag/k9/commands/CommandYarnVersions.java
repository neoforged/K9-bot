package com.tterrag.k9.commands;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.gson.Gson;
import com.tterrag.k9.K9;
import com.tterrag.k9.commands.api.Argument;
import com.tterrag.k9.commands.api.Command;
import com.tterrag.k9.commands.api.CommandBase;
import com.tterrag.k9.commands.api.CommandContext;
import com.tterrag.k9.mappings.yarn.MappingsVersion;
import com.tterrag.k9.mappings.yarn.YarnDownloader;
import com.tterrag.k9.util.EmbedCreator;

import discord4j.core.DiscordClient;
import discord4j.core.object.entity.Guild;
import reactor.core.publisher.Mono;

@Command
public class CommandYarnVersions extends CommandBase {

    private static final Argument<String> ARG_VERSION = CommandMappings.ARG_VERSION;
    
    private CommandYarn yarnCommand;
    
    public CommandYarnVersions() {
        super("yv", false);
    }
    
    @Override
    public void init(DiscordClient client, File dataFolder, Gson gson) {
        super.init(client, dataFolder, gson);
        
        yarnCommand = (CommandYarn) K9.commands.findCommand((Guild) null, "yarn").get();
    }

    @Override
    public Mono<?> process(CommandContext ctx) {
        String version = ctx.getArgOrGet(ARG_VERSION, () -> yarnCommand.getData(ctx).block());
        if (version == null) {
            version = YarnDownloader.INSTANCE.getLatestMinecraftVersion();
        }
        EmbedCreator.Builder builder = EmbedCreator.builder();
        Map<String, List<MappingsVersion>> versions = YarnDownloader.INSTANCE.getIndexedVersions();
        for (Entry<String, List<MappingsVersion>> e : versions.entrySet()) {
            if (e.getKey().equals(version)) {
                List<MappingsVersion> mappings = e.getValue();
                builder.title("Latest mappings for MC " + e.getKey());
                MappingsVersion v = mappings.get(0);
                builder.description("Version: " + v.getBuild());
                builder.field("Full Version", "`" + v.getVersion() + "`", true);
                builder.field("Gradle String", "`mappings '" + v.getMaven() + "'`", true);
                builder.color(CommandYarn.COLOR);
            }
        }
        if (builder.getFieldCount() == 0) {
            return ctx.error("No such version: " + version);
        }
        return ctx.reply(builder.build());
    }

    @Override
    public String getDescription() {
        return "Lists the latest mappings version for the given MC version. If none is given, uses the guild default.";
    }

}
