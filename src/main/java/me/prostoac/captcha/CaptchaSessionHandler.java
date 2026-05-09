package me.prostoac.captcha;

import com.velocitypowered.api.proxy.Player;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import me.prostoac.captcha.ProstoCaptchaPlugin.CaptchaTask;
import me.prostoac.captcha.ProstoCaptchaPlugin.StationType;
import me.prostoac.captcha.ProstoCaptchaPlugin.TaskType;
import me.prostoac.captcha.protocol.BlockDigPacket;
import me.prostoac.captcha.protocol.UseItemOnBlockPacket;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.player.GameMode;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

public final class CaptchaSessionHandler implements LimboSessionHandler {

  private final ProstoCaptchaPlugin plugin;
  private final Player proxyPlayer;

  private final Set<BlockPos> consumedBreakBlocks = new HashSet<>();

  private LimboPlayer limboPlayer;
  private BossBar bossBar;
  private ScheduledFuture<?> mapTimeoutFuture;
  private ScheduledFuture<?> fallCompleteFuture;

  private Stage stage = Stage.MAP_CAPTCHA;
  private boolean finished;
  private int wrongCaptchaAttempts;

  private String mapAnswer = "";
  private long mapStartedAt;

  private long fallStartedAt;
  private double fallStartX;
  private double fallStartZ;
  private double fallMinY;
  private double lastMoveY;
  private boolean hasLastMoveY;
  private int fallMovePackets;

  private List<CaptchaTask> questPlan = List.of();
  private int taskIndex;
  private int taskProgress;

  private long lastInteractAt;
  private boolean waitingForClose;
  private StationType waitingForCloseStation;
  private TaskType waitingForCloseTask;
  private long waitingForCloseAt;

  private long lastReturnTeleportAt;

  public CaptchaSessionHandler(ProstoCaptchaPlugin plugin, Player proxyPlayer) {
    this.plugin = plugin;
    this.proxyPlayer = proxyPlayer;
  }

  @Override
  public void onSpawn(Limbo server, LimboPlayer player) {
    this.limboPlayer = player;
    this.plugin.registerSession(this.proxyPlayer, this);

    player.setGameMode(GameMode.SURVIVAL);
    player.sendAbilities();
    player.disableFalling();
    player.teleport(this.plugin.mapSpawnX(), this.plugin.mapSpawnY(), this.plugin.mapSpawnZ(), 0.0F, 0.0F);

    showBossBar();
    showTitle("messages.stage1-title", "КАПЧА", "messages.stage1-subtitle", "Введи код с карты в чат");

    this.mapStartedAt = System.currentTimeMillis();
    startMapCaptchaRound();

    this.mapTimeoutFuture = player.getScheduledExecutor().schedule(
        () -> failAndKick(this.plugin.message("messages.captcha-timeout", "Время на капчу истекло.", Map.of())),
        this.plugin.mapTimeoutSeconds(),
        TimeUnit.SECONDS
    );

    player.flushPackets();
  }

  @Override
  public void onMove(double posX, double posY, double posZ) {
    handleMove(posX, posY, posZ);
  }

  @Override
  public void onMove(double posX, double posY, double posZ, float yaw, float pitch) {
    handleMove(posX, posY, posZ);
  }

  @Override
  public void onChat(String message) {
    if (this.finished || this.stage != Stage.MAP_CAPTCHA) {
      return;
    }

    String input = sanitizeChatInput(message);
    if (!input.isEmpty() && input.equals(this.mapAnswer)) {
      this.proxyPlayer.sendMessage(this.plugin.message("messages.captcha-ok", "Капча принята.", Map.of()));
      clearCaptchaMapVisuals();
      startFallStage();
      return;
    }

    this.wrongCaptchaAttempts++;
    if (this.wrongCaptchaAttempts >= 3) {
      failAndKick(this.plugin.message(
          "messages.captcha-fail-limit",
          "<red>Слишком много ошибок капчи (3/3). Переподключись.</red>",
          Map.of()
      ));
      return;
    }

    this.proxyPlayer.sendMessage(this.plugin.message("messages.captcha-wrong", "Неверно. Выдана новая капча.", Map.of()));
    startMapCaptchaRound();
  }

