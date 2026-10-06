package io.github.yudiiee.aicompanion;

import ai.djl.ModelException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import io.github.yudiiee.aicompanion.ChatUtils.BERTModel.BertModelManager;
import io.github.yudiiee.aicompanion.ChatUtils.NLPProcessor;
import io.github.yudiiee.aicompanion.Commands.configCommand;
import io.github.yudiiee.aicompanion.Commands.modCommandRegistry;
import io.github.yudiiee.aicompanion.Database.QTable;
import io.github.yudiiee.aicompanion.Database.SQLiteDB;
import io.github.yudiiee.aicompanion.FilingSystem.ManualConfig;
import io.github.yudiiee.aicompanion.GameAI.BotEventHandler;
import io.github.yudiiee.aicompanion.GameAI.autonomous.AutonomousManager;
import io.github.yudiiee.aicompanion.GameAI.autonomous.ServerChatEventBridge;
import io.github.yudiiee.aicompanion.GameAI.handoff.ItemHandoffListener;
import io.github.yudiiee.aicompanion.GameAI.handoff.TradeListener;

import io.github.yudiiee.aicompanion.Database.QTableStorage;
import io.github.yudiiee.aicompanion.Entity.AutoFaceEntity;
import io.github.yudiiee.aicompanion.GameAI.RLAgent;
import io.github.yudiiee.aicompanion.Network.OpenConfigPayload;
import io.github.yudiiee.aicompanion.Network.SaveAPIKeyPayload;
import io.github.yudiiee.aicompanion.Network.SaveConfigPayload;
import io.github.yudiiee.aicompanion.Network.SaveCustomProviderPayload;
import io.github.yudiiee.aicompanion.Network.configNetworkManager;
import io.github.yudiiee.aicompanion.PlayerUtils.FoodConsumptionTool;
import io.github.yudiiee.aicompanion.PlayerUtils.MiningTool;
import io.github.yudiiee.aicompanion.PathFinding.NavigationService;
import io.github.yudiiee.aicompanion.WebSearch.AISearchConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;


