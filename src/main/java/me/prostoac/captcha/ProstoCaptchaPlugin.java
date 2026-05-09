package me.prostoac.captcha;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.prostoac.captcha.protocol.BlockDigPacket;
import me.prostoac.captcha.protocol.UseItemOnBlockPacket;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.chunk.VirtualBlock;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent;
import net.elytrium.limboapi.api.material.Block;
import net.elytrium.limboapi.api.material.Item;
import net.elytrium.limboapi.api.material.VirtualItem;
import net.elytrium.limboapi.api.player.GameMode;
import net.elytrium.limboapi.api.protocol.PacketDirection;
import net.elytrium.limboapi.api.protocol.packets.PacketMapping;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

@Plugin(
    id = "prostocaptcha",
    name = "ProstoCaptcha",
    version = "1.2.2",
    authors = {"PROSTOBOX"},
    dependencies = {@Dependency(id = "limboapi")}
)
public final class ProstoCaptchaPlugin {

  private static final double MAP_SPAWN_X = 0.5D;
  private static final double MAP_SPAWN_Z = 0.5D;

  private static final double FALL_SPAWN_X = 0.5D;
  private static final double FALL_SPAWN_Z = 0.5D;

  private static final int ISLAND_CENTER_X = 160;
  private static final int ISLAND_CENTER_Z = 0;
  private static final int ISLAND_BASE_Y = 63;
  private static final int ISLAND_HALF = 8;
  private static final int ISLAND_TOP_Y = ISLAND_BASE_Y + 2;
  private static final double ISLAND_SPAWN_X = ISLAND_CENTER_X + 0.5D;
  private static final double ISLAND_SPAWN_Y = ISLAND_TOP_Y + 1.0D;
  private static final double ISLAND_SPAWN_Z = ISLAND_CENTER_Z + 0.5D;

  private final Logger logger;
  private final ProxyServer server;
  private final LimboFactory limboFactory;
  private final Path dataDirectory;
  private final MiniMessage miniMessage = MiniMessage.miniMessage();

  private final Map<UUID, CaptchaSessionHandler> activeSessions = new ConcurrentHashMap<>();
  private final Map<UUID, TaskType> lastTaskByPlayer = new ConcurrentHashMap<>();
  private final Map<String, AntiVpnCacheEntry> antiVpnCache = new ConcurrentHashMap<>();

  private final Set<BlockPos> logBlocks = new HashSet<>();
  private final Set<BlockPos> stoneBlocks = new HashSet<>();
  private final Set<BlockPos> coalBlocks = new HashSet<>();
  private final Set<BlockPos> ironBlocks = new HashSet<>();
  private final Set<BlockPos> diamondBlocks = new HashSet<>();
  private final Map<BlockPos, StationType> stationBlocks = new HashMap<>();

  private Limbo limbo;
  private VirtualWorld world;
  private VirtualItem pickaxeItem;
  private VirtualItem axeItem;
  private VirtualItem airItem;
  private CaptchaConfig captchaConfig;
  private int mapY;
  private int fallY;
  private boolean lowMemoryMode;
  private int mapChunkPreloadRadius;
  private int islandChunkPreloadRadius;
  private int maxLastTaskCache;
  private boolean islandDecorEnabled;
  private int antiVpnCacheMaxEntries;

  @Inject
  public ProstoCaptchaPlugin(Logger logger, ProxyServer server, @DataDirectory Path dataDirectory) {
    this.logger = logger;
    this.server = server;
    this.dataDirectory = dataDirectory;

    PluginContainer limboApiContainer = this.server.getPluginManager().getPlugin("limboapi").orElseThrow();
    this.limboFactory = (LimboFactory) limboApiContainer.getInstance().orElseThrow();
  }

  @Subscribe
  public void onProxyInitialize(ProxyInitializeEvent event) {
    this.captchaConfig = CaptchaConfig.load(this.dataDirectory, this.logger);
    this.mapY = this.captchaConfig.mapY();
    this.fallY = this.captchaConfig.fallY();
    this.lowMemoryMode = this.captchaConfig.boolValue("performance.low-memory-mode", true);
    this.mapChunkPreloadRadius = this.captchaConfig.intValue(
        "performance.map-chunk-preload-radius",
        this.lowMemoryMode ? 1 : 2,
        0,
        4
    );
    this.islandChunkPreloadRadius = this.captchaConfig.intValue(
        "performance.island-chunk-preload-radius",
        this.lowMemoryMode ? 2 : 4,
        0,
        6
    );
    this.maxLastTaskCache = this.captchaConfig.intValue(
        "performance.max-last-task-cache",
        this.lowMemoryMode ? 1024 : 4096,
        64,
        200000
    );
    this.islandDecorEnabled = this.captchaConfig.boolValue("performance.decor.enabled", !this.lowMemoryMode);
    this.antiVpnCacheMaxEntries = this.captchaConfig.intValue(
        "anti-vpn.cache-max-entries",
        this.lowMemoryMode ? 2048 : 4096,
        128,
        50000
    );
    this.antiVpnCache.clear();

    buildWorldAndLimbo();
    this.logger.info(
        "[ProstoCaptcha] low-memory={}, mapRadius={}, islandRadius={}, taskCacheMax={}, decor={}, antiVpnCacheMax={}",
        this.lowMemoryMode,
        this.mapChunkPreloadRadius,
        this.islandChunkPreloadRadius,
        this.maxLastTaskCache,
        this.islandDecorEnabled,
        this.antiVpnCacheMaxEntries
    );
    this.logger.info("[ProstoCaptcha] Loaded. Captcha limbo is ready.");
  }

