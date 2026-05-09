package me.prostoac.captcha.protocol;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import me.prostoac.captcha.BlockPos;

public final class UseItemOnBlockPacket implements MinecraftPacket {

  private int hand;
  private BlockPos position = new BlockPos(0, 0, 0);

  @Override
  public void decode(ByteBuf buf, ProtocolUtils.Direction direction, ProtocolVersion protocolVersion) {
    this.hand = ProtocolUtils.readVarInt(buf); // hand
    this.position = readPosition(buf);
    ProtocolUtils.readVarInt(buf); // face/direction
    buf.readFloat(); // cursorX
    buf.readFloat(); // cursorY
    buf.readFloat(); // cursorZ
    buf.readBoolean(); // insideBlock
    if (protocolVersion.compareTo(ProtocolVersion.MINECRAFT_1_19) >= 0 && buf.isReadable()) {
      ProtocolUtils.readVarInt(buf); // sequence
    }
  }

  @Override
  public void encode(ByteBuf buf, ProtocolUtils.Direction direction, ProtocolVersion protocolVersion) {
    throw new IllegalStateException("Serverbound-only packet");
  }

  @Override
  public boolean handle(MinecraftSessionHandler handler) {
    handler.handleGeneric(this);
    return true;
  }

  public BlockPos position() {
    return this.position;
  }

  public int hand() {
    return this.hand;
  }

  private static BlockPos readPosition(ByteBuf buf) {
    long value = buf.readLong();
    int x = (int) (value >> 38);
    int y = (int) (value & 0xFFF);
    int z = (int) (value << 26 >> 38);
    if (y >= 2048) {
      y -= 4096;
    }
    return new BlockPos(x, y, z);
  }
}
