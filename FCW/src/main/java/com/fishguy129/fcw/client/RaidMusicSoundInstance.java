package com.fishguy129.fcw.client;

import com.fishguy129.fcw.config.FCWClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

// Looping raid ambience tied to one raid core. The sound instance owns its own
// fade-out logic so the client tick handler can stay small
public class RaidMusicSoundInstance extends AbstractTickableSoundInstance {
    private final ResourceLocation dimensionId;
    private final net.minecraft.core.BlockPos corePos;

    public RaidMusicSoundInstance(SoundEvent soundEvent, ResourceLocation dimensionId, net.minecraft.core.BlockPos corePos) {
        super(soundEvent, SoundSource.RECORDS, RandomSource.create());
        this.dimensionId = dimensionId;
        this.corePos = corePos.immutable();
        this.looping = true;
        this.delay = 0;
        this.attenuation = Attenuation.LINEAR;
        this.relative = false;
        this.volume = FCWClientConfig.RAID_MUSIC_VOLUME.get().floatValue();
        this.pitch = 1.0F;
        this.x = corePos.getX() + 0.5D;
        this.y = corePos.getY() + 1.0D;
        this.z = corePos.getZ() + 0.5D;
    }

    @Override
    public void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || !FCWClientConfig.ENABLE_RAID_MUSIC.get()) {
            stop();
            return;
        }

        ClientRaidState.RaidVisual raid = ClientRaidState.get(dimensionId, corePos);
        if (raid == null || (!raid.localPlayerAttacker() && !raid.localPlayerDefender())) {
            stop();
            return;
        }

        if (!minecraft.level.dimension().location().equals(dimensionId)) {
            stop();
            return;
        }

        double maxDistance = FCWClientConfig.RAID_MUSIC_RANGE.get();
        double distance = minecraft.player.distanceToSqr(corePos.getX() + 0.5D, corePos.getY() + 0.5D, corePos.getZ() + 0.5D);
        if (distance > maxDistance * maxDistance) {
            stop();
            return;
        }

        // Fade instead of hard-cutting so moving around the edge of the zone sounds smoother.
        float fade = 1.0F - Mth.clamp((float) Math.sqrt(distance) / (float) maxDistance, 0F, 1F);
        this.volume = FCWClientConfig.RAID_MUSIC_VOLUME.get().floatValue() * (0.25F + (fade * 0.75F));
        this.x = corePos.getX() + 0.5D;
        this.y = corePos.getY() + 1.0D;
        this.z = corePos.getZ() + 0.5D;
    }
}