  @Subscribe(order = PostOrder.FIRST)
  public void onLogin(LoginLimboRegisterEvent event) {
    Player player = event.getPlayer();
    if (player.hasPermission("prostocaptcha.bypass")) {
      return;
    }
    if (this.limbo == null) {
      this.logger.warn("[ProstoCaptcha] Limbo is not initialized, skipping captcha for {}", player.getUsername());
      return;
    }

    event.addOnJoinCallback(() -> sendPlayerToCaptcha(player));
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    this.activeSessions.remove(event.getPlayer().getUniqueId());
  }

  @Subscribe(order = PostOrder.FIRST)
  public EventTask onPreLogin(PreLoginEvent event) {
    return EventTask.async(() -> handleAntiVpnPreLogin(event));
  }

  private void handleAntiVpnPreLogin(PreLoginEvent event) {
    if (!this.captchaConfig.boolValue("anti-vpn.enabled", false)) {
      return;
    }

    String ip = resolveIp(event.getConnection().getRemoteAddress());
    if (ip == null || ip.isBlank()) {
      return;
    }

    String username = event.getUsername() == null ? "" : event.getUsername().trim();
    if (isAntiVpnWhitelistedIp(ip) || isAntiVpnWhitelistedNick(username)) {
      return;
    }

    AntiVpnDecision decision = resolveAntiVpnDecision(ip);
    if (!decision.blocked) {
      return;
    }

    String reason = this.captchaConfig.text(
        "anti-vpn.kick-message",
        "<red><bold>VPN/Proxy detected.</bold></red><newline><gray>Disable VPN/Proxy and reconnect.</gray>"
    );
    Component message = this.miniMessage.deserialize(
        applyPlaceholders(
            reason,
            Map.of(
                "ip", ip,
                "provider", decision.provider == null ? "-" : decision.provider,
                "reason", decision.reason == null ? "-" : decision.reason
            )
        )
    );
    event.setResult(PreLoginEvent.PreLoginComponentResult.denied(message));
  }

  private void sendPlayerToCaptcha(Player player) {
    try {
      this.limbo.spawnPlayer(player, new CaptchaSessionHandler(this, player));
    } catch (Throwable throwable) {
      this.logger.error("[ProstoCaptcha] Failed to spawn {} into captcha limbo", player.getUsername(), throwable);
    }
  }