  @Override
  public void onGeneric(Object packet) {
    if (this.finished) {
      return;
    }

    if (packet instanceof BlockDigPacket blockDigPacket) {
      handleBlockDig(blockDigPacket);
      return;
    }
    if (packet instanceof UseItemOnBlockPacket useItemOnBlockPacket) {
      handleUseOnBlock(useItemOnBlockPacket);
    }
  }

  @Override
  public void onDisconnect() {
    this.plugin.unregisterSession(this.proxyPlayer);
    cancelFutures();
    hideBossBar();
  }

  private void handleMove(double x, double y, double z) {
    if (this.finished) {
      return;
    }

    if (this.stage == Stage.MAP_CAPTCHA) {
      enforceMapLock(x, y, z);
      return;
    }

    if (this.stage == Stage.FALL_CHECK) {
      handleFallMove(x, y, z);
      return;
    }

    if (this.stage == Stage.QUESTS && this.plugin.shouldReturnToIsland(x, y, z)) {
      long now = System.currentTimeMillis();
      if (now - this.lastReturnTeleportAt > 1000L && this.limboPlayer != null) {
        this.lastReturnTeleportAt = now;
        this.limboPlayer.teleport(this.plugin.islandSpawnX(), this.plugin.islandSpawnY(), this.plugin.islandSpawnZ(), 0.0F, 0.0F);
        this.proxyPlayer.sendMessage(this.plugin.message("messages.quest-fall-teleport", "Ты упал. Возврат.", Map.of()));
      }
    }
  }

  private void enforceMapLock(double x, double y, double z) {
    if (this.limboPlayer == null) {
      return;
    }

    double maxOffset = 0.20D;
    if (Math.abs(x - this.plugin.mapSpawnX()) > maxOffset
        || Math.abs(y - this.plugin.mapSpawnY()) > maxOffset
        || Math.abs(z - this.plugin.mapSpawnZ()) > maxOffset) {
      this.limboPlayer.teleport(this.plugin.mapSpawnX(), this.plugin.mapSpawnY(), this.plugin.mapSpawnZ(), 0.0F, 0.0F);
    }
  }

  private void handleFallMove(double x, double y, double z) {
    this.fallMovePackets++;
    this.fallMinY = Math.min(this.fallMinY, y);

    if (!this.hasLastMoveY) {
      this.lastMoveY = y;
      this.hasLastMoveY = true;
    } else {
      if (y > this.lastMoveY + 1.25D) {
        suspiciousKick();
        return;
      }
      this.lastMoveY = y;
    }

    if (Math.abs(x - this.fallStartX) > 6.0D || Math.abs(z - this.fallStartZ) > 6.0D) {
      suspiciousKick();
      return;
    }

    int elapsedMs = (int) (System.currentTimeMillis() - this.fallStartedAt);
    int totalMs = this.plugin.fallCheckSeconds() * 1000;
    int current = Math.min(totalMs, elapsedMs);
    float progress = Math.min(1.0F, totalMs <= 0 ? 1.0F : current / (float) totalMs);
    updateBossBarTimer(progress);
  }

  private void handleBlockDig(BlockDigPacket packet) {
    if (packet.status() != 2) {
      return;
    }

    if (this.stage != Stage.QUESTS) {
      suspiciousKick();
      return;
    }

    CaptchaTask currentTask = currentTask();
    if (currentTask == null) {
      return;
    }

    if (!currentTask.type().isBreakTask()) {
      wrongBlockKick();
      return;
    }

    BlockPos pos = packet.position();
    if (this.consumedBreakBlocks.contains(pos)) {
      return;
    }

    if (!this.plugin.matchesBreakTask(currentTask.type(), pos)) {
      wrongBlockKick();
      return;
    }

    this.consumedBreakBlocks.add(pos);
    this.taskProgress++;
    updateQuestProgress();
  }

