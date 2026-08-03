# Graph Report - mieai-qqbot  (2026-08-03)

## Corpus Check
- 38 files · ~13,036 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 514 nodes · 733 edges · 46 communities (34 shown, 12 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 40 edges (avg confidence: 0.81)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `6f15f6e2`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- HistoryDatabase
- required
- properties
- MieAiBotConfig.kt
- OpenAiChatProvider
- MieAiEngine
- properties
- .handleLocked
- Default Plugin Configuration
- MieAiBotConfig
- Pf4jSqliteParentLoaderIntegrationTest
- ModelFailoverManager
- DeliveryTracker
- .insertInbound
- AttachmentParser
- GroupChatQueue
- items
- ImageDownloader
- MieAiConfigCodecTest
- properties
- ImagePersistenceService
- TriggerDeciderTest
- gradlew
- .sendQuoted
- defaultKeyword
- defaultProbability
- defaultSystemPrompt
- keywordMaxLength
- keywordTooLongReply
- maxContextMessages
- promptMaxLength
- promptTooLongReply
- groupKeywords
- groupProbabilities
- AGENTS.md
- ChatTaskStatus
- HistoryModels.kt
- queue
- StoredMessage
- NewInboundMessage
- .cleanup
- .differentGroupsRunInParallelWhileWaitingCapacityExcludesTheRunningTask
- BotMentionParser
- BotMentionParserTest

## God Nodes (most connected - your core abstractions)
1. `HistoryDatabase` - 45 edges
2. `MieAiEngine` - 16 edges
3. `OpenAiChatProvider` - 15 edges
4. `required` - 14 edges
5. `Default Plugin Configuration` - 13 edges
6. `ModelFailoverManager` - 10 edges
7. `Pf4jSqliteParentLoaderIntegrationTest` - 10 edges
8. `CommandService` - 9 edges
9. `MieAiBotConfig` - 9 edges
10. `GroupChatQueue` - 9 edges

## Surprising Connections (you probably didn't know these)
- `config.yml` --semantically_similar_to--> `Default Plugin Configuration`  [INFERRED] [semantically similar]
  README.md → src/main/resources/qqbot-plugin-default.yml
- `api.protocol` --semantically_similar_to--> `openai-old Protocol`  [INFERRED] [semantically similar]
  src/main/resources/qqbot-plugin-default.yml → README.md
- `api.protocol` --semantically_similar_to--> `openai-new Protocol`  [INFERRED] [semantically similar]
  src/main/resources/qqbot-plugin-default.yml → README.md
- `api.primaryModel` --conceptually_related_to--> `mimo-v2.5 Model`  [INFERRED]
  src/main/resources/qqbot-plugin-default.yml → README.md
- `MieAiBotPlugin` --references--> `MieAiEngine`  [EXTRACTED]
  src/main/kotlin/com/mieai/bot/MieAiBotPlugin.kt → src/main/kotlin/com/mieai/bot/MieAiEngine.kt

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **API Configuration Parameters** — src_main_resources_qqbot_plugin_default_yml_api_baseurl, src_main_resources_qqbot_plugin_default_yml_api_apikey, src_main_resources_qqbot_plugin_default_yml_api_protocol, src_main_resources_qqbot_plugin_default_yml_api_primarymodel, src_main_resources_qqbot_plugin_default_yml_api_fallbackmodel [EXTRACTED 1.00]
- **Chat Configuration Parameters** — src_main_resources_qqbot_plugin_default_yml_chat_defaultprobability, src_main_resources_qqbot_plugin_default_yml_chat_defaultsystemprompt, src_main_resources_qqbot_plugin_default_yml_chat_defaultkeyword, src_main_resources_qqbot_plugin_default_yml_chat_imageunderstandingenabled [EXTRACTED 1.00]

## Communities (46 total, 12 thin omitted)

### Community 0 - "HistoryDatabase"
Cohesion: 0.17
Nodes (4): Connection, HistoryDatabase, AutoCloseable, ChatTaskRecord

### Community 1 - "required"
Cohesion: 0.05
Nodes (37): api, apiKey, baseUrl, chat, defaultKeyword, defaultProbability, defaultSystemPrompt, disabledGroups (+29 more)

### Community 2 - "properties"
Cohesion: 0.05
Nodes (38): cleanupDeleteBatchSize, cleanupTime, defaultMaxMessagesPerGroup, groupMaxMessages, maxBase64ImageBytes, maxMessageAgeDays, maxMessagesTotal, maximum (+30 more)

### Community 3 - "MieAiBotConfig.kt"
Cohesion: 0.09
Nodes (24): AiProtocol, OPENAI_NEW, OPENAI_OLD, ApiConfig, ChatConfig, codePointLength(), ConfigValidationException, defaults() (+16 more)

### Community 4 - "OpenAiChatProvider"
Cohesion: 0.12
Nodes (14): JsonNode, ObjectNode, RuntimeException, ChatApiException, ChatCompletionRequest, ChatProvider, ChatRole, ASSISTANT (+6 more)

### Community 5 - "MieAiEngine"
Cohesion: 0.09
Nodes (13): EventSubscription, PluginEvent, PluginTestContext, BotPlugin, MieAiBotPlugin, BotPlugin, BotPluginFactory, PluginRuntimeContext (+5 more)

### Community 6 - "properties"
Cohesion: 0.07
Nodes (30): openai-new, openai-old, properties, maxLength, type, maxLength, minLength, pattern (+22 more)

### Community 7 - ".handleLocked"
Cohesion: 0.14
Nodes (12): GroupMemberRole, codePointLength(), CommandOutcome, CommandService, DesiredCommand, Handled, NotCommand, ParsedCommand (+4 more)

### Community 8 - "Default Plugin Configuration"
Cohesion: 0.10
Nodes (23): config.yml, history.db, Java 21, mieai-bot Plugin, MieBot Framework, mimo-v2.5 Model, openai-new Protocol, openai-old Protocol (+15 more)

### Community 9 - "MieAiBotConfig"
Cohesion: 0.16
Nodes (10): MieAiBotConfig, ConfigParseException, IllegalArgumentException, T, V, MieAiConfigCodec, load(), MieAiConfigStore (+2 more)

### Community 10 - "Pf4jSqliteParentLoaderIntegrationTest"
Cohesion: 0.20
Nodes (7): FakeEventService, Fixture, AutoCloseable, BotPluginFactory, Fixture, PluginRuntimeContext, Pf4jSqliteParentLoaderIntegrationTest

### Community 11 - "ModelFailoverManager"
Cohesion: 0.23
Nodes (6): FallbackWindow, T, ModelFailoverManager, ModelSelection, RouteKey, ModelFailoverManagerTest

### Community 12 - "DeliveryTracker"
Cohesion: 0.29
Nodes (3): java, DeliveryTracker, OpenDelivery

### Community 13 - ".insertInbound"
Cohesion: 0.23
Nodes (4): T, setNullableString(), singleLongOrNull(), ImageAttachment

### Community 15 - "GroupChatQueue"
Cohesion: 0.39
Nodes (4): GroupChatQueue, AutoCloseable, Worker, WorkerSelection

### Community 16 - "items"
Cohesion: 0.25
Nodes (8): items, maxItems, type, maxLength, minLength, pattern, type, disabledGroups

### Community 17 - "ImageDownloader"
Cohesion: 0.38
Nodes (3): ByteArray, ImageDownloader, AutoCloseable

### Community 19 - "properties"
Cohesion: 0.33
Nodes (6): properties, additionalProperties, type, type, groupSystemPrompts, imageUnderstandingEnabled

### Community 22 - "gradlew"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 24 - "defaultKeyword"
Cohesion: 0.50
Nodes (4): maxLength, minLength, type, defaultKeyword

### Community 25 - "defaultProbability"
Cohesion: 0.50
Nodes (4): maximum, minimum, type, defaultProbability

### Community 26 - "defaultSystemPrompt"
Cohesion: 0.50
Nodes (4): maxLength, minLength, type, defaultSystemPrompt

### Community 27 - "keywordMaxLength"
Cohesion: 0.50
Nodes (4): maximum, minimum, type, keywordMaxLength

### Community 28 - "keywordTooLongReply"
Cohesion: 0.50
Nodes (4): maxLength, minLength, type, keywordTooLongReply

### Community 29 - "maxContextMessages"
Cohesion: 0.50
Nodes (4): maximum, minimum, type, maxContextMessages

### Community 30 - "promptMaxLength"
Cohesion: 0.50
Nodes (4): maximum, minimum, type, promptMaxLength

### Community 31 - "promptTooLongReply"
Cohesion: 0.50
Nodes (4): maxLength, minLength, type, promptTooLongReply

### Community 32 - "groupKeywords"
Cohesion: 0.67
Nodes (3): additionalProperties, type, groupKeywords

### Community 33 - "groupProbabilities"
Cohesion: 0.67
Nodes (3): additionalProperties, type, groupProbabilities

### Community 37 - "ChatTaskStatus"
Cohesion: 0.33
Nodes (6): ChatTaskStatus, CANCELLED, COMPLETED, FAILED, PENDING, RUNNING

### Community 38 - "HistoryModels.kt"
Cohesion: 0.18
Nodes (7): CleanupResult, CommandPlan, MessageDirection, BOT, MEMBER, PendingImage, StoredImage

### Community 39 - "queue"
Cohesion: 0.20
Nodes (10): maxPendingPerGroup, maximum, minimum, type, maxPendingPerGroup, queue, additionalProperties, properties (+2 more)

## Knowledge Gaps
- **149 isolated node(s):** `USER`, `ASSISTANT`, `NotCommand`, `OPENAI_OLD`, `OPENAI_NEW` (+144 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **12 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `HistoryDatabase` connect `HistoryDatabase` to `HistoryModels.kt`, `.handleLocked`, `StoredMessage`, `NewInboundMessage`, `.cleanup`, `.differentGroupsRunInParallelWhileWaitingCapacityExcludesTheRunningTask`, `DeliveryTracker`, `.insertInbound`?**
  _High betweenness centrality (0.109) - this node is a cross-community bridge._
- **Why does `validateBaseUrl()` connect `MieAiBotConfig.kt` to `OpenAiChatProvider`?**
  _High betweenness centrality (0.100) - this node is a cross-community bridge._
- **Why does `StoredImage` connect `HistoryModels.kt` to `StoredMessage`, `ImageDownloader`, `OpenAiChatProvider`?**
  _High betweenness centrality (0.085) - this node is a cross-community bridge._
- **Are the 3 inferred relationships involving `OpenAiChatProvider` (e.g. with `.everyMimoV25PrefixUsesOpenAiBearerChatCompletionsAndMultipleBase64Images()` and `.usesChatCompletionsAndBearerForOpenAiOld()`) actually correct?**
  _`OpenAiChatProvider` has 3 INFERRED edges - model-reasoned connections that need verification._
- **What connects `USER`, `ASSISTANT`, `NotCommand` to the rest of the system?**
  _149 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `required` be split into smaller, more focused modules?**
  _Cohesion score 0.05263157894736842 - nodes in this community are weakly interconnected._
- **Should `properties` be split into smaller, more focused modules?**
  _Cohesion score 0.05263157894736842 - nodes in this community are weakly interconnected._