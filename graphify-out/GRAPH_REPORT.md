# Graph Report - mieai-qqbot  (2026-08-12)

## Corpus Check
- 38 files · ~13,911 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 563 nodes · 889 edges · 33 communities (32 shown, 1 thin omitted)
- Extraction: 96% EXTRACTED · 4% INFERRED · 0% AMBIGUOUS · INFERRED: 33 edges (avg confidence: 0.81)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `7de8d9ed`
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
- PluginEvent
- Default Plugin Configuration
- packagedDefaultConfig
- Pf4jSqliteParentLoaderIntegrationTest
- ModelFailoverManager
- DeliveryTracker
- properties
- CommandService
- GroupChatQueue
- items
- ImageDownloader
- properties
- gradlew
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

## God Nodes (most connected - your core abstractions)
1. `HistoryDatabase` - 53 edges
2. `MieAiEngine` - 27 edges
3. `packagedDefaultConfig()` - 19 edges
4. `OpenAiChatProvider` - 16 edges
5. `CommandService` - 16 edges
6. `required` - 14 edges
7. `Default Plugin Configuration` - 13 edges
8. `CommandAliasesConfig` - 12 edges
9. `ModelFailoverManager` - 11 edges
10. `GroupChatQueue` - 11 edges

## Surprising Connections (you probably didn't know these)
- `config.yml` --semantically_similar_to--> `Default Plugin Configuration`  [INFERRED] [semantically similar]
  README.md → src/main/resources/qqbot-plugin-default.yml
- `api.protocol` --semantically_similar_to--> `openai-new Protocol`  [INFERRED] [semantically similar]
  src/main/resources/qqbot-plugin-default.yml → README.md
- `api.protocol` --semantically_similar_to--> `openai-old Protocol`  [INFERRED] [semantically similar]
  src/main/resources/qqbot-plugin-default.yml → README.md
- `api.primaryModel` --conceptually_related_to--> `mimo-v2.5 Model`  [INFERRED]
  src/main/resources/qqbot-plugin-default.yml → README.md
- `MieAiEngine` --calls--> `ModelFailoverManager`  [EXTRACTED]
  src/main/kotlin/com/mieai/bot/MieAiEngine.kt → src/main/kotlin/com/mieai/bot/ai/ModelFailoverManager.kt

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **API Configuration Parameters** — src_main_resources_qqbot_plugin_default_yml_api_baseurl, src_main_resources_qqbot_plugin_default_yml_api_apikey, src_main_resources_qqbot_plugin_default_yml_api_protocol, src_main_resources_qqbot_plugin_default_yml_api_primarymodel, src_main_resources_qqbot_plugin_default_yml_api_fallbackmodel [EXTRACTED 1.00]
- **Chat Configuration Parameters** — src_main_resources_qqbot_plugin_default_yml_chat_defaultprobability, src_main_resources_qqbot_plugin_default_yml_chat_defaultsystemprompt, src_main_resources_qqbot_plugin_default_yml_chat_defaultkeyword, src_main_resources_qqbot_plugin_default_yml_chat_imageunderstandingenabled [EXTRACTED 1.00]

## Communities (33 total, 1 thin omitted)

### Community 0 - "HistoryDatabase"
Cohesion: 0.06
Nodes (25): Connection, OutboundService, bindAll(), HistoryDatabase, AutoCloseable, T, setNullableString(), singleLongOrNull() (+17 more)

### Community 1 - "required"
Cohesion: 0.04
Nodes (47): api, apiKey, baseUrl, chat, defaultKeyword, defaultProbability, defaultSystemPrompt, disabledGroups (+39 more)

### Community 2 - "properties"
Cohesion: 0.05
Nodes (38): cleanupDeleteBatchSize, cleanupTime, defaultMaxMessagesPerGroup, groupMaxMessages, maxBase64ImageBytes, maxMessageAgeDays, maxMessagesTotal, maximum (+30 more)

### Community 3 - "MieAiBotConfig.kt"
Cohesion: 0.09
Nodes (24): AiProtocol, OPENAI_NEW, OPENAI_OLD, ApiConfig, ChatConfig, codePointLength(), ConfigValidationException, immutableSortedMap() (+16 more)

### Community 4 - "OpenAiChatProvider"
Cohesion: 0.14
Nodes (10): JsonNode, ObjectNode, RuntimeException, ChatApiException, ChatCompletionRequest, ChatTurn, MimoModelCapabilities, OpenAiChatProvider (+2 more)

