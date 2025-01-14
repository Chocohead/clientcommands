package net.earthcomputer.clientcommands.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.serialization.Dynamic;
import net.earthcomputer.clientcommands.interfaces.IItemGroup;
import net.earthcomputer.clientcommands.mixin.CreativeInventoryScreenAccessor;
import net.fabricmc.fabric.api.client.itemgroup.FabricItemGroupBuilder;
import net.fabricmc.fabric.api.util.NbtType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.datafixer.TypeReferences;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Identifier;
import net.minecraft.util.InvalidIdentifierException;
import net.minecraft.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static com.mojang.brigadier.arguments.IntegerArgumentType.*;
import static com.mojang.brigadier.arguments.StringArgumentType.*;
import static net.earthcomputer.clientcommands.command.ClientCommandManager.*;
import static net.minecraft.command.CommandSource.*;
import static net.minecraft.command.argument.ItemStackArgumentType.*;
import static net.minecraft.server.command.CommandManager.*;

public class ItemGroupCommand {

    private static final Logger LOGGER = LogManager.getLogger("clientcommands");

    private static final DynamicCommandExceptionType NOT_FOUND_EXCEPTION = new DynamicCommandExceptionType(arg -> new TranslatableText("commands.citemgroup.notFound", arg));
    private static final DynamicCommandExceptionType OUT_OF_BOUNDS_EXCEPTION = new DynamicCommandExceptionType(arg -> new TranslatableText("commands.citemgroup.outOfBounds", arg));

    private static final SimpleCommandExceptionType SAVE_FAILED_EXCEPTION = new SimpleCommandExceptionType(new TranslatableText("commands.citemgroup.saveFile.failed"));
    private static final DynamicCommandExceptionType ILLEGAL_CHARACTER_EXCEPTION = new DynamicCommandExceptionType(arg -> new TranslatableText("commands.citemgroup.addGroup.illegalCharacter", arg));
    private static final DynamicCommandExceptionType ALREADY_EXISTS_EXCEPTION = new DynamicCommandExceptionType(arg -> new TranslatableText("commands.citemgroup.addGroup.alreadyExists", arg));

    private static final Path configPath = FabricLoader.getInstance().getConfigDir().resolve("clientcommands");

    private static final MinecraftClient client = MinecraftClient.getInstance();

    private static final Map<String, Group> groups = new HashMap<>();

    static {
        try {
            loadFile();
        } catch (IOException e) {
            LOGGER.error("Could not load groups file, hence /citemgroup will not work!", e);
        }
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        addClientSideCommand("citemgroup");

        LiteralCommandNode<ServerCommandSource> citemgroup = dispatcher.register(literal("citemgroup"));
        dispatcher.register(literal("citemgroup")
            .then(literal("modify")
                .then(argument("group", string())
                    .suggests((ctx, builder) -> suggestMatching(groups.keySet(), builder))
                    .then(literal("add")
                        .then(argument("itemstack", itemStack())
                            .then(argument("count", integer(1))
                                .executes(ctx -> addStack(ctx.getSource(), getString(ctx, "group"), getItemStackArgument(ctx, "itemstack").createStack(getInteger(ctx, "count"), false))))
                            .executes(ctx -> addStack(ctx.getSource(), getString(ctx, "group"), getItemStackArgument(ctx, "itemstack").createStack(1, false)))))
                    .then(literal("remove")
                        .then(argument("index", integer(0))
                            .executes(ctx -> removeStack(ctx.getSource(), getString(ctx, "group"), getInteger(ctx, "index")))))
                    .then(literal("set")
                        .then(argument("index", integer(0))
                            .then(argument("itemstack", itemStack())
                                .then(argument("count", integer(1))
                                    .executes(ctx -> setStack(ctx.getSource(), getString(ctx, "group"), getInteger(ctx, "index"), getItemStackArgument(ctx, "itemstack").createStack(getInteger(ctx, "count"), false))))
                                .executes(ctx -> setStack(ctx.getSource(), getString(ctx, "group"), getInteger(ctx, "index"), getItemStackArgument(ctx, "itemstack").createStack(1, false))))))
                    .then(literal("icon")
                        .then(argument("icon", itemStack())
                            .executes(ctx -> changeIcon(ctx.getSource(), getString(ctx, "group"), getItemStackArgument(ctx, "icon").createStack(1, false)))))
                    .then(literal("rename")
                        .then(argument("new", string())
                            .executes(ctx -> renameGroup(ctx.getSource(), getString(ctx, "group"), getString(ctx, "new")))))))
            .then(literal("add")
                .then(argument("group", string())
                    .then(argument("icon", itemStack())
                         .executes(ctx -> addGroup(ctx.getSource(), getString(ctx, "group"), getItemStackArgument(ctx, "icon").createStack(1, false))))))
            .then(literal("remove")
                .then(argument("group", string())
                    .suggests((ctx, builder) -> suggestMatching(groups.keySet(), builder))
                    .executes(ctx -> removeGroup(ctx.getSource(), getString(ctx, "group"))))));
    }

