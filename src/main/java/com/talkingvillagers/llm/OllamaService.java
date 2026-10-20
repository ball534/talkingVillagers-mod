package com.talkingvillagers.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import com.talkingvillagers.TalkingVillagers;
import com.talkingvillagers.Tuning;
import com.talkingvillagers.config.TalkingVillagersConfig;

/**
 * Talks to Ollama over plain HTTP, entirely off the server thread.
 *
 * <p>Two rules shape this class. Nothing may block the server thread, so requests are fired
 * asynchronously and their results are parked in a queue that {@link #tick} drains on the
 * server thread. And the model is assumed to be small, local and easily swamped, so requests
 * are rate-limited by {@link Tuning.Ollama#MAX_CONCURRENT}, queued by priority, and dropped rather than
 * allowed to pile up.
 *
 * <p>When Ollama is unreachable the mod goes quiet rather than substituting canned lines:
 * queued work is discarded, new submissions fail immediately, and a periodic probe re-opens
 * the gate once the endpoint answers again.
 */
public final class OllamaService implements AutoCloseable {
	/** Consecutive failures before the endpoint is considered down. */
	private static final int FAILURE_THRESHOLD = 3;

	private final HttpClient http;
	private final Map<LlmPriority, ArrayDeque<LlmRequest>> queues = new EnumMap<>(LlmPriority.class);
	/** Callbacks handed back from HTTP threads, drained on the server thread. */
	private final Queue<Runnable> pendingCallbacks = new ConcurrentLinkedQueue<>();
	private final AtomicInteger inFlight = new AtomicInteger();
	private final AtomicInteger consecutiveFailures = new AtomicInteger();
	/**
	 * Bumped by {@link #reset()}. A request that was already in flight when a world stopped
	 * completes against the old generation and is dropped, so its callback cannot fire into the
	 * next world — where the villager it was speaking for no longer exists.
	 */
	private final AtomicInteger generation = new AtomicInteger();

	private volatile boolean healthy = true;
	private volatile boolean probeInFlight;
	private int ticksUntilProbe;

	public OllamaService() {
		this.http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			// A small daemon pool: HTTP work is IO-bound and must never keep the JVM alive
			// after the server stops.
			.executor(Executors.newCachedThreadPool(runnable -> {
				Thread thread = new Thread(runnable, "talkingvillagers-ollama");
				thread.setDaemon(true);
				return thread;
			}))
			.build();

		for (LlmPriority priority : LlmPriority.values()) {
			this.queues.put(priority, new ArrayDeque<>());
		}
	}

	/**
	 * Queues a request. Must be called on the server thread.
	 *
	 * <p>If the endpoint is known to be down, or the queue is full of higher-priority work,
	 * the request's failure callback runs immediately — the caller learns straight away that
	 * this villager will not be speaking.
	 */
	public void submit(LlmRequest request) {
		if (!this.healthy) {
			request.onFailure().run();
			return;
		}

		TalkingVillagersConfig config = TalkingVillagers.config();
		this.queues.get(request.priority()).addLast(request);
		enforceCapacity(config.ollama.queueCapacity);
	}

	/**
	 * Drops the lowest-priority queued work until the queue fits in {@code capacity}.
	 *
	 * <p>Dropping the newest of the lowest priority keeps a long-queued request from being
	 * starved forever by a stream of fresher ambient chatter.
	 */
	private void enforceCapacity(int capacity) {
		int queued = queuedCount();
		if (queued <= capacity) {
			return;
		}

		LlmPriority[] priorities = LlmPriority.values();
		for (int index = priorities.length - 1; index >= 0 && queued > capacity; index--) {
			ArrayDeque<LlmRequest> queue = this.queues.get(priorities[index]);
			while (!queue.isEmpty() && queued > capacity) {
				LlmRequest discarded = queue.removeLast();
				queued--;
				discarded.onFailure().run();
			}
		}
	}