  private void handleUseOnBlock(UseItemOnBlockPacket packet) {
    if (packet.hand() != 0) {
      return;
    }

    if (this.stage != Stage.QUESTS) {
      suspiciousKick();
      return;
    }

    CaptchaTask currentTask = currentTask();
    if (currentTask == null) {
      return;
    }

    if (!currentTask.type().isInteractTask()) {
      wrongOpenKick();
      return;
    }

    long now = System.currentTimeMillis();
    if (now - this.lastInteractAt < this.plugin.interactCooldownMs()) {
      return;
    }
    this.lastInteractAt = now;

    StationType stationType = this.plugin.stationAt(packet.position());
    if (stationType == null || !this.plugin.matchesStationTask(currentTask.type(), stationType)) {
      wrongOpenKick();
      return;
    }

    if (!this.waitingForClose) {
      this.waitingForClose = true;
      this.waitingForCloseStation = stationType;
      this.waitingForCloseTask = currentTask.type();
      this.waitingForCloseAt = now;
      this.proxyPlayer.sendActionBar(this.plugin.messageNoPrefix("messages.wait-close", "Закрой интерфейс и нажми снова.", Map.of()));
      return;
    }

    if (this.waitingForCloseTask != currentTask.type() || this.waitingForCloseStation != stationType) {
      wrongOpenKick();
      return;
    }

    if (now - this.waitingForCloseAt < this.plugin.closeReclickMs()) {
      return;
    }

    this.waitingForClose = false;
    this.waitingForCloseTask = null;
    this.waitingForCloseStation = null;

    this.taskProgress++;
    updateQuestProgress();
  }

  private void updateQuestProgress() {
    CaptchaTask task = currentTask();
    if (task == null) {
      return;
    }

    this.proxyPlayer.sendActionBar(this.plugin.messageNoPrefix(
        "messages.quest-next",
        "{task} ({current}/{required})",
        placeholders(
            "task", this.plugin.taskText(task.type()),
            "current", String.valueOf(this.taskProgress),
            "required", String.valueOf(task.requiredCount())
        )
    ));

    updateBossBarQuest(task, this.taskProgress);

    if (this.taskProgress < task.requiredCount()) {
      return;
    }

    this.proxyPlayer.sendMessage(this.plugin.message("messages.quest-step-done", "Этап выполнен.", Map.of()));
    finishCaptcha();
  }

  private void startMapCaptchaRound() {
    if (this.finished || this.stage != Stage.MAP_CAPTCHA) {
      return;
    }

    this.mapAnswer = generateCaptchaText(this.plugin.mapCodeLength());
    updateBossBarMap();

    if (!sendMapCaptchaImage(this.mapAnswer)) {
      this.proxyPlayer.sendMessage(this.plugin.message("messages.captcha-image-error", "Ошибка отправки капчи.", Map.of()));
    }

    this.proxyPlayer.sendMessage(this.plugin.message(
        "messages.captcha-start",
        "Введи код с карты. Время: {seconds}",
        placeholders("seconds", String.valueOf(this.plugin.mapTimeoutSeconds()))
    ));
  }

  private void clearCaptchaMapVisuals() {
    if (this.limboPlayer == null) {
      return;
    }

    BufferedImage blank = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = blank.createGraphics();
    g.setColor(new Color(8, 10, 16));
    g.fillRect(0, 0, 128, 128);
    g.dispose();

    this.limboPlayer.sendImage(0, blank, true, true);
    clearHotbar();
    this.limboPlayer.flushPackets();
  }

  private void clearHotbar() {
    if (this.limboPlayer == null) {
      return;
    }
    for (int slot = 36; slot <= 44; slot++) {
      this.limboPlayer.setInventory(this.plugin.getAirItem(), slot, 1);
    }
    this.limboPlayer.setInventory(this.plugin.getAirItem(), 45, 1);
  }