  private void buildWorldAndLimbo() {
    this.logBlocks.clear();
    this.stoneBlocks.clear();
    this.coalBlocks.clear();
    this.ironBlocks.clear();
    this.diamondBlocks.clear();
    this.stationBlocks.clear();

    this.world = this.limboFactory.createVirtualWorld(Dimension.OVERWORLD, MAP_SPAWN_X, this.mapY, MAP_SPAWN_Z, 0.0F, 0.0F);

    preloadChunksAround((int) MAP_SPAWN_X, (int) MAP_SPAWN_Z, this.mapChunkPreloadRadius);
    preloadChunksAround(ISLAND_CENTER_X, ISLAND_CENTER_Z, this.islandChunkPreloadRadius);

    VirtualBlock air = this.limboFactory.createSimpleBlock(Block.AIR);
    VirtualBlock grass = this.limboFactory.createSimpleBlock(Block.GRASS);
    VirtualBlock dirt = this.limboFactory.createSimpleBlock(Block.DIRT);
    VirtualBlock stone = this.limboFactory.createSimpleBlock(Block.STONE);
    VirtualBlock log = this.limboFactory.createSimpleBlock(Block.LOG);
    VirtualBlock leaves = this.limboFactory.createSimpleBlock(Block.LEAVES);
    VirtualBlock coalOre = this.limboFactory.createSimpleBlock(Block.COAL_ORE);
    VirtualBlock ironOre = this.limboFactory.createSimpleBlock(Block.IRON_ORE);
    VirtualBlock diamondOre = this.limboFactory.createSimpleBlock(Block.DIAMOND_ORE);
    VirtualBlock chest = this.limboFactory.createSimpleBlock(Block.CHEST);
    VirtualBlock craftingTable = this.limboFactory.createSimpleBlock(Block.CRAFTING_TABLE);
    VirtualBlock furnace = this.limboFactory.createSimpleBlock(Block.FURNACE);
    VirtualBlock anvil = this.limboFactory.createSimpleBlock(Block.ANVIL);
    VirtualBlock flowerYellow = this.limboFactory.createSimpleBlock(Block.YELLOW_FLOWER);
    VirtualBlock flowerRed = this.limboFactory.createSimpleBlock(Block.RED_FLOWER);
    VirtualBlock tallGrass = this.limboFactory.createSimpleBlock(Block.TALLGRASS);

    buildMapZone(air);
    buildIslandTerrain(grass, dirt);
    buildIslandResources(air, dirt, stone, coalOre, ironOre, diamondOre, craftingTable, furnace, anvil, chest);
    if (this.islandDecorEnabled) {
      buildIslandDecor(log, leaves, flowerYellow, flowerRed, tallGrass);
    }

    this.world.fillSkyLight(15);
    if (!this.lowMemoryMode) {
      this.world.fillBlockLight(15);
    }

    if (this.limbo != null) {
      this.limbo.dispose();
    }

    this.limbo = this.limboFactory.createLimbo(this.world)
        .setName("ProstoCaptcha")
        .setReadTimeout(45_000)
        .setWorldTime(6000L)
        .setGameMode(GameMode.SURVIVAL)
        .setShouldRespawn(true)
        .setShouldUpdateTags(false)
        .setShouldRejoin(false)
        .registerPacket(
            PacketDirection.SERVERBOUND,
            BlockDigPacket.class,
            BlockDigPacket::new,
            new PacketMapping[]{
                new PacketMapping(0x1B, ProtocolVersion.MINECRAFT_1_16, false),
                new PacketMapping(0x1A, ProtocolVersion.MINECRAFT_1_17, false),
                new PacketMapping(0x1C, ProtocolVersion.MINECRAFT_1_19, false),
                new PacketMapping(0x1D, ProtocolVersion.MINECRAFT_1_19_4, false),
                new PacketMapping(0x20, ProtocolVersion.MINECRAFT_1_20_2, false),
                new PacketMapping(0x21, ProtocolVersion.MINECRAFT_1_20_3, false),
                new PacketMapping(0x24, ProtocolVersion.MINECRAFT_1_20_5, false),
                new PacketMapping(0x27, ProtocolVersion.MINECRAFT_1_21_4, false),
            }
        )
        .registerPacket(
            PacketDirection.SERVERBOUND,
            UseItemOnBlockPacket.class,
            UseItemOnBlockPacket::new,
            new PacketMapping[]{
                new PacketMapping(0x2E, ProtocolVersion.MINECRAFT_1_16, false),
                new PacketMapping(0x30, ProtocolVersion.MINECRAFT_1_19, false),
                new PacketMapping(0x31, ProtocolVersion.MINECRAFT_1_19_3, false),
                new PacketMapping(0x34, ProtocolVersion.MINECRAFT_1_20_2, false),
                new PacketMapping(0x35, ProtocolVersion.MINECRAFT_1_20_3, false),
                new PacketMapping(0x38, ProtocolVersion.MINECRAFT_1_20_5, false),
                new PacketMapping(0x3C, ProtocolVersion.MINECRAFT_1_21_4, false),
            }
        );

    this.pickaxeItem = this.limboFactory.getItem(Item.IRON_PICKAXE);
    this.axeItem = this.limboFactory.getItem(Item.IRON_AXE);
    this.airItem = this.limboFactory.getItem(Item.AIR);
  }

  private void buildMapZone(VirtualBlock air) {
    for (int x = -8; x <= 8; x++) {
      for (int z = -8; z <= 8; z++) {
        this.world.setBlock(x, this.mapY - 1, z, air);
        this.world.setBlock(x, this.mapY, z, air);
        this.world.setBlock(x, this.mapY + 1, z, air);
      }
    }
  }

  private void buildIslandTerrain(VirtualBlock grass, VirtualBlock dirt) {
    for (int x = ISLAND_CENTER_X - ISLAND_HALF; x <= ISLAND_CENTER_X + ISLAND_HALF; x++) {
      for (int z = ISLAND_CENTER_Z - ISLAND_HALF; z <= ISLAND_CENTER_Z + ISLAND_HALF; z++) {
        for (int y = ISLAND_BASE_Y; y < ISLAND_TOP_Y; y++) {
          this.world.setBlock(x, y, z, dirt);
        }
        this.world.setBlock(x, ISLAND_TOP_Y, z, grass);
      }
    }
  }