	/** Runs once per server tick, on the server thread. */
	public void tick() {
		// Deliver finished work first so callbacks see the current game state.
		Runnable callback;
		while ((callback = this.pendingCallbacks.poll()) != null) {
			try {
				callback.run();
			} catch (RuntimeException e) {
				TalkingVillagers.LOGGER.error("Error applying a villager's reply", e);
			}
		}

		TalkingVillagersConfig config = TalkingVillagers.config();

		if (!this.healthy) {
			tickHealthProbe(config);
			return;
		}

		while (this.inFlight.get() < Tuning.Ollama.MAX_CONCURRENT) {
			LlmRequest next = pollNext();
			if (next == null) {
				return;
			}
			dispatch(next, config);
		}
	}

	private LlmRequest pollNext() {
		for (LlmPriority priority : LlmPriority.values()) {
			ArrayDeque<LlmRequest> queue = this.queues.get(priority);
			if (!queue.isEmpty()) {
				return queue.removeFirst();
			}
		}
		return null;
	}

	private void dispatch(LlmRequest request, TalkingVillagersConfig config) {
		HttpRequest httpRequest;
		try {
			httpRequest = HttpRequest.newBuilder()
				.uri(URI.create(config.ollama.baseUrl + "/api/generate"))
				.header("Content-Type", "application/json")
				.timeout(Duration.ofMillis(config.ollama.timeoutMs))
				.POST(HttpRequest.BodyPublishers.ofString(buildBody(request, config)))
				.build();
		} catch (IllegalArgumentException e) {
			// A malformed base_url is a config error, not a transient outage; say so once and
			// treat the endpoint as down so we stop retrying every tick.
			TalkingVillagers.LOGGER.error("Invalid ollama.base_url '{}'", config.ollama.baseUrl, e);
			markUnhealthy(config);
			this.pendingCallbacks.add(request.onFailure());
			return;
		}

		int dispatchedIn = this.generation.get();
		this.inFlight.incrementAndGet();
		this.http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
			.whenComplete((response, error) -> {
				if (this.generation.get() != dispatchedIn) {
					// The world this request belonged to has gone; there is nobody left to answer.
					return;
				}
				this.inFlight.decrementAndGet();
				if (error != null) {
					noteFailure(error.getMessage());
					this.pendingCallbacks.add(request.onFailure());
					return;
				}
				if (response.statusCode() != 200) {
					noteFailure("HTTP " + response.statusCode());
					this.pendingCallbacks.add(request.onFailure());
					return;
				}

				String reply = extractReply(response.body());
				if (reply.isEmpty()) {
					noteFailure("empty reply");
					this.pendingCallbacks.add(request.onFailure());
					return;
				}

				this.consecutiveFailures.set(0);
				this.pendingCallbacks.add(() -> request.onReply().accept(reply));
			});
	}

	private String buildBody(LlmRequest request, TalkingVillagersConfig config) {
		JsonObject options = new JsonObject();
		options.addProperty("temperature", config.ollama.temperature);
		options.addProperty("num_predict", config.ollama.maxTokens);

		JsonObject body = new JsonObject();
		body.addProperty("model", config.ollama.model);
		body.addProperty("system", request.system());
		body.addProperty("prompt", request.user());
		body.addProperty("stream", false);
		// Thinking models emit their reasoning into a separate "thinking" field and will happily
		// spend the whole token budget there — num_predict truncates the thought, "response"
		// comes back empty, and the villager says nothing. Asking Ollama not to think routes
		// every token to speech; for models that cannot think, this is a no-op.
		body.addProperty("think", false);
		// Ask Ollama to keep the model resident. Villages speak in bursts with long silences,
		// and Ollama's five-minute default eviction means the villager who breaks a lull would
		// otherwise wait out a full model load and time out.
		body.addProperty("keep_alive", Tuning.Ollama.KEEP_ALIVE);
		body.add("options", options);
		return body.toString();
	}