    private static int addGroup(ServerCommandSource source, String name, ItemStack icon) throws CommandSyntaxException {
        if (groups.containsKey(name)) {
            throw ALREADY_EXISTS_EXCEPTION.create(name);
        }

        final Identifier identifier = Identifier.tryParse("clientcommands:" + name);
        if (identifier == null) {
            throw ILLEGAL_CHARACTER_EXCEPTION.create(name);
        }
        ItemGroup itemGroup = FabricItemGroupBuilder.create(identifier)
                .icon(() -> icon)
                .build();

        groups.put(name, new Group(itemGroup, icon, new ListTag()));
        saveFile();
        sendFeedback("commands.citemgroup.addGroup.success", name);
        return 0;
    }

    private static int removeGroup(ServerCommandSource source, String name) throws CommandSyntaxException {
        if (!groups.containsKey(name)) {
            throw NOT_FOUND_EXCEPTION.create(name);
        }

        groups.remove(name);

        reloadGroups();
        CreativeInventoryScreenAccessor.setSelectedTab(0);
        CreativeInventoryScreenAccessor.setFabricCurrentPage(0);
        saveFile();
        sendFeedback("commands.citemgroup.removeGroup.success", name);
        return 0;
    }

    private static int addStack(ServerCommandSource source, String name, ItemStack itemStack) throws CommandSyntaxException {
        if (!groups.containsKey(name)) {
            throw NOT_FOUND_EXCEPTION.create(name);
        }

        Group group = groups.get(name);
        ListTag items = group.getItems();
        items.add(itemStack.toTag(new CompoundTag()));

        reloadGroups();
        saveFile();
        sendFeedback("commands.citemgroup.addStack.success", itemStack.getItem().getName(), name);
        return 0;
    }

    private static int removeStack(ServerCommandSource source, String name, int index) throws CommandSyntaxException {
        if (!groups.containsKey(name)) {
            throw NOT_FOUND_EXCEPTION.create(name);
        }

        Group group = groups.get(name);
        ListTag items = group.getItems();
        if (index < 0 || index >= items.size()) {
            throw OUT_OF_BOUNDS_EXCEPTION.create(index);
        }
        items.remove(index);

        reloadGroups();
        saveFile();
        sendFeedback("commands.citemgroup.removeStack.success", name, index);
        return 0;
    }

    private static int setStack(ServerCommandSource source, String name, int index, ItemStack itemStack) throws CommandSyntaxException {
        if (!groups.containsKey(name)) {
            throw NOT_FOUND_EXCEPTION.create(name);
        }

        Group group = groups.get(name);
        ListTag items = group.getItems();
        if ((index < 0) || (index >= items.size())) {
            throw OUT_OF_BOUNDS_EXCEPTION.create(index);
        }
        items.set(index, itemStack.toTag(new CompoundTag()));

        reloadGroups();
        saveFile();
        sendFeedback("commands.citemgroup.setStack.success", name, index, itemStack.getItem().getName());
        return 0;
    }

    private static int changeIcon(ServerCommandSource source, String name, ItemStack icon) throws CommandSyntaxException {
        if (!groups.containsKey(name)) {
            throw NOT_FOUND_EXCEPTION.create(name);
        }

        Group group = groups.get(name);
        ItemGroup itemGroup = group.getItemGroup();
        ListTag items = group.getItems();
        ItemStack old = itemGroup.getIcon();
        itemGroup = FabricItemGroupBuilder.create(
                new Identifier("clientcommands", name))
                .icon(() -> icon)
                .build();

        groups.put(name, new Group(itemGroup, icon, items));

        reloadGroups();
        saveFile();
        sendFeedback("commands.citemgroup.changeIcon.success", name, old.getItem().getName(), icon.getItem().getName());
        return 0;
    }

    private static int renameGroup(ServerCommandSource source, String name, String _new) throws CommandSyntaxException {
        if (!groups.containsKey(name)) {
            throw NOT_FOUND_EXCEPTION.create(name);
        }

        Group group = groups.remove(name);
        ItemGroup itemGroup = group.getItemGroup();
        ListTag items = group.getItems();
        ItemStack icon = itemGroup.getIcon();
        try {
            itemGroup = FabricItemGroupBuilder.create(
                    new Identifier("clientcommands", _new))
                    .icon(() -> icon)
                    .build();

            groups.put(_new, new Group(itemGroup, icon, items));
        } catch (InvalidIdentifierException e) {
            throw ILLEGAL_CHARACTER_EXCEPTION.create(_new);
        }

        reloadGroups();
        saveFile();
        sendFeedback("commands.citemgroup.renameGroup.success", name, _new);
        return 0;
    }

