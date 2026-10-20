package com.talkingvillagers.config;

/**
 * The shipped default config, verbatim as it is written to disk on first run.
 *
 * <p>This text is also parsed as the fallback for any key missing from the user's file, so the
 * comments here document the values the mod actually uses — the two cannot drift apart.
 *
 * <p>Only settings a server owner genuinely needs are exposed. Everything else that shapes
 * village behaviour is fixed in {@link com.talkingvillagers.Tuning}.
 */
final class DefaultConfig {
	private DefaultConfig() {
	}

	static final String TEXT = """
		# Talking Villagers configuration.
		#
		# ===========================================================================
		#  Setting up Ollama
		#
		#  Villagers speak using a language model you run yourself. Nothing is sent
		#  to any external service, and the mod never blocks the server waiting on
		#  it: if the model is slow or missing, villagers just go quiet.
		#
		#  1. Install Ollama from https://ollama.com/download
		#     Linux:  curl -fsSL https://ollama.com/install.sh | sh
		#
		#  2. Download a model. A 3B model is what this mod is tuned for:
		#         ollama pull llama3.2:3b
		#     See what you already have with:
		#         ollama list
		#     The "model" setting below must match a name from that list exactly,
		#     including the tag after the colon.
		#
		#  3. Make sure Ollama is running. It usually starts with your computer and
		#     listens on port 11434. If it is not running, start it with:
		#         ollama serve
		#     Check it is up:
		#         curl http://localhost:11434
		#     A working server answers "Ollama is running".
		#
		#  4. If Ollama is on the SAME machine as the Minecraft server, the default
		#     base_url below already works. There is nothing else to do.
		#
		#  5. If Ollama is on a DIFFERENT machine, it refuses outside connections
		#     until told otherwise. On the machine running Ollama, set the
		#     environment variable
		#         OLLAMA_HOST=0.0.0.0
		#     restart Ollama, allow port 11434 through that machine's firewall, and
		#     then point base_url at its address, for example
		#         base_url = "http://192.168.1.50:11434"
		#
		#  6. Start the Minecraft server and walk up to a village. The mod adds no
		#     commands at all - everything it does happens by playing: sneak-click
		#     a villager to talk to them, or sneak-click them (or a village bell)
		#     with a plain book to be handed a written record. The server log says
		#     whether Ollama could be reached.
		#
		#  Troubleshooting
		#     The log says Ollama is not responding
		#         Ollama is not running, or base_url has the wrong host or port.
		#         Test it with the curl command in step 3.
		#     Villagers never say anything
		#         The model name probably does not match "ollama list", or the model
		#         is still downloading. The server log says which it is.
		#     Replies take too long to appear
		#         The model is too big for the hardware. Try a smaller one, or raise
		#         timeout_ms.
		#     Everything worked and then stopped
		#         Nothing to do. The mod re-checks Ollama every
		#         health_check_interval_ticks and villagers resume on their own.
		# ===========================================================================

		[names]
		# First names given to male villagers.
		first_names_male = [
			"Albin", "Alvar", "Anders", "Anton", "Arvid", "Axel", "Benjamin", "Björn",
			"Casper", "Edvin", "Elias", "Emil", "Erik", "Filip", "Folke", "Fredrik",
			"Gunnar", "Gustav", "Hampus", "Harald", "Henrik", "Hjalmar", "Hugo", "Isak",
			"Ivar", "Jonas", "Josef", "Klas", "Knut", "Lars", "Leif", "Linus",
			"Ludvig", "Magnus", "Malte", "Mattias", "Mikael", "Nils", "Olof", "Oskar",
			"Otto", "Rasmus", "Roland", "Sigurd", "Sixten", "Sten", "Sture", "Torsten",
			"Valter", "Viktor",
		]
		# First names given to female villagers.
		first_names_female = [
			"Agnes", "Alice", "Alma", "Anneli", "Astrid", "Birgitta", "Britta", "Cecilia",
			"Dagny", "Ebba", "Elin", "Elsa", "Emma", "Frida", "Gerda", "Greta",
			"Gunilla", "Hanna", "Hedda", "Helga", "Ida", "Ingrid", "Iris", "Jenny",
			"Johanna", "Karin", "Kerstin", "Klara", "Linnea", "Lisbet", "Lovisa", "Maja",
			"Marit", "Matilda", "Nora", "Ottilia", "Ragnhild", "Rakel", "Saga", "Selma",
			"Signe", "Siri", "Solveig", "Stina", "Sylvia", "Tove", "Ulla", "Vega",
			"Vera", "Ylva",
		]
		# Surnames for villagers with no parents. Children inherit their father's instead.
		surnames = [
			"Andersson", "Axelsson", "Bengtsson", "Berg", "Berglund", "Bergman", "Björk",
			"Blomqvist", "Dahl", "Eklund", "Ekström", "Engström", "Eriksson", "Falk",
			"Forsberg", "Gustafsson", "Hallberg", "Hedlund", "Holm", "Holmberg",
			"Isaksson", "Jakobsson", "Johansson", "Jonsson", "Karlsson", "Kjellberg",
			"Larsson", "Lind", "Lindberg", "Lindqvist", "Ljung", "Lundgren", "Magnusson",
			"Mårtensson", "Nilsson", "Nordin", "Nyström", "Olofsson", "Palm", "Persson",
			"Sandberg", "Sjöberg", "Strand", "Sundqvist", "Svensson", "Söderberg",
			"Wallin", "Vikström", "Åberg", "Öberg",
		]
		# Settlement names are one prefix plus one suffix: "Björk" + "vik" = "Björkvik".
		settlement_name_prefixes = [
			"Al", "Berg", "Björk", "Djup", "Ek", "Fager", "Frost", "Grå", "Grön",
			"Guld", "Hassel", "Hög", "Kvarn", "Ljung", "Ljus", "Lång", "Ny", "Rönn",
			"Röd", "Sol", "Sten", "Tall", "Torn", "Vass",
		]
		settlement_name_suffixes = [
			"backe", "bäck", "borg", "by", "dal", "fall", "fors", "gård", "hamn",
			"holm", "hult", "kors", "lund", "mark", "näs", "ryd", "sjö", "stad",
			"torp", "vik",
		]

		[ollama]
		# Address of the Ollama server.
		base_url = "http://localhost:11434"
		# Model used for villager speech. Any you have pulled, e.g. ["llama3.2:3b", "mistral", "qwen3:8b"]
		model = "llama3.2:3b"
		# Whether thinking models may reason before they answer. Their reasoning is steered by
		# the villager's persona and the conversation, so replies come out more in character -
		# at the cost of a slower answer, typically several seconds for a small thinking model.
		# While a villager thinks, "Nils is thinking..." floats over their head. The mod asks
		# Ollama for the extra tokens itself, so max_tokens stays the speech budget. Has no
		# effect on models that cannot think.
		thinking = true
		# Milliseconds to wait for a reply before abandoning it. The villager just stays silent.
		# The very first request also loads the model, which can take far longer than a reply.
		timeout_ms = 30000
		# Longest reply a villager may give, in tokens. Higher lets them ramble, and is slower.
		# Kept a little above the one-sentence target the prompt asks for, so a legitimate short
		# reply has room to finish its own punctuation instead of being cut off mid-word.
		max_tokens = 48
		# How random replies are, 0.0 to 2.0. Low repeats itself, high stops making sense or
		# following instructions - a small model drifts off the length and character rules much
		# more often above this.
		temperature = 0.7
		# How many requests may wait in line. Past this the least important are dropped.
		queue_capacity = 32
		# Ticks between retries while Ollama is unreachable. 20 ticks = 1 second.
		health_check_interval_ticks = 200
		# How many people one villager can remember. Higher costs prompt length and save size.
		relationship_cap = 24

		[sexuality]
		# Whether villagers are given an orientation. false makes every villager heterosexual.
		# Does not affect the chance of a villager being transgender, which is separate.
		enable_sexuality = true

		[conversation]
		# Where a villager's speech appears: floating over their head, in your chat box, or both.
		# ["overhead", "chat", "both"]
		display = "both"
		# How long floating speech stays up, in ticks. 20 ticks = 1 second.
		overhead_duration_ticks = 240
		# How far you can walk from a villager before the conversation ends, in blocks.
		max_distance = 6.0
		# Whether villagers give off particles while talking.
		particles_while_talking = true

		[needs]
		# Whether settlements have to feed themselves. Villagers starve if their farms cannot
		# keep up with the population.
		food_enabled = true
		""";
}