	/** Pulls the generated text out of an Ollama {@code /api/generate} response. */
	private String extractReply(String body) {
		try {
			JsonObject json = JsonParser.parseString(body).getAsJsonObject();
			if (json.has("error")) {
				TalkingVillagers.LOGGER.warn("Ollama reported an error: {}", json.get("error").getAsString());
				return "";
			}
			if (!json.has("response")) {
				return "";
			}
			return ReplyCleaner.clean(json.get("response").getAsString());
		} catch (JsonSyntaxException | IllegalStateException | UnsupportedOperationException e) {
			TalkingVillagers.LOGGER.warn("Could not parse Ollama response", e);
			return "";
		}
	}

	private void noteFailure(String reason) {
		int failures = this.consecutiveFailures.incrementAndGet();
		if (failures == FAILURE_THRESHOLD) {
			TalkingVillagers.LOGGER.warn(
				"Ollama unreachable ({}); villagers will stay quiet until it responds again", reason);
			markUnhealthy(TalkingVillagers.config());
		} else if (failures < FAILURE_THRESHOLD) {
			TalkingVillagers.LOGGER.debug("Ollama request failed ({})", reason);
		}
	}

	private void markUnhealthy(TalkingVillagersConfig config) {
		this.healthy = false;
		this.ticksUntilProbe = config.ollama.healthCheckIntervalTicks;
		discardQueued();
	}

	/**
	 * Throws away queued work. Called when the endpoint goes down: delivering these later
	 * would mean villagers suddenly answering questions asked minutes ago.
	 */
	private void discardQueued() {
		for (ArrayDeque<LlmRequest> queue : this.queues.values()) {
			while (!queue.isEmpty()) {
				this.pendingCallbacks.add(queue.removeFirst().onFailure());
			}
		}
	}

	/** Periodically asks Ollama whether it is back, without burning a real request on it. */
	private void tickHealthProbe(TalkingVillagersConfig config) {
		if (this.probeInFlight || --this.ticksUntilProbe > 0) {
			return;
		}
		this.ticksUntilProbe = config.ollama.healthCheckIntervalTicks;

		HttpRequest probe;
		try {
			probe = HttpRequest.newBuilder()
				.uri(URI.create(config.ollama.baseUrl + "/api/tags"))
				.timeout(Duration.ofSeconds(3))
				.GET()
				.build();
		} catch (IllegalArgumentException e) {
			return;
		}

		this.probeInFlight = true;
		this.http.sendAsync(probe, HttpResponse.BodyHandlers.discarding())
			.whenComplete((response, error) -> {
				this.probeInFlight = false;
				if (error == null && response.statusCode() == 200) {
					this.consecutiveFailures.set(0);
					this.healthy = true;
					TalkingVillagers.LOGGER.info("Ollama is responding again; villagers can talk");
				}
			});
	}

	public boolean healthy() {
		return this.healthy;
	}

	private int queuedCount() {
		int total = 0;
		for (ArrayDeque<LlmRequest> queue : this.queues.values()) {
			total += queue.size();
		}
		return total;
	}

	/**
	 * Forgets everything to do with the world that just stopped, while staying usable.
	 *
	 * <p>Called when a server shuts down — which, in single-player, means every time the player
	 * returns to the title screen. The HTTP client deliberately survives: it belongs to the
	 * process, not to the world, and closing it left the next world talking to a dead client that
	 * failed every request with "closed", so villagers were silent from the second world onwards
	 * with no way to recover short of restarting the game.
	 *
	 * <p>Queued work is abandoned outright rather than failed: those callbacks would touch
	 * entities that are already unloading. Health is reset optimistically, since a fresh world is
	 * a fresh chance for Ollama to be up.
	 */
	public void reset() {
		this.generation.incrementAndGet();
		this.queues.values().forEach(ArrayDeque::clear);
		this.pendingCallbacks.clear();
		this.inFlight.set(0);
		this.consecutiveFailures.set(0);
		this.healthy = true;
		this.ticksUntilProbe = 0;
	}

	/**
	 * Releases the HTTP client for good. Only for process shutdown — see {@link #reset()} for
	 * what happens between worlds.
	 */
	@Override
	public void close() {
		reset();
		this.http.close();
	}
}