  private void buildIslandResources(
      VirtualBlock air,
      VirtualBlock dirt,
      VirtualBlock stone,
      VirtualBlock coalOre,
      VirtualBlock ironOre,
      VirtualBlock diamondOre,
      VirtualBlock craftingTable,
      VirtualBlock furnace,
      VirtualBlock anvil,
      VirtualBlock chest
  ) {
    int stationY = ISLAND_TOP_Y + 1;

    placeStation(ISLAND_CENTER_X - 1, stationY, ISLAND_CENTER_Z, StationType.CRAFTING_TABLE, craftingTable);
    placeStation(ISLAND_CENTER_X, stationY, ISLAND_CENTER_Z, StationType.FURNACE, furnace);
    placeStation(ISLAND_CENTER_X + 1, stationY, ISLAND_CENTER_Z, StationType.ANVIL, anvil);
    placeStation(ISLAND_CENTER_X + 2, stationY, ISLAND_CENTER_Z, StationType.CHEST, chest);

    // Bury resources into the ground and expose them via small shafts.
    // Each shaft stores 2 blocks, so players can mine up to 3 by combining shafts.
    placeBuriedResourceShaft(ISLAND_CENTER_X - 6, ISLAND_CENTER_Z - 5, air, dirt, stone, OreKind.STONE);
    placeBuriedResourceShaft(ISLAND_CENTER_X - 5, ISLAND_CENTER_Z - 5, air, dirt, stone, OreKind.STONE);

    placeBuriedResourceShaft(ISLAND_CENTER_X + 6, ISLAND_CENTER_Z - 5, air, dirt, coalOre, OreKind.COAL);
    placeBuriedResourceShaft(ISLAND_CENTER_X + 5, ISLAND_CENTER_Z - 5, air, dirt, coalOre, OreKind.COAL);

    placeBuriedResourceShaft(ISLAND_CENTER_X - 6, ISLAND_CENTER_Z + 5, air, dirt, ironOre, OreKind.IRON);
    placeBuriedResourceShaft(ISLAND_CENTER_X - 5, ISLAND_CENTER_Z + 5, air, dirt, ironOre, OreKind.IRON);

    placeBuriedResourceShaft(ISLAND_CENTER_X + 6, ISLAND_CENTER_Z + 5, air, dirt, diamondOre, OreKind.DIAMOND);
    placeBuriedResourceShaft(ISLAND_CENTER_X + 5, ISLAND_CENTER_Z + 5, air, dirt, diamondOre, OreKind.DIAMOND);
  }

