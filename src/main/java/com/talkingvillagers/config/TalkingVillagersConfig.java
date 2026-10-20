package com.talkingvillagers.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.talkingvillagers.TalkingVillagers;

/**
 * Typed view over {@code config/talkingvillagers.toml}.
 *
 * <p>The commented default file in {@link DefaultConfig#TEXT} is the single source of both
 * the shipped documentation and the fallback values: it is parsed first, then the user's
 * file is overlaid on top. A key the user has deleted (or a key added by a newer version of
 * the mod) therefore still resolves, and the docs can never drift from the defaults.
 *
 * <p>Every field is read once at load. Reloading produces a new instance rather than
 * mutating this one, so a tick that is midway through reading config can't observe a torn
 * half-updated view.
 */
public final class TalkingVillagersConfig {
	public final Ollama ollama;
	public final Names names;
	public final Sexuality sexuality;
	public final Conversation conversation;
	public final Needs needs;

	private TalkingVillagersConfig(Lookup lookup) {
		this.ollama = new Ollama(lookup);
		this.names = new Names(lookup);
		this.sexuality = new Sexuality(lookup);
		this.conversation = new Conversation(lookup);
		this.needs = new Needs(lookup);
	}

	/**
	 * Loads the config, writing the commented default file first if it does not exist.
	 * Never throws: a malformed or unreadable file logs an error and falls back to defaults,
	 * because failing to start the server over a typo in a name list would be worse than
	 * running with the shipped values.
	 */
	public static TalkingVillagersConfig load(Path configDir) {
		Path file = configDir.resolve("talkingvillagers.toml");
		Map<String, Object> defaults = Toml.parse(DefaultConfig.TEXT);

		if (!Files.exists(file)) {
			try {
				Files.createDirectories(configDir);
				Files.writeString(file, DefaultConfig.TEXT, StandardCharsets.UTF_8);
				TalkingVillagers.LOGGER.info("Wrote default config to {}", file);
			} catch (IOException e) {
				TalkingVillagers.LOGGER.error("Could not write default config to {}, using built-in defaults", file, e);
			}
			return new TalkingVillagersConfig(new Lookup(defaults, defaults));
		}

		Map<String, Object> user;
		try {
			user = Toml.parse(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException | Toml.TomlException e) {
			TalkingVillagers.LOGGER.error("Could not read {}, using built-in defaults instead", file, e);
			return new TalkingVillagersConfig(new Lookup(defaults, defaults));
		}

		Lookup lookup = new Lookup(user, defaults);
		TalkingVillagersConfig loaded = new TalkingVillagersConfig(lookup);
		lookup.reportMissingKeys(file);
		return loaded;
	}

	/** Config as it would be with no file present. Used by tests and as a last resort. */
	public static TalkingVillagersConfig defaults() {
		Map<String, Object> defaults = Toml.parse(DefaultConfig.TEXT);
		return new TalkingVillagersConfig(new Lookup(defaults, defaults));
	}

	public static final class Ollama {
		public final String baseUrl;
		public final String model;
		/** Whether thinking models may reason before answering. No effect on models that cannot. */
		public final boolean thinking;
		public final int timeoutMs;
		public final int maxTokens;
		public final double temperature;
		public final int queueCapacity;
		public final int healthCheckIntervalTicks;
		/**
		 * How many other people one villager remembers. Lives here rather than with the
		 * settlement settings because its real job is bounding prompt size.
		 */
		public final int relationshipCap;

		private Ollama(Lookup l) {
			this.baseUrl = stripTrailingSlash(l.string("ollama.base_url"));
			this.model = l.string("ollama.model");
			this.thinking = l.bool("ollama.thinking");
			this.timeoutMs = l.intAtLeast("ollama.timeout_ms", 100);
			this.maxTokens = l.intAtLeast("ollama.max_tokens", 1);
			this.temperature = l.doubleValue("ollama.temperature");
			this.queueCapacity = l.intAtLeast("ollama.queue_capacity", 1);
			this.healthCheckIntervalTicks = l.intAtLeast("ollama.health_check_interval_ticks", 20);
			this.relationshipCap = l.intAtLeast("ollama.relationship_cap", 4);
		}

		private static String stripTrailingSlash(String url) {
			return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
		}
	}

	public static final class Names {
		public final List<String> firstNamesMale;
		public final List<String> firstNamesFemale;
		public final List<String> surnames;
		public final List<String> settlementPrefixes;
		public final List<String> settlementSuffixes;

		private Names(Lookup l) {
			this.firstNamesMale = l.stringListNonEmpty("names.first_names_male");
			this.firstNamesFemale = l.stringListNonEmpty("names.first_names_female");
			this.surnames = l.stringListNonEmpty("names.surnames");
			this.settlementPrefixes = l.stringListNonEmpty("names.settlement_name_prefixes");
			this.settlementSuffixes = l.stringListNonEmpty("names.settlement_name_suffixes");
		}
	}

	public static final class Sexuality {
		/**
		 * Whether orientation is modelled at all. With this off every adult is treated as
		 * heterosexual, so courtship only pairs men with women.
		 */
		public final boolean enabled;

		private Sexuality(Lookup l) {
			this.enabled = l.bool("sexuality.enable_sexuality");
		}
	}

	public static final class Conversation {
		/** Where a villager's speech appears. */
		public enum Display {
			OVERHEAD, CHAT, BOTH;

			/** Whether speech should float above the villager's head. */
			public boolean overhead() {
				return this != CHAT;
			}

			/** Whether speech should also be written to the player's chat box. */
			public boolean chat() {
				return this != OVERHEAD;
			}
		}

		public final Display display;
		/** How long a line of floating speech stays up, in ticks. */
		public final int overheadDurationTicks;
		/**
		 * How far a player may get from the villager they are talking to before the conversation
		 * ends, in blocks.
		 */
		public final double maxDistance;
		public final boolean particlesWhileTalking;

		private Conversation(Lookup l) {
			String raw = l.string("conversation.display");
			this.display = switch (raw.toLowerCase()) {
				case "chat" -> Display.CHAT;
				case "overhead" -> Display.OVERHEAD;
				case "both" -> Display.BOTH;
				default -> {
					TalkingVillagers.LOGGER.warn(
						"conversation.display was '{}'; expected \"overhead\", \"chat\" or \"both\". Using \"both\".",
						raw);
					yield Display.BOTH;
				}
			};
			// A single tick of floating text would be invisible, so the floor is a fifth of a
			// second rather than zero; switch display to "chat" to turn bubbles off properly.
			this.overheadDurationTicks = l.intAtLeast("conversation.overhead_duration_ticks", 4);
			// Below about a block the player's own standing-room would end the conversation.
			this.maxDistance = l.doubleAtLeast("conversation.max_distance", 1.0);
			this.particlesWhileTalking = l.bool("conversation.particles_while_talking");
		}
	}

	public static final class Needs {
		/** Whether settlements track whether they can feed themselves. */
		public final boolean foodEnabled;

		private Needs(Lookup l) {
			this.foodEnabled = l.bool("needs.food_enabled");
		}
	}

	/**
	 * Reads typed values from the user map, falling back to the defaults map and finally to
	 * a hard-coded sentinel. Every fallback is logged once so a mistyped key surfaces in the
	 * log instead of silently changing behaviour.
	 */
	private static final class Lookup {
		private final Map<String, Object> user;
		private final Map<String, Object> defaults;
		private final List<String> missing = new ArrayList<>();

		Lookup(Map<String, Object> user, Map<String, Object> defaults) {
			this.user = user;
			this.defaults = defaults;
		}

		private Object raw(String key) {
			Object value = user.get(key);
			if (value != null) {
				return value;
			}
			Object fallback = defaults.get(key);
			if (fallback == null) {
				// Only reachable if DefaultConfig.TEXT is missing a key some field reads,
				// which is a bug in this class rather than in the user's file.
				throw new IllegalStateException("No default defined for config key '" + key + "'");
			}
			if (!user.isEmpty()) {
				this.missing.add(key);
			}
			return fallback;
		}

		/**
		 * Names any key the mod reads that the user's file does not contain.
		 *
		 * <p>Falling back to a default is harmless, but silent: a config written by an older
		 * version of the mod goes on working while quietly not offering its newest settings, and
		 * the owner has no way to tell a key they have never heard of from one they mistyped.
		 * Saying so once, with the file path, is enough for either.
		 */
		void reportMissingKeys(Path file) {
			if (this.missing.isEmpty()) {
				return;
			}
			TalkingVillagers.LOGGER.warn(
				"{} does not set {} (using defaults): {}. Delete the file to have the current one "
					+ "written out with every setting and its documentation.",
				file.getFileName(), this.missing.size(), String.join(", ", this.missing));
		}

		String string(String key) {
			Object value = raw(key);
			return value instanceof String s ? s : warnType(key, value, String.valueOf(value));
		}

		boolean bool(String key) {
			Object value = raw(key);
			return value instanceof Boolean b ? b : warnType(key, value, defaultAs(key, Boolean.class, false));
		}

		int intAtLeast(String key, int min) {
			int value = intValue(key);
			if (value < min) {
				TalkingVillagers.LOGGER.warn("Config key '{}' was {}, clamping to minimum {}", key, value, min);
				return min;
			}
			return value;
		}

		int intInRange(String key, int min, int max) {
			int value = intValue(key);
			int clamped = Math.max(min, Math.min(max, value));
			if (clamped != value) {
				TalkingVillagers.LOGGER.warn("Config key '{}' was {}, clamping into [{}, {}]", key, value, min, max);
			}
			return clamped;
		}

		private int intValue(String key) {
			Object value = raw(key);
			if (value instanceof Long l) {
				return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, l));
			}
			if (value instanceof Double d) {
				return (int) Math.round(d);
			}
			return warnType(key, value, defaultAs(key, Long.class, 0L).intValue());
		}

