package me.prostoac.captcha;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

public final class CaptchaConfig {

  private static final String RESOURCE_CONFIG = "config.yml";

  private final Map<String, String> values;

  private CaptchaConfig(Map<String, String> values) {
    this.values = values;
  }

  public static CaptchaConfig load(Path dataDirectory, Logger logger) {
    try {
      Files.createDirectories(dataDirectory);
      Path configPath = dataDirectory.resolve(RESOURCE_CONFIG);
      if (Files.notExists(configPath)) {
        copyDefaultConfig(configPath, logger);
      }

      Map<String, String> loaded = parseYamlLike(configPath);
      Map<String, String> defaults = parseYamlLikeText(defaultConfigText());
      defaults.putAll(loaded);
      return new CaptchaConfig(defaults);
    } catch (Exception exception) {
      logger.error("[ProstoCaptcha] Failed to load config.yml, using in-memory defaults.", exception);
      return new CaptchaConfig(parseYamlLikeText(defaultConfigText()));
    }
  }

  public int mapTimeoutSeconds() {
    return intValue("timings.captcha-seconds", 25, 5, 180);
  }

  public int fallCheckSeconds() {
    return intValue("timings.fall-check-seconds", 3, 1, 10);
  }

  public long interactCooldownMs() {
    return longValue("timings.interact-cooldown-ms", 300L, 0L, 5000L);
  }

  public long closeReclickMs() {
    return longValue("timings.close-reclick-ms", 400L, 50L, 5000L);
  }

  public int mapCodeLength() {
    return intValue("captcha.code-length", 6, 4, 12);
  }

  public String mapAlphabet() {
    return text("captcha.alphabet", "ABCDEFGHJKLMNPQRSTUVWXYZ23456789");
  }

  public int minFallDistance() {
    return intValue("security.min-fall-distance", 8, 1, 80);
  }

  public boolean kickOnWrongAction() {
    return boolValue("security.kick-on-wrong-action", true);
  }

  public int mapY() {
    return intValue("world.map-y", 250, 100, 320);
  }

  public int fallY() {
    return intValue("world.fall-y", 215, 90, 320);
  }

  public int islandMinY() {
    return intValue("world.island-min-y", 58, -64, 320);
  }

  public String text(String key, String fallback) {
    String raw = this.values.get(key);
    if (raw == null) {
      return fallback;
    }
    String trimmed = raw.trim();
    return trimmed.isEmpty() ? fallback : trimmed;
  }