  private void placeBuriedResourceShaft(
      int x,
      int z,
      VirtualBlock air,
      VirtualBlock dirt,
      VirtualBlock resource,
      OreKind kind
  ) {
    int top = ISLAND_TOP_Y;
    int first = ISLAND_TOP_Y - 1;
    int second = ISLAND_TOP_Y - 2;

    this.world.setBlock(x, top + 1, z, air);
    this.world.setBlock(x, top, z, air);
    placeResource(x, first, z, resource, kind);
    placeResource(x, second, z, resource, kind);

    // Natural-looking dirt around each shaft.
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        if (dx == 0 && dz == 0) {
          continue;
        }
        this.world.setBlock(x + dx, first, z + dz, dirt);
        this.world.setBlock(x + dx, second, z + dz, dirt);
      }
    }
  }

  private void placeResource(int x, int y, int z, VirtualBlock block, OreKind kind) {
    BlockPos pos = new BlockPos(x, y, z);
    this.world.setBlock(x, y, z, block);
    switch (kind) {
      case STONE -> this.stoneBlocks.add(pos);
      case COAL -> this.coalBlocks.add(pos);
      case IRON -> this.ironBlocks.add(pos);
      case DIAMOND -> this.diamondBlocks.add(pos);
    }
  }

  private void buildIslandDecor(VirtualBlock log, VirtualBlock leaves, VirtualBlock flowerYellow, VirtualBlock flowerRed, VirtualBlock tallGrass) {
    placeTree(ISLAND_CENTER_X - 4, ISLAND_TOP_Y + 1, ISLAND_CENTER_Z + 3, log, leaves);
    placeTree(ISLAND_CENTER_X + 4, ISLAND_TOP_Y + 1, ISLAND_CENTER_Z - 4, log, leaves);
    this.world.setBlock(ISLAND_CENTER_X - 2, ISLAND_TOP_Y + 1, ISLAND_CENTER_Z - 3, flowerYellow);
    this.world.setBlock(ISLAND_CENTER_X + 3, ISLAND_TOP_Y + 1, ISLAND_CENTER_Z + 2, flowerRed);
    this.world.setBlock(ISLAND_CENTER_X, ISLAND_TOP_Y + 1, ISLAND_CENTER_Z + 4, tallGrass);
  }

  private void placeTree(int x, int y, int z, VirtualBlock log, VirtualBlock leaves) {
    placeLog(x, y, z, log);
    placeLog(x, y + 1, z, log);
    placeLog(x, y + 2, z, log);

    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        this.world.setBlock(x + dx, y + 3, z + dz, leaves);
      }
    }
    this.world.setBlock(x, y + 4, z, leaves);
  }

  private void preloadChunksAround(int blockX, int blockZ, int radiusChunks) {
    int chunkX = floorDiv(blockX, 16);
    int chunkZ = floorDiv(blockZ, 16);
    for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
      for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
        this.world.getChunkOrNew((chunkX + dx) * 16, (chunkZ + dz) * 16);
      }
    }
  }

  private static int floorDiv(int value, int divisor) {
    int result = value / divisor;
    if ((value ^ divisor) < 0 && result * divisor != value) {
      result--;
    }
    return result;
  }

  private void placeLog(int x, int y, int z, VirtualBlock block) {
    BlockPos pos = new BlockPos(x, y, z);
    this.logBlocks.add(pos);
    this.world.setBlock(x, y, z, block);
  }

  private void placeStone(int x, int y, int z, VirtualBlock block) {
    BlockPos pos = new BlockPos(x, y, z);
    this.stoneBlocks.add(pos);
    this.world.setBlock(x, y, z, block);
  }

  private void placeStation(int x, int y, int z, StationType stationType, VirtualBlock block) {
    BlockPos pos = new BlockPos(x, y, z);
    this.stationBlocks.put(pos, stationType);
    this.world.setBlock(x, y, z, block);
  }

  public List<CaptchaTask> buildQuestPlan(UUID playerId) {
    TaskType[] values = TaskType.values();
    TaskType previous = this.lastTaskByPlayer.get(playerId);
    TaskType selected = values[ThreadLocalRandom.current().nextInt(values.length)];

    if (values.length > 1 && previous != null && selected == previous) {
      int guard = 0;
      while (selected == previous && guard++ < 16) {
        selected = values[ThreadLocalRandom.current().nextInt(values.length)];
      }
    }

    rememberLastTask(playerId, selected);
    int required = selected.isBreakTask() ? ThreadLocalRandom.current().nextInt(1, 4) : 1;
    return List.of(new CaptchaTask(selected, required));
  }

  private void rememberLastTask(UUID playerId, TaskType selected) {
    this.lastTaskByPlayer.put(playerId, selected);
    int overflow = this.lastTaskByPlayer.size() - this.maxLastTaskCache;
    if (overflow <= 0) {
      return;
    }
    var iterator = this.lastTaskByPlayer.keySet().iterator();
    while (overflow > 0 && iterator.hasNext()) {
      iterator.next();
      iterator.remove();
      overflow--;
    }
  }

  private String resolveIp(SocketAddress address) {
    if (!(address instanceof InetSocketAddress inet)) {
      return null;
    }
    if (inet.getAddress() == null) {
      return null;
    }
    return inet.getAddress().getHostAddress();
  }

  private boolean isAntiVpnWhitelistedIp(String ip) {
    if (ip == null || ip.isBlank()) {
      return false;
    }
    String normalized = ip.trim();
    String raw = this.captchaConfig.text("anti-vpn.whitelist-ips", "");
    if (raw.isBlank()) {
      return false;
    }
    for (String token : raw.split(",")) {
      String value = token.trim();
      if (!value.isEmpty() && normalized.equals(value)) {
        return true;
      }
    }
    return false;
  }

  private boolean isAntiVpnWhitelistedNick(String username) {
    if (username == null || username.isBlank()) {
      return false;
    }
    String raw = this.captchaConfig.text("anti-vpn.whitelist-nicks", "");
    if (raw.isBlank()) {
      return false;
    }
    for (String token : raw.split(",")) {
      String value = token.trim();
      if (!value.isEmpty() && username.equalsIgnoreCase(value)) {
        return true;
      }
    }
    return false;
  }

  private AntiVpnDecision resolveAntiVpnDecision(String ip) {
    long now = System.currentTimeMillis();
    AntiVpnCacheEntry cached = this.antiVpnCache.get(ip);
    if (cached != null && cached.expiresAt > now) {
      return cached.decision;
    }

    int timeoutMs = this.captchaConfig.intValue("anti-vpn.timeout-ms", 2500, 500, 10000);
    boolean failOpen = this.captchaConfig.boolValue("anti-vpn.fail-open", true);
    boolean anyChecked = false;

    AntiVpnDecision proxycheck = checkWithProxycheck(ip, timeoutMs);
    if (proxycheck.checked) {
      anyChecked = true;
      if (proxycheck.blocked) {
        cacheAntiVpnDecision(ip, proxycheck, now);
        return proxycheck;
      }
    }

    AntiVpnDecision ipApi = checkWithIpApi(ip, timeoutMs);
    if (ipApi.checked) {
      anyChecked = true;
      if (ipApi.blocked) {
        cacheAntiVpnDecision(ip, ipApi, now);
        return ipApi;
      }
    }

    AntiVpnDecision decision;
    if (!anyChecked && !failOpen) {
      decision = new AntiVpnDecision(true, true, "anti-vpn", "verification_unavailable");
    } else {
      decision = new AntiVpnDecision(false, anyChecked, "anti-vpn", "clean");
    }
    cacheAntiVpnDecision(ip, decision, now);
    return decision;
  }

  private void cacheAntiVpnDecision(String ip, AntiVpnDecision decision, long now) {
    long ttlSec = this.captchaConfig.longValue("anti-vpn.cache-seconds", 21600L, 30L, 86400L);
    this.antiVpnCache.put(ip, new AntiVpnCacheEntry(decision, now + ttlSec * 1000L));
    if (this.antiVpnCache.size() <= this.antiVpnCacheMaxEntries) {
      return;
    }
    this.antiVpnCache.clear();
  }

  private AntiVpnDecision checkWithProxycheck(String ip, int timeoutMs) {
    if (!this.captchaConfig.boolValue("anti-vpn.providers.proxycheck.enabled", true)) {
      return AntiVpnDecision.notChecked("proxycheck");
    }

    String urlTemplate = this.captchaConfig.text("anti-vpn.providers.proxycheck.url", "");
    if (urlTemplate.isBlank()) {
      urlTemplate = "https://proxycheck.io/v2/%ip%?vpn=1&risk=1&asn=1";
    }

    String url = urlTemplate.replace("%ip%", ip);
    String apiKey = this.captchaConfig.text("anti-vpn.providers.proxycheck.api-key", "");
    if (!apiKey.isBlank() && !url.contains("key=")) {
      url = appendQueryParam(url, "key=" + apiKey);
    }

    String body = httpGet(url, timeoutMs);
    if (body == null || body.isBlank()) {
      return AntiVpnDecision.notChecked("proxycheck");
    }

    String lower = body.toLowerCase(Locale.ROOT);
    if (lower.contains("\"status\":\"denied\"")
        || lower.contains("\"status\":\"error\"")
        || lower.contains("\"status\":\"warning\"")) {
      return AntiVpnDecision.notChecked("proxycheck");
    }

    boolean blockVpn = this.captchaConfig.boolValue("anti-vpn.providers.proxycheck.block-vpn", true);
    boolean blockProxy = this.captchaConfig.boolValue("anti-vpn.providers.proxycheck.block-proxy", true);
    boolean blockTor = this.captchaConfig.boolValue("anti-vpn.providers.proxycheck.block-tor", true);
    boolean blockHosting = this.captchaConfig.boolValue("anti-vpn.providers.proxycheck.block-hosting", true);
    int riskThreshold = this.captchaConfig.intValue("anti-vpn.providers.proxycheck.risk-threshold", 66, 0, 100);

    boolean isProxy = lower.contains("\"proxy\":\"yes\"") || lower.contains("\"proxy\":true");
    boolean isVpn = lower.contains("\"vpn\":\"yes\"") || lower.contains("\"vpn\":true") || lower.contains("\"type\":\"vpn\"");
    boolean isTor = lower.contains("\"type\":\"tor\"") || lower.contains("\"tor\":true");
    boolean isHosting = lower.contains("\"type\":\"hosting\"") || lower.contains("\"hosting\":true");
    int risk = extractJsonInt(lower, "risk", -1);

    List<String> triggers = new ArrayList<>();
    if (blockProxy && isProxy) {
      triggers.add("proxy");
    }
    if (blockVpn && isVpn) {
      triggers.add("vpn");
    }
    if (blockTor && isTor) {
      triggers.add("tor");
    }
    if (blockHosting && isHosting) {
      triggers.add("hosting");
    }
    if (riskThreshold > 0 && risk >= riskThreshold) {
      triggers.add("risk:" + risk);
    }

    if (!triggers.isEmpty()) {
      return new AntiVpnDecision(true, true, "proxycheck", String.join(",", triggers));
    }
    return new AntiVpnDecision(false, true, "proxycheck", "clean");
  }

  private AntiVpnDecision checkWithIpApi(String ip, int timeoutMs) {
    if (!this.captchaConfig.boolValue("anti-vpn.providers.ip-api.enabled", true)) {
      return AntiVpnDecision.notChecked("ip-api");
    }

    String urlTemplate = this.captchaConfig.text("anti-vpn.providers.ip-api.url", "");
    if (urlTemplate.isBlank()) {
      urlTemplate = "http://ip-api.com/json/%ip%?fields=status,message,proxy,hosting,query";
    }

    String url = urlTemplate.replace("%ip%", ip);
    String body = httpGet(url, timeoutMs);
    if (body == null || body.isBlank()) {
      return AntiVpnDecision.notChecked("ip-api");
    }

    String lower = body.toLowerCase(Locale.ROOT);
    if (lower.contains("\"status\":\"fail\"")) {
      return AntiVpnDecision.notChecked("ip-api");
    }

    boolean blockProxy = this.captchaConfig.boolValue("anti-vpn.providers.ip-api.block-proxy", true);
    boolean blockHosting = this.captchaConfig.boolValue("anti-vpn.providers.ip-api.block-hosting", true);
    boolean proxy = lower.contains("\"proxy\":true");
    boolean hosting = lower.contains("\"hosting\":true");

    List<String> triggers = new ArrayList<>();
    if (blockProxy && proxy) {
      triggers.add("proxy");
    }
    if (blockHosting && hosting) {
      triggers.add("hosting");
    }

    if (!triggers.isEmpty()) {
      return new AntiVpnDecision(true, true, "ip-api", String.join(",", triggers));
    }
    return new AntiVpnDecision(false, true, "ip-api", "clean");
  }

  private String appendQueryParam(String url, String param) {
    if (url == null || url.isBlank() || param == null || param.isBlank()) {
      return url;
    }
    if (url.contains("?")) {
      if (url.endsWith("?") || url.endsWith("&")) {
        return url + param;
      }
      return url + "&" + param;
    }
    return url + "?" + param;
  }

  private String httpGet(String endpoint, int timeoutMs) {
    HttpURLConnection connection = null;
    InputStream input = null;
    try {
      URL url = new URL(endpoint);
      connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("GET");
      connection.setConnectTimeout(timeoutMs);
      connection.setReadTimeout(timeoutMs);
      connection.setRequestProperty("User-Agent", "ProstoCaptcha/1.2.2");
      connection.setRequestProperty("Accept", "application/json");

      int code = connection.getResponseCode();
      if (code < 200 || code >= 300) {
        input = connection.getErrorStream();
        if (input == null) {
          return null;
        }
        return readStreamUtf8(input);
      }

      input = connection.getInputStream();
      return readStreamUtf8(input);
    } catch (Exception ignored) {
      return null;
    } finally {
      if (input != null) {
        try {
          input.close();
        } catch (IOException ignored) {
        }
      }
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  private String readStreamUtf8(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[2048];
    int read;
    while ((read = input.read(buffer)) != -1) {
      output.write(buffer, 0, read);
    }
    return output.toString(StandardCharsets.UTF_8);
  }

  private int extractJsonInt(String json, String field, int fallback) {
    if (json == null || field == null || field.isEmpty()) {
      return fallback;
    }
    Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"?(\\d{1,3})\"?", Pattern.CASE_INSENSITIVE)
        .matcher(json);
    if (!matcher.find()) {
      return fallback;
    }
    try {
      return Integer.parseInt(matcher.group(1));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  public boolean matchesBreakTask(TaskType type, BlockPos pos) {
    return switch (type) {
      case BREAK_LOG -> this.logBlocks.contains(pos);
      case BREAK_STONE -> this.stoneBlocks.contains(pos);
      case BREAK_COAL -> this.coalBlocks.contains(pos);
      case BREAK_IRON -> this.ironBlocks.contains(pos);
      case BREAK_DIAMOND -> this.diamondBlocks.contains(pos);
      default -> false;
    };
  }

  public StationType stationAt(BlockPos pos) {
    return this.stationBlocks.get(pos);
  }

  public boolean matchesStationTask(TaskType type, StationType stationType) {
    if (stationType == null) {
      return false;
    }
    return switch (type) {
      case CRAFT_PLANKS, CRAFT_ITEM, OPEN_CLOSE_CRAFTING -> stationType == StationType.CRAFTING_TABLE;
      case SMELT_IRON, OPEN_CLOSE_FURNACE -> stationType == StationType.FURNACE;
      case OPEN_CLOSE_CHEST -> stationType == StationType.CHEST;
      case OPEN_CLOSE_ANVIL -> stationType == StationType.ANVIL;
      default -> false;
    };
  }

  public void registerSession(Player player, CaptchaSessionHandler handler) {
    this.activeSessions.put(player.getUniqueId(), handler);
  }

  public void unregisterSession(Player player) {
    this.activeSessions.remove(player.getUniqueId());
  }

  public VirtualItem getPickaxeItem() {
    return this.pickaxeItem;
  }

  public VirtualItem getAxeItem() {
    return this.axeItem;
  }

  public VirtualItem getAirItem() {
    return this.airItem;
  }

  public CaptchaConfig config() {
    return this.captchaConfig;
  }

  public int mapTimeoutSeconds() {
    return this.captchaConfig.mapTimeoutSeconds();
  }

  public int fallCheckSeconds() {
    return this.captchaConfig.fallCheckSeconds();
  }

  public int minFallDistance() {
    return this.captchaConfig.minFallDistance();
  }

  public boolean kickOnWrongAction() {
    return this.captchaConfig.kickOnWrongAction();
  }

  public long interactCooldownMs() {
    return this.captchaConfig.interactCooldownMs();
  }

  public long closeReclickMs() {
    return this.captchaConfig.closeReclickMs();
  }

  public int mapCodeLength() {
    return this.captchaConfig.mapCodeLength();
  }

  public String mapAlphabet() {
    return this.captchaConfig.mapAlphabet();
  }

  public double mapSpawnX() {
    return MAP_SPAWN_X;
  }

  public double mapSpawnY() {
    return this.mapY;
  }

  public double mapSpawnZ() {
    return MAP_SPAWN_Z;
  }

  public double fallSpawnX() {
    return FALL_SPAWN_X;
  }

  public double fallSpawnY() {
    return this.fallY;
  }

  public double fallSpawnZ() {
    return FALL_SPAWN_Z;
  }

  public double islandSpawnX() {
    return ISLAND_SPAWN_X;
  }

  public double islandSpawnY() {
    return ISLAND_SPAWN_Y;
  }

  public double islandSpawnZ() {
    return ISLAND_SPAWN_Z;
  }

  public boolean shouldReturnToIsland(double x, double y, double z) {
    if (y < this.captchaConfig.islandMinY()) {
      return true;
    }
    double minX = ISLAND_CENTER_X - ISLAND_HALF - 3;
    double maxX = ISLAND_CENTER_X + ISLAND_HALF + 3;
    double minZ = ISLAND_CENTER_Z - ISLAND_HALF - 3;
    double maxZ = ISLAND_CENTER_Z + ISLAND_HALF + 3;
    return x < minX || x > maxX || z < minZ || z > maxZ;
  }

  public Component message(String key, String fallback, Map<String, String> placeholders) {
    String template = this.captchaConfig.text(key, fallback);
    String prefixed = this.captchaConfig.text("messages.prefix", "") + template;
    return this.miniMessage.deserialize(applyPlaceholders(prefixed, placeholders));
  }

  public Component messageNoPrefix(String key, String fallback, Map<String, String> placeholders) {
    String template = this.captchaConfig.text(key, fallback);
    return this.miniMessage.deserialize(applyPlaceholders(template, placeholders));
  }

  public String taskText(TaskType type) {
    return switch (type) {
      case BREAK_LOG -> this.captchaConfig.text("tasks.break-log", "Сломай дерево");
      case BREAK_STONE -> this.captchaConfig.text("tasks.break-stone", "Добудь камень");
      case BREAK_IRON -> this.captchaConfig.text("tasks.break-iron", "Добудь железо");
      case BREAK_COAL -> this.captchaConfig.text("tasks.break-coal", "Добудь уголь");
      case BREAK_DIAMOND -> this.captchaConfig.text("tasks.break-diamond", "Добудь алмаз");
      case CRAFT_PLANKS -> this.captchaConfig.text("tasks.craft-planks", "Сделай доски");
      case SMELT_IRON -> this.captchaConfig.text("tasks.smelt-iron", "Переплавь железо");
      case CRAFT_ITEM -> this.captchaConfig.text("tasks.craft-item", "Скрафти предмет");
      case OPEN_CLOSE_CHEST -> this.captchaConfig.text("tasks.open-close-chest", "Открой и закрой сундук");
      case OPEN_CLOSE_CRAFTING -> this.captchaConfig.text("tasks.open-close-crafting", "Открой и закрой верстак");
      case OPEN_CLOSE_FURNACE -> this.captchaConfig.text("tasks.open-close-furnace", "Открой и закрой печку");
      case OPEN_CLOSE_ANVIL -> this.captchaConfig.text("tasks.open-close-anvil", "Открой и закрой наковальню");
    };
  }

  private String applyPlaceholders(String value, Map<String, String> placeholders) {
    String out = value == null ? "" : value;
    if (placeholders == null || placeholders.isEmpty()) {
      return out;
    }
    for (Map.Entry<String, String> entry : placeholders.entrySet()) {
      String key = entry.getKey();
      String replacement = entry.getValue() == null ? "" : entry.getValue();
      out = out.replace("{" + key + "}", replacement);
      out = out.replace("%" + key + "%", replacement);
    }
    return out;
  }

  private static final class AntiVpnDecision {
    private final boolean blocked;
    private final boolean checked;
    private final String provider;
    private final String reason;

    private AntiVpnDecision(boolean blocked, boolean checked, String provider, String reason) {
      this.blocked = blocked;
      this.checked = checked;
      this.provider = provider;
      this.reason = reason;
    }

    private static AntiVpnDecision notChecked(String provider) {
      return new AntiVpnDecision(false, false, provider, "not_checked");
    }
  }

  private static final class AntiVpnCacheEntry {
    private final AntiVpnDecision decision;
    private final long expiresAt;

    private AntiVpnCacheEntry(AntiVpnDecision decision, long expiresAt) {
      this.decision = decision;
      this.expiresAt = expiresAt;
    }
  }

  private enum OreKind {
    STONE,
    COAL,
    IRON,
    DIAMOND
  }

  public enum StationType {
    CHEST,
    CRAFTING_TABLE,
    FURNACE,
    ANVIL
  }

  public enum TaskType {
    BREAK_LOG(true, false),
    BREAK_STONE(true, false),
    BREAK_IRON(true, false),
    BREAK_COAL(true, false),
    BREAK_DIAMOND(true, false),
    CRAFT_PLANKS(false, true),
    SMELT_IRON(false, true),
    CRAFT_ITEM(false, true),
    OPEN_CLOSE_CHEST(false, true),
    OPEN_CLOSE_CRAFTING(false, true),
    OPEN_CLOSE_FURNACE(false, true),
    OPEN_CLOSE_ANVIL(false, true);

    private final boolean breakTask;
    private final boolean interactTask;

    TaskType(boolean breakTask, boolean interactTask) {
      this.breakTask = breakTask;
      this.interactTask = interactTask;
    }

    public boolean isBreakTask() {
      return this.breakTask;
    }

    public boolean isInteractTask() {
      return this.interactTask;
    }
  }

  public record CaptchaTask(TaskType type, int requiredCount) {
    public String id() {
      return this.type.name().toLowerCase(Locale.ROOT);
    }
  }
}