public class AICompanion implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("ai-companion");
	static { DataMigration.run(); } // old config names -> new, before the config below is read
	public static final ManualConfig CONFIG = ManualConfig.load();
	public static MinecraftServer serverInstance = null; // default for now
	public static BertModelManager modelManager;
	public static boolean loadedBERTModelIntoMemory = false;
	/** Completes when the background NLP model download/check has finished. */
	private static volatile CompletableFuture<Void> NLP_MODELS_READY = null;


	@Override
	public void onInitialize() {

		LOGGER.info("Hello Fabric world!");

		LOGGER.debug("Running on environment type: {}", FabricLoader.getInstance().getEnvironmentType());

		// Fix DJL cache directory path on Windows (Issue #33)
		// DJL constructs paths incorrectly on Windows, missing backslash after username
		// Explicitly set the cache directory to avoid path construction bugs
		String userHome = System.getProperty("user.home");
		if (userHome != null && !userHome.isEmpty()) {
			String djlCacheDir = userHome + "/.djl.ai";
			System.setProperty("DJL_CACHE_DIR", djlCacheDir);
			LOGGER.info("Set DJL cache directory to: {}", djlCacheDir);
		}

		String llmProvider = System.getProperty("aicompanion.llmMode", "custom");

		System.out.println("Using provider: " + llmProvider);

		// Debug: Print ALL system properties to see what's available
		System.out.println("=== ALL SYSTEM PROPERTIES ===");
		System.getProperties().forEach((key, value) -> {
			if (key.toString().contains("aicompanion") || key.toString().contains("llm")) {
				System.out.println(key + " = " + value);
			}
		});
		System.out.println("=== END DEBUG ===");


        // registering the packets on the global entrypoint to recognise them

		PayloadTypeRegistry.serverboundPlay().register(SaveConfigPayload.ID, SaveConfigPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(OpenConfigPayload.ID, OpenConfigPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SaveAPIKeyPayload.ID, SaveAPIKeyPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SaveCustomProviderPayload.ID, SaveCustomProviderPayload.CODEC);


		modCommandRegistry.register();
		configCommand.register();
		SQLiteDB.createDB();
		QTableStorage.setupQTableStorage();

		// Register the server-side chat bridge so WorldEventListener receives
		// join/leave/death/advancement messages for all bots.
		ServerChatEventBridge.register();

		// Human-like layer: natural chat, body language, quick reactions, small talk.
		io.github.yudiiee.aicompanion.GameAI.human.HumanConfig.load();
		io.github.yudiiee.aicompanion.GameAI.human.HumanChatListener.register();
		io.github.yudiiee.aicompanion.GameAI.human.HumanBehavior.register();
		io.github.yudiiee.aicompanion.GameAI.human.HumanLikeCommand.register();

		// Feature 4.2 — Smart Item Handoff: react to players throwing items at the bot.
		ItemHandoffListener.register();

		// Feature 7 — Trade Request System: wire up sneak-throw item detection.
		// TradeListener hooks PlayerPickupItemCallback to detect when a player throws
		// an item while sneaking near the bot, driving the two-phase chat-based trade flow.
		TradeListener.register();
		NavigationService.register();
		MiningTool.register();

		NLP_MODELS_READY = CompletableFuture.runAsync(() -> {

			AISearchConfig.setupIfMissing();
			NLPProcessor.ensureLocalNLPModel();
			try {
				Thread.sleep(2000);
				System.out.println("NLP model deployment task complete");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

		});


		modelManager = BertModelManager.getInstance();

		// Inside AICompanion.onInitialize()
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			configNetworkManager.registerServerModelNameSaveReceiver(server);
			configNetworkManager.registerServerAPIKeySaveReceiver(server);
			configNetworkManager.registerServerCustomProviderSaveReceiver(server);
			serverInstance = server;
			LOGGER.info("Server instance stored!");

			System.out.println("Server instance is " + serverInstance);

			// Load the intent model in the background, after the NLP model download has
			// finished. Loading on the server thread froze world loading and crashed the
			// server when the files weren't downloaded yet ("No model with the specified URI").
			LOGGER.info("Scheduling background load of the BERT intent model");
			CompletableFuture<Void> ready = NLP_MODELS_READY != null ? NLP_MODELS_READY : CompletableFuture.completedFuture(null);
			ready.whenCompleteAsync((ignored, downloadError) -> {
				try {
					modelManager.loadModel();
					loadedBERTModelIntoMemory = true;
					LOGGER.info("BERT model loaded into memory. It will stay in memory as long as any bot stays active in game.");
				} catch (Throwable e) {
					Throwable cause = e.getCause() != null ? e.getCause() : e;
					LOGGER.warn("BERT intent model not available yet ({}). Intent detection will use the other classifiers / the LLM until it is.", cause.getMessage());
				}
			});


		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			MiningTool.cancelAll(server, "Server stopped");
			NavigationService.cancelAll(server, "Server stopped");
			FoodConsumptionTool.reset();

			AutoFaceEntity.onServerStopped(server);
			io.github.yudiiee.aicompanion.GameAI.human.ConversationMemory.clear();
			io.github.yudiiee.aicompanion.GameAI.human.SurvivalBrain.stopAll();

			// Gracefully shut down all autonomous goal engines
			AutonomousManager.getInstance().stopAll();

			try {
				if (modelManager.isModelLoaded() || loadedBERTModelIntoMemory) {
					modelManager.unloadModel();
					System.out.println("Unloaded BERT Model from memory");
				}
				else {
					System.out.println("BERT Model was not loaded, skipping unloading...");
				}

			} catch (IOException e) {
				LOGGER.error("BERT Model unloading failed!", e);
			}

		});

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayer serverPlayer) {
                if (BotEventHandler.bot != null && serverPlayer.getUUID().equals(BotEventHandler.bot.getUUID())) {
                    // Save state first
                    QTableStorage.saveLastKnownState(BotEventHandler.getCurrentState(), BotEventHandler.qTableDir + "/lastKnownState.bin");

                    try {
                        // Load needed data for learning
                        QTable qTable = QTableStorage.loadQTable();
                        if (qTable == null) qTable = new QTable();

                        // Create a temporary agent wrapper for the learning process
                        RLAgent tempAgent = new RLAgent(0.1, qTable);

                        // Trigger death learning
                        BotEventHandler.handleBotDeath(qTable, tempAgent);

                    } catch (Exception e) {
                        LOGGER.error("Error during death learning trigger: ", e);
                    }
                }
            }
        });

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			// Check if the respawned player is the bot
			if (oldPlayer instanceof ServerPlayer && newPlayer instanceof ServerPlayer && oldPlayer.getName().getString().equals(newPlayer.getName().getString())) {
				System.out.println("Bot has respawned. Updating state...");
				BotEventHandler.hasRespawned = true;
				BotEventHandler.botSpawnCount++;

			}
		});

		// Player retaliation tracking - track hits on bot players
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			// Check if the damaged entity is a bot player
			if (entity instanceof ServerPlayer bot) {
				// Check if damage source is another player
				if (source.getEntity() instanceof net.minecraft.world.entity.player.Player attacker) {
					// Record the hit for retaliation tracking
					io.github.yudiiee.aicompanion.PlayerUtils.PlayerRetaliationTracker.recordPlayerHit(bot, attacker);
					if (attacker instanceof ServerPlayer attackerPlayer
							&& io.github.yudiiee.aicompanion.GameAI.human.HumanBehavior.isAiBot(bot)) {
						io.github.yudiiee.aicompanion.GameAI.human.HumanBehavior.onBotHurtByPlayer(bot, attackerPlayer);
					}
				}
			}
			return true; // Allow damage to proceed
		});

	}


}