    private static void saveFile() throws CommandSyntaxException {
        try {
            CompoundTag rootTag = new CompoundTag();
            CompoundTag compoundTag = new CompoundTag();
            groups.forEach((key, value) -> {
                CompoundTag group = new CompoundTag();
                group.put("icon", value.getIcon().toTag(new CompoundTag()));
                group.put("items", value.getItems());
                compoundTag.put(key, group);
            });
            rootTag.putInt("DataVersion", SharedConstants.getGameVersion().getWorldVersion());
            rootTag.put("Groups", compoundTag);
            File newFile = File.createTempFile("groups", ".dat", configPath.toFile());
            NbtIo.write(rootTag, newFile);
            File backupFile = new File(configPath.toFile(), "groups.dat_old");
            File currentFile = new File(configPath.toFile(), "groups.dat");
            Util.backupAndReplace(currentFile, newFile, backupFile);
        } catch (IOException e) {
            e.printStackTrace();
            throw SAVE_FAILED_EXCEPTION.create();
        }
    }

    private static void loadFile() throws IOException {
        groups.clear();
        CompoundTag rootTag = NbtIo.read(new File(configPath.toFile(), "groups.dat"));
        if (rootTag == null) {
            return;
        }
        final int currentVersion = SharedConstants.getGameVersion().getWorldVersion();
        final int fileVersion = rootTag.getInt("DataVersion");
        CompoundTag compoundTag = rootTag.getCompound("Groups");
        if (fileVersion >= currentVersion) {
            compoundTag.getKeys().forEach(key -> {
                CompoundTag group = compoundTag.getCompound(key);
                ItemStack icon = ItemStack.fromTag(group.getCompound("icon"));
                ListTag items = group.getList("items", NbtType.COMPOUND);
                ItemGroup itemGroup = FabricItemGroupBuilder.create(
                        new Identifier("clientcommands", key))
                        .icon(() -> icon)
                        .appendItems(stacks -> {
                            for (int i = 0; i < items.size(); i++) {
                                stacks.add(ItemStack.fromTag(items.getCompound(i)));
                            }
                        })
                        .build();
                groups.put(key, new Group(itemGroup, icon, items));
            });
        } else {
            compoundTag.getKeys().forEach(key -> {
                CompoundTag group = compoundTag.getCompound(key);
                Dynamic<Tag> oldStackDynamic = new Dynamic<>(NbtOps.INSTANCE, group.getCompound("icon"));
                Dynamic<Tag> newStackDynamic = client.getDataFixer().update(TypeReferences.ITEM_STACK, oldStackDynamic, fileVersion, currentVersion);
                ItemStack icon = ItemStack.fromTag((CompoundTag) newStackDynamic.getValue());

                ListTag updatedListTag = new ListTag();
                group.getList("items", NbtType.COMPOUND).forEach(tag -> {
                    Dynamic<Tag> oldTagDynamic = new Dynamic<>(NbtOps.INSTANCE, tag);
                    Dynamic<Tag> newTagDynamic = client.getDataFixer().update(TypeReferences.ITEM_STACK, oldTagDynamic, fileVersion, currentVersion);
                    updatedListTag.add(newTagDynamic.getValue());
                });
                ItemGroup itemGroup = FabricItemGroupBuilder.create(
                        new Identifier("clientcommands", key))
                        .icon(() -> icon)
                        .appendItems(stacks -> {
                            for (int i = 0; i < updatedListTag.size(); i++) {
                                stacks.add(ItemStack.fromTag(updatedListTag.getCompound(i)));
                            }
                        })
                        .build();
                groups.put(key, new Group(itemGroup, icon, updatedListTag));
            });
        }
    }

    private static void reloadGroups() {
        ((IItemGroup) ItemGroup.BUILDING_BLOCKS).shrink();

        for (String key : groups.keySet()) {
            Group group = groups.get(key);
            group.setItemGroup(FabricItemGroupBuilder.create(
                    new Identifier("clientcommands", key))
                    .icon(group::getIcon)
                    .appendItems(stacks -> {
                        for (int i = 0; i < group.getItems().size(); i++) {
                            stacks.add(ItemStack.fromTag(group.getItems().getCompound(i)));
                        }
                    })
                    .build());
        }
    }
}

class Group {

    private ItemGroup itemGroup;
    private final ItemStack icon;
    private final ListTag items;

    public Group(ItemGroup itemGroup, ItemStack icon, ListTag items) {
        this.itemGroup = itemGroup;
        this.icon = icon;
        this.items = items;
    }

    public ItemGroup getItemGroup() {
        return this.itemGroup;
    }

    public ItemStack getIcon() {
        return this.icon;
    }

    public ListTag getItems() {
        return this.items;
    }

    public void setItemGroup(ItemGroup itemGroup) {
        this.itemGroup = itemGroup;
    }
}