### Community 5 - "MieAiEngine"
Cohesion: 0.08
Nodes (13): EventSubscription, ChatRole, ASSISTANT, USER, AttachmentParser, BotMentionParser, ImageAttachment, ImagePersistenceService (+5 more)

### Community 6 - "properties"
Cohesion: 0.07
Nodes (30): openai-new, openai-old, properties, maxLength, type, maxLength, minLength, pattern (+22 more)

### Community 7 - "PluginEvent"
Cohesion: 0.18
Nodes (8): PluginEvent, PluginTestContext, BotPlugin, BotPluginFactory, PluginRuntimeContext, MieAiBotPluginFactory, MieAiEngineTest, PluginIntegrationTest

### Community 8 - "Default Plugin Configuration"
Cohesion: 0.10
Nodes (23): config.yml, history.db, Java 21, mieai-bot Plugin, MieBot Framework, mimo-v2.5 Model, openai-new Protocol, openai-old Protocol (+15 more)

### Community 9 - "packagedDefaultConfig"
Cohesion: 0.07
Nodes (16): CommandAliasesConfig, MieAiBotConfig, ConfigParseException, IllegalArgumentException, T, V, MieAiConfigCodec, MieAiConfigStore (+8 more)

### Community 10 - "Pf4jSqliteParentLoaderIntegrationTest"
Cohesion: 0.22
Nodes (8): BotId, FakeEventService, Fixture, AutoCloseable, BotPluginFactory, Fixture, PluginRuntimeContext, Pf4jSqliteParentLoaderIntegrationTest

### Community 11 - "ModelFailoverManager"
Cohesion: 0.23
Nodes (6): FallbackWindow, T, ModelFailoverManager, ModelSelection, RouteKey, ModelFailoverManagerTest

### Community 12 - "DeliveryTracker"
Cohesion: 0.31
Nodes (3): java, DeliveryTracker, OpenDelivery

### Community 13 - "properties"
Cohesion: 0.05
Nodes (42): chatAlias, helpAlias, keywordAlias, mainAlias, probAlias, promptAlias, description, maxLength (+34 more)

### Community 14 - "CommandService"
Cohesion: 0.22
Nodes (7): GroupMemberRole, CommandOutcome, CommandService, DesiredCommand, Handled, NotCommand, ParsedCommand

### Community 15 - "GroupChatQueue"
Cohesion: 0.39
Nodes (4): GroupChatQueue, AutoCloseable, Worker, WorkerSelection

### Community 16 - "items"
Cohesion: 0.25
Nodes (8): items, maxItems, type, maxLength, minLength, pattern, type, disabledGroups

### Community 17 - "ImageDownloader"
Cohesion: 0.31
Nodes (4): ByteArray, PendingImage, ImageDownloader, AutoCloseable

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

## Knowledge Gaps
- **186 isolated node(s):** `USER`, `ASSISTANT`, `NotCommand`, `OPENAI_OLD`, `OPENAI_NEW` (+181 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **1 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `HistoryDatabase` connect `HistoryDatabase` to `OpenAiChatProvider`, `MieAiEngine`, `packagedDefaultConfig`, `DeliveryTracker`, `CommandService`, `GroupChatQueue`, `ImageDownloader`?**
  _High betweenness centrality (0.115) - this node is a cross-community bridge._
- **Why does `properties` connect `required` to `properties`, `properties`?**
  _High betweenness centrality (0.107) - this node is a cross-community bridge._
- **Why does `MieAiEngine` connect `MieAiEngine` to `HistoryDatabase`, `OpenAiChatProvider`, `PluginEvent`, `ModelFailoverManager`, `DeliveryTracker`, `CommandService`, `GroupChatQueue`, `ImageDownloader`?**
  _High betweenness centrality (0.069) - this node is a cross-community bridge._
- **Are the 3 inferred relationships involving `OpenAiChatProvider` (e.g. with `.everyMimoV25PrefixUsesOpenAiBearerChatCompletionsAndMultipleBase64Images()` and `.usesChatCompletionsAndBearerForOpenAiOld()`) actually correct?**
  _`OpenAiChatProvider` has 3 INFERRED edges - model-reasoned connections that need verification._
- **What connects `USER`, `ASSISTANT`, `NotCommand` to the rest of the system?**
  _186 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `HistoryDatabase` be split into smaller, more focused modules?**
  _Cohesion score 0.055900621118012424 - nodes in this community are weakly interconnected._
- **Should `required` be split into smaller, more focused modules?**
  _Cohesion score 0.041666666666666664 - nodes in this community are weakly interconnected._