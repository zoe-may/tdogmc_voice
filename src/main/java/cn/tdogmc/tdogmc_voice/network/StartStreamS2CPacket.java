package cn.tdogmc.tdogmc_voice.network;

import cn.tdogmc.tdogmc_voice.client.ClientStreamManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public class StartStreamS2CPacket {
    private final UUID streamId;
    private final double x, y, z;
    private final boolean isFollowingEntity;
    private final UUID entityToFollow;
    private final float range;
    private final float volume; // 新增：音量

    public StartStreamS2CPacket(UUID streamId, double x, double y, double z, float range, float volume) {
        this.streamId = streamId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.isFollowingEntity = false;
        this.entityToFollow = new UUID(0, 0);
        this.range = range;
        this.volume = volume;
    }

    public StartStreamS2CPacket(UUID streamId, UUID entityToFollow, float range, float volume) {
        this.streamId = streamId;
        this.x = 0; this.y = 0; this.z = 0;
        this.isFollowingEntity = true;
        this.entityToFollow = entityToFollow;
        this.range = range;
        this.volume = volume;
    }

    public StartStreamS2CPacket(FriendlyByteBuf buf) {
        this.streamId = buf.readUUID();
        this.isFollowingEntity = buf.readBoolean();
        if (this.isFollowingEntity) {
            this.entityToFollow = buf.readUUID();
            this.x = 0; this.y = 0; this.z = 0;
        } else {
            this.x = buf.readDouble();
            this.y = buf.readDouble();
            this.z = buf.readDouble();
            this.entityToFollow = new UUID(0, 0);
        }
        this.range = buf.readFloat();
        this.volume = buf.readFloat(); // 读取音量
    }

    public void toBytes(FriendlyByteBuf buf) {
        buf.writeUUID(this.streamId);
        buf.writeBoolean(this.isFollowingEntity);
        if (this.isFollowingEntity) {
            buf.writeUUID(this.entityToFollow);
        } else {
            buf.writeDouble(this.x);
            buf.writeDouble(this.y);
            buf.writeDouble(this.z);
        }
        buf.writeFloat(this.range);
        buf.writeFloat(this.volume); // 写入音量
    }

    public boolean handle(Supplier<NetworkEvent.Context> supplier) {
        supplier.get().enqueueWork(() -> {
            if (isFollowingEntity) {
                ClientStreamManager.startStream(streamId, entityToFollow, range, volume);
            } else {
                ClientStreamManager.startStream(streamId, x, y, z, range, volume);
            }
        });
        return true;
    }
}