  private void startFallStage() {
    if (this.finished) {
      return;
    }

    cancelMapTimeout();

    this.stage = Stage.FALL_CHECK;
    this.fallStartedAt = System.currentTimeMillis();
    this.fallStartX = this.plugin.fallSpawnX();
    this.fallStartZ = this.plugin.fallSpawnZ();
    this.fallMinY = this.plugin.fallSpawnY();
    this.hasLastMoveY = false;
    this.fallMovePackets = 0;

    if (this.limboPlayer != null) {
      this.limboPlayer.enableFalling();
      this.limboPlayer.teleport(this.plugin.fallSpawnX(), this.plugin.fallSpawnY(), this.plugin.fallSpawnZ(), 0.0F, 0.0F);
      this.limboPlayer.flushPackets();
    }

    showTitle("messages.timer-title", "ПРОВЕРКА", "messages.timer-subtitle", "3...2...1...");
    this.proxyPlayer.sendMessage(this.plugin.message(
        "messages.timer-start",
        "⏳ {seconds}",
        placeholders("seconds", String.valueOf(this.plugin.fallCheckSeconds()))
    ));

    updateBossBarTimer(0.0F);

    if (this.limboPlayer != null) {
      this.fallCompleteFuture = this.limboPlayer.getScheduledExecutor().schedule(
          this::finishFallStage,
          this.plugin.fallCheckSeconds(),
          TimeUnit.SECONDS
      );
    }
  }

  private void finishFallStage() {
    if (this.finished || this.stage != Stage.FALL_CHECK) {
      return;
    }

    int fallenDistance = (int) Math.round(this.plugin.fallSpawnY() - this.fallMinY);
    if (fallenDistance < this.plugin.minFallDistance() || this.fallMovePackets < 3) {
      failAndKick(this.plugin.message("messages.fall-failed", "Проверка не пройдена.", Map.of()));
      return;
    }

    startQuestStage();
  }

  private void startQuestStage() {
    if (this.finished) {
      return;
    }

    this.stage = Stage.QUESTS;
    this.questPlan = this.plugin.buildQuestPlan(this.proxyPlayer.getUniqueId());
    this.taskIndex = 0;
    this.taskProgress = 0;

    this.lastInteractAt = 0L;
    this.waitingForClose = false;
    this.waitingForCloseTask = null;
    this.waitingForCloseStation = null;

    if (this.limboPlayer != null) {
      this.limboPlayer.enableFalling();
      this.limboPlayer.teleport(this.plugin.islandSpawnX(), this.plugin.islandSpawnY(), this.plugin.islandSpawnZ(), 0.0F, 0.0F);
      clearHotbar();
      this.limboPlayer.setInventory(this.plugin.getPickaxeItem(), 36, 1);
      this.limboPlayer.setInventory(this.plugin.getAxeItem(), 37, 1);
      this.limboPlayer.flushPackets();
    }

    this.proxyPlayer.sendMessage(this.plugin.message(
        "messages.quest-start",
        "Старт. Задание: {total}",
        placeholders("total", String.valueOf(this.questPlan.size()))
    ));

    announceCurrentTask();
  }

  private void announceCurrentTask() {
    CaptchaTask task = currentTask();
    if (task == null) {
      return;
    }

    this.proxyPlayer.sendMessage(this.plugin.message(
        "messages.quest-next",
        "{task} ({current}/{required})",
        placeholders(
            "task", this.plugin.taskText(task.type()),
            "current", String.valueOf(this.taskProgress),
            "required", String.valueOf(task.requiredCount())
        )
    ));

    updateBossBarQuest(task, this.taskProgress);
  }

  private CaptchaTask currentTask() {
    if (this.taskIndex < 0 || this.taskIndex >= this.questPlan.size()) {
      return null;
    }
    return this.questPlan.get(this.taskIndex);
  }