  public int intValue(String key, int fallback, int min, int max) {
    String raw = this.values.get(key);
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      int parsed = Integer.parseInt(raw.trim());
      return Math.max(min, Math.min(max, parsed));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  public long longValue(String key, long fallback, long min, long max) {
    String raw = this.values.get(key);
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      long parsed = Long.parseLong(raw.trim());
      return Math.max(min, Math.min(max, parsed));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  public boolean boolValue(String key, boolean fallback) {
    String raw = this.values.get(key);
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    if (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("yes") || raw.equalsIgnoreCase("on")) {
      return true;
    }
    if (raw.equalsIgnoreCase("false") || raw.equalsIgnoreCase("no") || raw.equalsIgnoreCase("off")) {
      return false;
    }
    return fallback;
  }

  private static void copyDefaultConfig(Path configPath, Logger logger) throws IOException {
    try (InputStream input = CaptchaConfig.class.getClassLoader().getResourceAsStream(RESOURCE_CONFIG)) {
      if (input == null) {
        Files.writeString(configPath, defaultConfigText(), StandardCharsets.UTF_8);
        logger.warn("[ProstoCaptcha] config.yml resource not found, wrote built-in defaults.");
        return;
      }
      Files.copy(input, configPath, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static Map<String, String> parseYamlLike(Path path) throws IOException {
    return parseLines(Files.readAllLines(path, StandardCharsets.UTF_8));
  }

  private static Map<String, String> parseYamlLikeText(String text) {
    return parseLines(text.lines().toList());
  }

  private static Map<String, String> parseLines(List<String> lines) {
    Map<String, String> out = new HashMap<>();
    Deque<String> sections = new ArrayDeque<>();

    for (String line : lines) {
      String noComment = stripComment(line);
      if (noComment == null || noComment.trim().isEmpty()) {
        continue;
      }

      int indent = countLeadingSpaces(noComment);
      int level = Math.max(0, indent / 2);
      while (sections.size() > level) {
        sections.removeLast();
      }

      String trimmed = noComment.trim();
      if (trimmed.endsWith(":")) {
        String section = trimmed.substring(0, trimmed.length() - 1).trim();
        if (!section.isEmpty()) {
          sections.addLast(section);
        }
        continue;
      }

      int separator = trimmed.indexOf(':');
      if (separator <= 0) {
        continue;
      }

      String key = trimmed.substring(0, separator).trim();
      String value = trimmed.substring(separator + 1).trim();
      value = stripWrappingQuotes(value);
      value = unescape(value);

      String pathKey = sections.isEmpty() ? key : String.join(".", sections) + "." + key;
      out.put(pathKey, value);
    }
    return out;
  }

  private static String stripComment(String line) {
    boolean inSingle = false;
    boolean inDouble = false;
    boolean escaping = false;

    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (escaping) {
        escaping = false;
        continue;
      }
      if (c == '\\') {
        escaping = true;
        continue;
      }
      if (c == '\'' && !inDouble) {
        inSingle = !inSingle;
        continue;
      }
      if (c == '"' && !inSingle) {
        inDouble = !inDouble;
        continue;
      }
      if (c == '#' && !inSingle && !inDouble) {
        return line.substring(0, i);
      }
    }
    return line;
  }

  private static int countLeadingSpaces(String line) {
    int count = 0;
    while (count < line.length() && line.charAt(count) == ' ') {
      count++;
    }
    return count;
  }

  private static String stripWrappingQuotes(String value) {
    if (value.length() >= 2) {
      char first = value.charAt(0);
      char last = value.charAt(value.length() - 1);
      if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        return value.substring(1, value.length() - 1);
      }
    }
    return value;
  }

  private static String unescape(String value) {
    return value
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t");
  }

  public static String defaultConfigText() {
    return """
        # ProstoCaptcha Core
        # UTF-8 file

        timings:
          captcha-seconds: 25
          fall-check-seconds: 3
          interact-cooldown-ms: 300
          close-reclick-ms: 450

        captcha:
          code-length: 6
          alphabet: "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        security:
          min-fall-distance: 8
          kick-on-wrong-action: true

        world:
          map-y: 250
          fall-y: 215
          island-min-y: 58

        messages:
          prefix: "<gradient:#F9C449:#FF7E4A><bold>Защита</bold></gradient> <gray>»</gray> "
          stage1-title: "<gradient:#67E8F9:#22D3EE><bold>КАПЧА</bold></gradient>"
          stage1-subtitle: "<white>Введи код с карты в чат</white>"
          stage2-title: "<gradient:#FCD34D:#F59E0B><bold>ПРОВЕРКА ПАДЕНИЯ</bold></gradient>"
          stage2-subtitle: "<white>Не трогай ничего 3 секунды</white>"
          stage3-title: "<gradient:#86EFAC:#22C55E><bold>ПРОВЕРКА ДЕЙСТВИЙ</bold></gradient>"
          stage3-subtitle: "<white>Выполни задания на острове</white>"
          captcha-start: "<gray>🧩</gray> <white>Введи код с карты. Время: <yellow>{seconds}</yellow> сек.</white>"
          captcha-wrong: "<red>Неверно.</red> <white>Выдана новая капча.</white>"
          captcha-ok: "<green>Капча принята.</green> <white>Переход к следующему этапу.</white>"
          captcha-timeout: "<red>⏰ Время на капчу истекло. Зайди снова.</red>"
          captcha-image-error: "<yellow>Не удалось отправить карту, выдана новая.</yellow>"
          fall-start: "<gray>🪂</gray> <white>Проверка падения: <yellow>{seconds}</yellow> сек.</white>"
          fall-actionbar: "<gradient:#FDE68A:#F59E0B>Падение: {current}/{required}</gradient>"
          fall-failed: "<red>Проверка падения не пройдена.</red>"
          fall-ok: "<green>Падение подтверждено.</green>"
          quest-start: "<gray>⚒</gray> <white>Старт заданий. Всего: <yellow>{total}</yellow></white>"
          quest-next: "<gray>📌</gray> <white>{task}</white> <gray>({current}/{required})</gray>"
          quest-step-done: "<green>✔ Этап выполнен.</green>"
          quest-all-done: "<gradient:#86EFAC:#22C55E><bold>Проверка пройдена. Добро пожаловать!</bold></gradient>"
          quest-fall-teleport: "<yellow>Ты упал с острова. Возврат на точку проверки.</yellow>"
          wait-close: "<gray>Закрой интерфейс (ESC) и нажми снова по блоку.</gray>"
          wrong-block: "<red>Нельзя ломать этот блок на текущем этапе.</red>"
          wrong-open: "<red>Нельзя открывать этот объект на текущем этапе.</red>"
          suspicious: "<red>Обнаружено подозрительное действие. Переподключись.</red>"
          need-log-first: "<yellow>Сначала добудь дерево.</yellow>"
          need-iron-first: "<yellow>Сначала добудь железо.</yellow>"
          need-craft-first: "<yellow>Сначала сделай доски и переплавь железо.</yellow>"
          boss-map: "<gradient:#67E8F9:#22D3EE>Капча: введи код с карты</gradient>"
          boss-fall: "<gradient:#FDE68A:#F59E0B>Проверка падения</gradient>"
          boss-quest: "<gradient:#86EFAC:#22C55E>{task} ({current}/{required})</gradient>"
          boss-ready: "<gradient:#F9C449:#FF7E4A>Подготовка проверки...</gradient>"

        tasks:
          break-log: "Сломай дерево"
          break-stone: "Добудь камень"
          break-iron: "Добудь железную руду"
          break-coal: "Добудь уголь"
          break-diamond: "Добудь алмазную руду"
          craft-planks: "Сделай доски (верстак: открыть/закрыть)"
          smelt-iron: "Переплавь железо (печка: открыть/закрыть)"
          craft-item: "Скрафти предмет (верстак: открыть/закрыть)"
          open-close-chest: "Открой и закрой сундук"
          open-close-crafting: "Открой и закрой верстак"
          open-close-furnace: "Открой и закрой печку"
          open-close-anvil: "Открой и закрой наковальню"

        words:
          one: "раз"
          few: "раза"
          many: "раз"
        """;
  }
}
