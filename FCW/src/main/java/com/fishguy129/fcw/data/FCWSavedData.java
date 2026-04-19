package com.fishguy129.fcw.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

// World save bucket for everything FCW needs to survive a restart.
public class FCWSavedData extends SavedData {
    private static final String DATA_NAME = "fcw_state";

    private final Map<UUID, CoreRecord> cores = new HashMap<>();
    private final Map<UUID, RaidRecord> raids = new HashMap<>();
    private final Map<UUID, Integer> raidCraftCounts = new HashMap<>();
    private final Map<UUID, Map<UUID, Long>> memberJoinTimes = new HashMap<>();

    public static FCWSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FCWSavedData::load, FCWSavedData::new, DATA_NAME);
    }

    public static FCWSavedData load(CompoundTag tag) {
        FCWSavedData data = new FCWSavedData();

        ListTag coreList = tag.getList("cores", Tag.TAG_COMPOUND);
        for (int i = 0; i < coreList.size(); i++) {
            CoreRecord core = CoreRecord.fromTag(coreList.getCompound(i));
            data.cores.put(core.teamId(), core);
        }

        ListTag raidList = tag.getList("raids", Tag.TAG_COMPOUND);
        for (int i = 0; i < raidList.size(); i++) {
            RaidRecord raid = RaidRecord.fromTag(raidList.getCompound(i));
            data.raids.put(raid.raidId(), raid);
        }

        CompoundTag craftCounts = tag.getCompound("raidCraftCounts");
        for (String key : craftCounts.getAllKeys()) {
            data.raidCraftCounts.put(UUID.fromString(key), craftCounts.getInt(key));
        }

        CompoundTag joinTimes = tag.getCompound("memberJoinTimes");
        for (String teamIdKey : joinTimes.getAllKeys()) {
            UUID teamId = UUID.fromString(teamIdKey);
            CompoundTag teamJoinData = joinTimes.getCompound(teamIdKey);
            Map<UUID, Long> entries = new HashMap<>();
            for (String playerIdKey : teamJoinData.getAllKeys()) {
                entries.put(UUID.fromString(playerIdKey), teamJoinData.getLong(playerIdKey));
            }
            data.memberJoinTimes.put(teamId, entries);
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag coreList = new ListTag();
        cores.values().stream().sorted(Comparator.comparing(CoreRecord::teamId)).forEach(core -> coreList.add(core.toTag()));
        tag.put("cores", coreList);

        ListTag raidList = new ListTag();
        raids.values().stream().sorted(Comparator.comparing(RaidRecord::raidId)).forEach(raid -> raidList.add(raid.toTag()));
        tag.put("raids", raidList);

        CompoundTag craftCounts = new CompoundTag();
        raidCraftCounts.forEach((teamId, count) -> craftCounts.putInt(teamId.toString(), count));
        tag.put("raidCraftCounts", craftCounts);

        CompoundTag joinTimes = new CompoundTag();
        memberJoinTimes.forEach((teamId, entries) -> {
            CompoundTag teamJoinData = new CompoundTag();
            entries.forEach((playerId, joinedAt) -> teamJoinData.putLong(playerId.toString(), joinedAt));
            joinTimes.put(teamId.toString(), teamJoinData);
        });
        tag.put("memberJoinTimes", joinTimes);

        return tag;
    }

    public Optional<CoreRecord> getCore(UUID teamId) {
        return Optional.ofNullable(cores.get(teamId));
    }

    public Collection<CoreRecord> allCores() {
        return Collections.unmodifiableCollection(cores.values());
    }

    public void putCore(CoreRecord coreRecord) {
        cores.put(coreRecord.teamId(), coreRecord);
        setDirty();
    }

    public void removeCore(UUID teamId) {
        cores.remove(teamId);
        setDirty();
    }

    public Optional<RaidRecord> findRaidForTeam(UUID teamId) {
        if (teamId == null) {
            return Optional.empty();
        }
        return raids.values().stream().filter(raid -> raid.attackerTeamId().equals(teamId) || raid.defenderTeamId().equals(teamId)).findFirst();
    }

    public Collection<RaidRecord> activeRaids() {
        return Collections.unmodifiableCollection(raids.values());
    }

    public void putRaid(RaidRecord raidRecord) {
        raids.put(raidRecord.raidId(), raidRecord);
        setDirty();
    }

    public void removeRaid(UUID raidId) {
        raids.remove(raidId);
        setDirty();
    }

    public int raidCraftCount(UUID teamId) {
        return raidCraftCounts.getOrDefault(teamId, 0);
    }

    public void incrementRaidCraftCount(UUID teamId) {
        raidCraftCounts.merge(teamId, 1, Integer::sum);
        setDirty();
    }

    public long memberJoinTick(UUID teamId, UUID playerId) {
        return memberJoinTimes.getOrDefault(teamId, Map.of()).getOrDefault(playerId, 0L);
    }

    public void setMemberJoinTick(UUID teamId, UUID playerId, long gameTick) {
        memberJoinTimes.computeIfAbsent(teamId, ignored -> new HashMap<>()).put(playerId, gameTick);
        setDirty();
    }

    public void removeMemberJoinTick(UUID teamId, UUID playerId) {
        Map<UUID, Long> teamData = memberJoinTimes.get(teamId);
        if (teamData != null) {
            teamData.remove(playerId);
            if (teamData.isEmpty()) {
                memberJoinTimes.remove(teamId);
            }
            setDirty();
        }
    }

    public void clearTeamMeta(UUID teamId) {
        boolean changed = false;
        if (raidCraftCounts.remove(teamId) != null) {
            changed = true;
        }
        if (memberJoinTimes.remove(teamId) != null) {
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    public record CoreRecord(UUID teamId, ResourceKey<Level> dimension, BlockPos pos, boolean active, boolean protectionSuspended,
                             boolean packed, int upgradeCount, long lastRelocationTick, List<ItemStack> hologramItems,
                             List<Long> savedClaimChunks) {
        // These with* helpers keep call sites readable without making the record itself mutable.
        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("teamId", teamId);
            tag.putString("dimension", dimension.location().toString());
            tag.putLong("pos", pos.asLong());
            tag.putBoolean("active", active);
            tag.putBoolean("protectionSuspended", protectionSuspended);
            tag.putBoolean("packed", packed);
            tag.putInt("upgradeCount", upgradeCount);
            tag.putLong("lastRelocationTick", lastRelocationTick);
            ListTag hologramList = new ListTag();
            for (ItemStack hologramItem : normalizedHologramItems(hologramItems)) {
                CompoundTag itemTag = new CompoundTag();
                hologramItem.save(itemTag);
                hologramList.add(itemTag);
            }
            tag.put("hologramItems", hologramList);
            ListTag savedClaims = new ListTag();
            for (Long chunkLong : normalizedSavedClaimChunks(savedClaimChunks)) {
                CompoundTag chunkTag = new CompoundTag();
                chunkTag.putLong("chunk", chunkLong);
                savedClaims.add(chunkTag);
            }
            tag.put("savedClaimChunks", savedClaims);
            return tag;
        }

        public static CoreRecord fromTag(CompoundTag tag) {
            return new CoreRecord(
                    tag.getUUID("teamId"),
                    ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, new ResourceLocation(tag.getString("dimension"))),
                    BlockPos.of(tag.getLong("pos")),
                    tag.getBoolean("active"),
                    tag.getBoolean("protectionSuspended"),
                    tag.getBoolean("packed"),
                    tag.getInt("upgradeCount"),
                    tag.getLong("lastRelocationTick"),
                    readHologramItems(tag.getList("hologramItems", Tag.TAG_COMPOUND)),
                    readSavedClaimChunks(tag.getList("savedClaimChunks", Tag.TAG_COMPOUND))
            );
        }

        public CoreRecord withPos(ResourceKey<Level> newDimension, BlockPos newPos, long gameTick) {
            return new CoreRecord(teamId, newDimension, newPos, active, protectionSuspended, false, upgradeCount, gameTick,
                    normalizedHologramItems(hologramItems), normalizedSavedClaimChunks(savedClaimChunks));
        }

        public CoreRecord withUpgradeCount(int newUpgradeCount) {
            return new CoreRecord(teamId, dimension, pos, active, protectionSuspended, packed, newUpgradeCount, lastRelocationTick,
                    normalizedHologramItems(hologramItems), normalizedSavedClaimChunks(savedClaimChunks));
        }

        public CoreRecord withActive(boolean newActive) {
            return new CoreRecord(teamId, dimension, pos, newActive, protectionSuspended, packed, upgradeCount, lastRelocationTick,
                    normalizedHologramItems(hologramItems), normalizedSavedClaimChunks(savedClaimChunks));
        }

        public CoreRecord withProtectionSuspended(boolean suspended) {
            return new CoreRecord(teamId, dimension, pos, active, suspended, packed, upgradeCount, lastRelocationTick,
                    normalizedHologramItems(hologramItems), normalizedSavedClaimChunks(savedClaimChunks));
        }

        public CoreRecord withPacked(boolean packedState, long gameTick) {
            return new CoreRecord(teamId, dimension, pos, active, protectionSuspended, packedState, upgradeCount, gameTick,
                    normalizedHologramItems(hologramItems), normalizedSavedClaimChunks(savedClaimChunks));
        }

        public CoreRecord withHologramItem(int slot, ItemStack stack) {
            List<ItemStack> updated = new ArrayList<>(normalizedHologramItems(hologramItems));
            if (slot >= 0 && slot < updated.size()) {
                updated.set(slot, stack.copy());
            }
            return new CoreRecord(teamId, dimension, pos, active, protectionSuspended, packed, upgradeCount, lastRelocationTick,
                    normalizedHologramItems(updated), normalizedSavedClaimChunks(savedClaimChunks));
        }

        public CoreRecord withSavedClaimChunks(Collection<Long> chunkLongs) {
            return new CoreRecord(teamId, dimension, pos, active, protectionSuspended, packed, upgradeCount, lastRelocationTick,
                    normalizedHologramItems(hologramItems), normalizedSavedClaimChunks(chunkLongs));
        }

        private static List<ItemStack> readHologramItems(ListTag listTag) {
            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < listTag.size(); i++) {
                items.add(ItemStack.of(listTag.getCompound(i)));
            }
            return normalizedHologramItems(items);
        }

        private static List<Long> readSavedClaimChunks(ListTag listTag) {
            List<Long> chunks = new ArrayList<>();
            for (int i = 0; i < listTag.size(); i++) {
                chunks.add(listTag.getCompound(i).getLong("chunk"));
            }
            return normalizedSavedClaimChunks(chunks);
        }

        public static List<ItemStack> normalizedHologramItems(List<ItemStack> items) {
            // Keep the saved shape fixed so the renderer and menu never have to guess.
            List<ItemStack> normalized = new ArrayList<>(3);
            for (int i = 0; i < 3; i++) {
                if (items != null && i < items.size() && !items.get(i).isEmpty()) {
                    ItemStack copy = items.get(i).copy();
                    copy.setCount(1);
                    normalized.add(copy);
                } else {
                    normalized.add(ItemStack.EMPTY);
                }
            }
            return List.copyOf(normalized);
        }

        public static List<Long> normalizedSavedClaimChunks(Collection<Long> chunks) {
            if (chunks == null || chunks.isEmpty()) {
                return List.of();
            }
            LinkedHashSet<Long> normalized = new LinkedHashSet<>();
            for (Long chunk : chunks) {
                if (chunk != null) {
                    normalized.add(chunk);
                }
            }
            return List.copyOf(normalized);
        }
    }

    public static final class RaidRecord {
        // Raids mutate in place because progress, eliminations, and logout deadlines
        // are updated every tick.
        private final UUID raidId;
        private final UUID attackerTeamId;
        private final UUID defenderTeamId;
        private final ResourceKey<Level> dimension;
        private final BlockPos corePos;
        private final long startedTick;
        private long progressTicks;
        private final long requiredTicks;
        private final Set<UUID> eliminatedAttackers;
        private final Set<UUID> eliminatedDefenders;
        private final Map<UUID, Long> logoutDeadlines;
        private final Set<Long> forcedChunks;

        public RaidRecord(UUID raidId, UUID attackerTeamId, UUID defenderTeamId, ResourceKey<Level> dimension, BlockPos corePos,
                          long startedTick, long progressTicks, long requiredTicks, Set<UUID> eliminatedAttackers, Set<UUID> eliminatedDefenders,
                          Map<UUID, Long> logoutDeadlines, Set<Long> forcedChunks) {
            this.raidId = raidId;
            this.attackerTeamId = attackerTeamId;
            this.defenderTeamId = defenderTeamId;
            this.dimension = dimension;
            this.corePos = corePos;
            this.startedTick = startedTick;
            this.progressTicks = progressTicks;
            this.requiredTicks = requiredTicks;
            this.eliminatedAttackers = eliminatedAttackers;
            this.eliminatedDefenders = eliminatedDefenders;
            this.logoutDeadlines = logoutDeadlines;
            this.forcedChunks = forcedChunks;
        }

        public UUID raidId() { return raidId; }
        public UUID attackerTeamId() { return attackerTeamId; }
        public UUID defenderTeamId() { return defenderTeamId; }
        public ResourceKey<Level> dimension() { return dimension; }
        public BlockPos corePos() { return corePos; }
        public long progressTicks() { return progressTicks; }
        public long requiredTicks() { return requiredTicks; }
        public Set<UUID> eliminatedAttackers() { return eliminatedAttackers; }
        public Set<UUID> eliminatedDefenders() { return eliminatedDefenders; }
        public Map<UUID, Long> logoutDeadlines() { return logoutDeadlines; }
        public Set<Long> forcedChunks() { return forcedChunks; }

        public void incrementProgress() { progressTicks++; }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("raidId", raidId);
            tag.putUUID("attackerTeamId", attackerTeamId);
            tag.putUUID("defenderTeamId", defenderTeamId);
            tag.putString("dimension", dimension.location().toString());
            tag.putLong("corePos", corePos.asLong());
            tag.putLong("startedTick", startedTick);
            tag.putLong("progressTicks", progressTicks);
            tag.putLong("requiredTicks", requiredTicks);

            ListTag eliminated = new ListTag();
            eliminatedAttackers.forEach(id -> {
                CompoundTag idTag = new CompoundTag();
                idTag.putUUID("id", id);
                eliminated.add(idTag);
            });
            tag.put("eliminatedAttackers", eliminated);

            ListTag eliminatedDef = new ListTag();
            eliminatedDefenders.forEach(id -> {
                CompoundTag idTag = new CompoundTag();
                idTag.putUUID("id", id);
                eliminatedDef.add(idTag);
            });
            tag.put("eliminatedDefenders", eliminatedDef);

            CompoundTag deadlines = new CompoundTag();
            logoutDeadlines.forEach((id, deadline) -> deadlines.putLong(id.toString(), deadline));
            tag.put("logoutDeadlines", deadlines);

            ListTag chunks = new ListTag();
            forcedChunks.forEach(chunkLong -> {
                CompoundTag chunkTag = new CompoundTag();
                chunkTag.putLong("chunk", chunkLong);
                chunks.add(chunkTag);
            });
            tag.put("forcedChunks", chunks);
            return tag;
        }

        public static RaidRecord fromTag(CompoundTag tag) {
            Set<UUID> eliminated = new HashSet<>();
            ListTag eliminatedTag = tag.getList("eliminatedAttackers", Tag.TAG_COMPOUND);
            for (int i = 0; i < eliminatedTag.size(); i++) {
                eliminated.add(eliminatedTag.getCompound(i).getUUID("id"));
            }

            Set<UUID> eliminatedDef = new HashSet<>();
            ListTag eliminatedDefTag = tag.getList("eliminatedDefenders", Tag.TAG_COMPOUND);
            for (int i = 0; i < eliminatedDefTag.size(); i++) {
                eliminatedDef.add(eliminatedDefTag.getCompound(i).getUUID("id"));
            }

            Map<UUID, Long> deadlines = new HashMap<>();
            CompoundTag deadlineTag = tag.getCompound("logoutDeadlines");
            for (String key : deadlineTag.getAllKeys()) {
                deadlines.put(UUID.fromString(key), deadlineTag.getLong(key));
            }

            Set<Long> forcedChunks = new HashSet<>();
            ListTag chunks = tag.getList("forcedChunks", Tag.TAG_COMPOUND);
            for (int i = 0; i < chunks.size(); i++) {
                forcedChunks.add(chunks.getCompound(i).getLong("chunk"));
            }

            return new RaidRecord(
                    tag.getUUID("raidId"),
                    tag.getUUID("attackerTeamId"),
                    tag.getUUID("defenderTeamId"),
                    ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, new ResourceLocation(tag.getString("dimension"))),
                    BlockPos.of(tag.getLong("corePos")),
                    tag.getLong("startedTick"),
                    tag.getLong("progressTicks"),
                    tag.getLong("requiredTicks"),
                    eliminated,
                    eliminatedDef,
                    deadlines,
                    forcedChunks
            );
        }
    }
}