  private boolean sendMapCaptchaImage(String answer) {
    if (this.limboPlayer == null) {
      return false;
    }
    try {
      BufferedImage image = renderCaptchaImage(answer);
      this.limboPlayer.sendImage(0, image, true, true);
      this.limboPlayer.flushPackets();
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }

  private BufferedImage renderCaptchaImage(String answer) {
    BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

      GradientPaint bg = new GradientPaint(0, 0, new Color(16, 22, 34), 128, 128, new Color(34, 48, 66));
      g.setPaint(bg);
      g.fillRect(0, 0, 128, 128);

      ThreadLocalRandom random = ThreadLocalRandom.current();

      for (int i = 0; i < 260; i++) {
        g.setColor(new Color(
            random.nextInt(30, 190),
            random.nextInt(30, 190),
            random.nextInt(30, 190),
            random.nextInt(30, 150)
        ));
        g.fillRect(random.nextInt(128), random.nextInt(128), random.nextInt(1, 3), random.nextInt(1, 3));
      }

      g.setStroke(new BasicStroke(2.1f));
      for (int i = 0; i < 20; i++) {
        g.setColor(new Color(
            random.nextInt(80, 255),
            random.nextInt(80, 255),
            random.nextInt(80, 255),
            random.nextInt(50, 170)
        ));
        g.drawLine(random.nextInt(128), random.nextInt(128), random.nextInt(128), random.nextInt(128));
      }

      for (int i = 0; i < 9; i++) {
        g.setColor(new Color(
            random.nextInt(100, 255),
            random.nextInt(100, 255),
            random.nextInt(100, 255),
            random.nextInt(40, 120)
        ));
        int y = random.nextInt(16, 112);
        g.fillRect(0, y, 128, random.nextInt(1, 3));
      }

      g.setFont(new Font("Monospaced", Font.BOLD, 32));
      int textWidth = g.getFontMetrics().stringWidth(answer);
      int x = (128 - textWidth) / 2;
      int y = 74;

      for (int i = 0; i < answer.length(); i++) {
        char ch = answer.charAt(i);
        AffineTransform original = g.getTransform();
        double angle = Math.toRadians(random.nextInt(-18, 19));
        int charX = x + random.nextInt(-2, 3);
        int charY = y + random.nextInt(-4, 5);
        g.rotate(angle, charX, charY);
        g.setColor(new Color(235, 242, 255));
        g.drawString(String.valueOf(ch), charX, charY);
        g.setTransform(original);
        x += g.getFontMetrics().charWidth(ch);
      }

      // Overlay stripes and lines above symbols to harden OCR.
      g.setStroke(new BasicStroke(2.5f));
      for (int i = 0; i < 12; i++) {
        g.setColor(new Color(
            random.nextInt(140, 255),
            random.nextInt(120, 240),
            random.nextInt(120, 240),
            random.nextInt(90, 180)
        ));
        int x1 = random.nextInt(128);
        int y1 = random.nextInt(28, 106);
        int x2 = random.nextInt(128);
        int y2 = random.nextInt(28, 106);
        g.drawLine(x1, y1, x2, y2);
      }
      for (int i = 0; i < 10; i++) {
        g.setColor(new Color(
            random.nextInt(120, 255),
            random.nextInt(120, 255),
            random.nextInt(120, 255),
            random.nextInt(70, 150)
        ));
        int stripeY = random.nextInt(20, 112);
        g.fillRect(0, stripeY, 128, random.nextInt(1, 4));
      }

      g.setColor(new Color(255, 200, 80));
      g.setFont(new Font("SansSerif", Font.BOLD, 10));
      g.drawString("PROSTO CAPTCHA", 8, 12);
    } finally {
      g.dispose();
    }
    return image;
  }

  private String generateCaptchaText(int length) {
    String alphabet = this.plugin.mapAlphabet();
    if (alphabet == null || alphabet.isBlank()) {
      alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    }

    int safeLength = Math.max(4, length);
    StringBuilder builder = new StringBuilder(safeLength);
    ThreadLocalRandom random = ThreadLocalRandom.current();
    for (int i = 0; i < safeLength; i++) {
      char ch = alphabet.charAt(random.nextInt(alphabet.length()));
      if (Character.isLetter(ch)) {
        ch = random.nextBoolean() ? Character.toUpperCase(ch) : Character.toLowerCase(ch);
      }
      builder.append(ch);
    }
    return builder.toString();
  }

  private String sanitizeChatInput(String message) {
    if (message == null) {
      return "";
    }
    String input = message.trim();
    if (input.startsWith("/")) {
      input = input.substring(1).trim();
    }
    if (input.contains(" ")) {
      input = input.substring(input.lastIndexOf(' ') + 1).trim();
    }
    return input;
  }

  private void showBossBar() {
    this.bossBar = BossBar.bossBar(
        this.plugin.messageNoPrefix("messages.boss-ready", "Подготовка проверки...", Map.of()),
        0.0F,
        BossBar.Color.YELLOW,
        BossBar.Overlay.PROGRESS
    );
    this.proxyPlayer.showBossBar(this.bossBar);
  }