		double doubleValue(String key) {
			Object value = raw(key);
			if (value instanceof Double d) {
				return d;
			}
			if (value instanceof Long l) {
				return l;
			}
			return warnType(key, value, defaultAs(key, Double.class, 0.0));
		}

		double doubleAtLeast(String key, double min) {
			double value = doubleValue(key);
			if (value < min) {
				TalkingVillagers.LOGGER.warn("Config key '{}' was {}, clamping to minimum {}", key, value, min);
				return min;
			}
			return value;
		}

		List<String> stringListNonEmpty(String key) {
			List<String> values = new ArrayList<>();
			for (Object element : rawList(key)) {
				if (element instanceof String s && !s.isBlank()) {
					values.add(s);
				}
			}
			if (values.isEmpty()) {
				TalkingVillagers.LOGGER.warn("Config key '{}' had no usable entries, using defaults", key);
				for (Object element : listFrom(defaults.get(key))) {
					if (element instanceof String s && !s.isBlank()) {
						values.add(s);
					}
				}
			}
			return List.copyOf(values);
		}

		List<Integer> intListNonEmpty(String key) {
			List<Integer> values = new ArrayList<>();
			for (Object element : rawList(key)) {
				if (element instanceof Long l) {
					values.add(l.intValue());
				} else if (element instanceof Double d) {
					values.add((int) Math.round(d));
				}
			}
			if (values.isEmpty()) {
				TalkingVillagers.LOGGER.warn("Config key '{}' had no usable entries, using defaults", key);
				for (Object element : listFrom(defaults.get(key))) {
					if (element instanceof Long l) {
						values.add(l.intValue());
					}
				}
			}
			return List.copyOf(values);
		}

		private List<?> rawList(String key) {
			return listFrom(raw(key));
		}

		private static List<?> listFrom(Object value) {
			return value instanceof List<?> list ? list : List.of();
		}

		private <T> T defaultAs(String key, Class<T> type, T fallback) {
			Object value = defaults.get(key);
			return type.isInstance(value) ? type.cast(value) : fallback;
		}

		private <T> T warnType(String key, Object actual, T fallback) {
			TalkingVillagers.LOGGER.warn("Config key '{}' has unexpected type ({}), using {}", key, actual, fallback);
			return fallback;
		}
	}
}
