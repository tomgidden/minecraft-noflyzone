package cx.gid.minecraft.noflyzone;

import java.util.Locale;

/**
 * How a no-fly zone treats a player who flies into it.
 *
 * <p>The three modes sit on a deliberate spectrum of severity, and differ in one
 * structural way worth noting: {@link #DAMAGE} is the only mode where the player
 * <em>keeps gliding</em>. The other two stop the glide, by different means.
 */
public enum NoFlyMode {
  /**
   * Merciful. Horizontal momentum is clamped to nothing and new takeoffs are
   * refused, but an existing glide continues -- so a player sinks gently and
   * lands safely rather than dropping out of the sky.
   */
  ZERO_MOMENTUM("zero-momentum"),

  /**
   * Brutal. Gliding is refused outright, so wings cut mid-flight exactly as
   * they do when an elytra breaks. Surprising, and often fatal from altitude.
   */
  NO_GLIDE("no-glide"),

  /**
   * Scary but survivable. The player keeps flying and keeps control, while
   * taking steady damage as though being shot down. Armour and starting health
   * genuinely decide whether a crossing is survivable.
   */
  DAMAGE("damage");

  /**
   * The value written in, and parsed from, the config file.
   */
  private final String configName;

  NoFlyMode(String configName)
  {
    this.configName = configName;
  }

  /**
   * The value as it appears in {@code noflyzone.properties}.
   */
  public String configName()
  {
    return configName;
  }

  /**
   * Translation key for the human-readable description of this mode.
   */
  public String descriptionKey()
  {
    return "noflyzone.mode." + configName;
  }

  /**
   * Parses a config or command value, case- and separator-insensitively, so
   * {@code zero_momentum} and {@code ZERO-MOMENTUM} both work.
   *
   * @return the matching mode, or {@code null} if nothing matches
   */
  public static NoFlyMode parse(String raw)
  {
    if (raw == null) {
      return null;
    }
    String normalised = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    for (NoFlyMode mode : values()) {
      if (mode.configName.equals(normalised)) {
        return mode;
      }
    }
    return null;
  }

  /**
   * The accepted config values, for error messages and command completion.
   */
  public static String names()
  {
    StringBuilder sb = new StringBuilder();
    for (NoFlyMode mode : values()) {
      if (!sb.isEmpty()) {
        sb.append(", ");
      }
      sb.append(mode.configName);
    }
    return sb.toString();
  }
}