  private void updateBossBarMap() {
    if (this.bossBar == null) {
      return;
    }
    long elapsed = System.currentTimeMillis() - this.mapStartedAt;
    long total = this.plugin.mapTimeoutSeconds() * 1000L;
    float progress = total <= 0L ? 0.0F : Math.max(0.0F, 1.0F - (float) elapsed / (float) total);
    this.bossBar.name(this.plugin.messageNoPrefix("messages.boss-map", "Капча", Map.of()));
    this.bossBar.progress(progress);
  }

  private void updateBossBarTimer(float progress) {
    if (this.bossBar == null) {
      return;
    }
    this.bossBar.name(this.plugin.messageNoPrefix(
        "messages.boss-fall",
        "Проверка...",
        Map.of()
    ));
    this.bossBar.progress(Math.max(0.0F, Math.min(1.0F, progress)));
  }

  private void updateBossBarQuest(CaptchaTask task, int progress) {
    if (this.bossBar == null) {
      return;
    }

    int required = Math.max(1, task.requiredCount());
    float taskProgressPercent = Math.min(1.0F, progress / (float) required);

    this.bossBar.name(this.plugin.messageNoPrefix(
        "messages.boss-quest",
        "{task} ({current}/{required})",
        placeholders(
            "task", this.plugin.taskText(task.type()),
            "current", String.valueOf(progress),
            "required", String.valueOf(required)
        )
    ));
    this.bossBar.progress(taskProgressPercent);
  }

  private void hideBossBar() {
    if (this.bossBar == null) {
      return;
    }
    this.proxyPlayer.hideBossBar(this.bossBar);
    this.bossBar = null;
  }

  private void showTitle(String titleKey, String titleFallback, String subtitleKey, String subtitleFallback) {
    Title title = Title.title(
        this.plugin.messageNoPrefix(titleKey, titleFallback, Map.of()),
        this.plugin.messageNoPrefix(subtitleKey, subtitleFallback, Map.of()),
        Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(1800), Duration.ofMillis(300))
    );
    this.proxyPlayer.showTitle(title);
  }

  private void finishCaptcha() {
    if (this.finished) {
      return;
    }
    this.finished = true;
    cancelFutures();
    this.plugin.unregisterSession(this.proxyPlayer);
    hideBossBar();

    this.proxyPlayer.sendMessage(this.plugin.message("messages.quest-all-done", "Проверка пройдена.", Map.of()));

    if (this.limboPlayer != null) {
      this.limboPlayer.disconnect();
    }
  }

  private void wrongBlockKick() {
    if (this.plugin.kickOnWrongAction()) {
      failAndKick(this.plugin.message("messages.wrong-block", "Нельзя ломать этот блок.", Map.of()));
    }
  }

  private void wrongOpenKick() {
    if (this.plugin.kickOnWrongAction()) {
      failAndKick(this.plugin.message("messages.wrong-open", "Нельзя открывать этот объект.", Map.of()));
    }
  }

  private void suspiciousKick() {
    failAndKick(this.plugin.message("messages.suspicious", "Обнаружено подозрительное действие.", Map.of()));
  }

  private void failAndKick(Component message) {
    if (this.finished) {
      return;
    }
    this.finished = true;
    cancelFutures();
    this.plugin.unregisterSession(this.proxyPlayer);
    hideBossBar();

    this.proxyPlayer.disconnect(message);
  }

  private void cancelMapTimeout() {
    if (this.mapTimeoutFuture != null) {
      this.mapTimeoutFuture.cancel(true);
      this.mapTimeoutFuture = null;
    }
  }

  private void cancelFutures() {
    cancelMapTimeout();
    if (this.fallCompleteFuture != null) {
      this.fallCompleteFuture.cancel(true);
      this.fallCompleteFuture = null;
    }
  }

  private Map<String, String> placeholders(String... pairs) {
    if (pairs == null || pairs.length == 0) {
      return Map.of();
    }
    if (pairs.length % 2 != 0) {
      throw new IllegalArgumentException("pairs must be key/value list");
    }
    java.util.HashMap<String, String> map = new java.util.HashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put(pairs[i], pairs[i + 1]);
    }
    return map;
  }

  private enum Stage {
    MAP_CAPTCHA,
    FALL_CHECK,
    QUESTS
  }
}
