# Spring AI Learning

Projet d'apprentissage Spring AI avec un BFF Spring Boot et une UI HTML simple.

## Modules
- `bff-service` : backend Spring Boot exposant des endpoints pour tester Spring AI
- `embedding-service` : service dedie a la vectorisation locale (Transformers ONNX) + stockage/recherche PGVector
- `ui-simple` : UI HTML minimale pour tester les endpoints

## Demarrage rapide
1. Demarrer PostgreSQL avec extension `vector` active
2. Lancer `embedding-service` (port `8090`, contexte `/api`)
3. Lancer `bff-service` (port `8080`, contexte `/api`)
4. Definir `OPENAI_API_KEY` pour la partie arbitrage LLM (BFF)
5. Ouvrir `ui-simple/index.html`

## Ce que montre maintenant le projet
- advisors Spring AI built-in et custom
- tools Spring AI sur un referentiel documentaire factice
- RAG simple avec `SimpleVectorStore` et `QuestionAnswerAdvisor`
- prompts centralises dans `bff-service/src/main/resources/prompts`
- donnees factices fonds / typologies / statistiques dans `bff-service/src/main/resources/data`

## Endpoints utiles
- `POST /api/ai/answer-question` : QA simple
- `POST /api/ai/answer-question-memory` : QA avec `conversationId` pour la memoire
- `POST /api/ai/classify-document` : classification minimale vers `documentType`
- `POST /api/ai/route-document/basic` : prompt-only
- `POST /api/ai/route-document/memory` : advisor `MessageChatMemoryAdvisor`
- `POST /api/ai/route-document/tools` : tools Spring AI (`@Tool`)
- `POST /api/ai/route-document/rag` : RAG avec `QuestionAnswerAdvisor`
- `POST /api/ai/route-document/hybrid` : top-k semantique local via `embedding-service`, arbitrage final via OpenAI

### Endpoints utiles (`embedding-service`)
- `POST /api/embed/ingest` : ingestion d'un texte dans le vector store
- `POST /api/embed/search` : similarite semantique top-k
- `POST /api/embed/typologies/reload` : rechargement des prototypes de typologies
- `GET /api/embed/typologies/count` : nombre de typologies indexees
- `GET /api/swagger-ui.html` : Swagger UI du service d'embedding

Exemple de payload pour les endpoints de routage:

```json
{
	"conversationId": "demo-001",
	"fundCode": "CREDIMMO",
	"text": "Compromis de vente signe entre le vendeur et l'acquereur, prix de vente 325000 EUR, notaire Maître Martin..."
}
```

## Advisors

### 1. MessageChatMemoryAdvisor
Utilise un `ChatMemory` pour remettre le contexte conversationnel dans le prompt. Pratique quand un exploitant enchaine plusieurs questions sur le meme dossier.

### 2. QuestionAnswerAdvisor
Ajoute au prompt les documents recuperes depuis un vector store. C'est le chemin RAG le plus simple a expliquer et a tester.

### 3. DocumentExcerptAdvisor
Advisor custom du projet. Il tronque un OCR trop long en gardant le debut et la fin du document. L'idee n'est pas de "comprendre" le document, mais de montrer comment on peut intercepter une requete avant l'appel LLM pour maitriser le budget de tokens.

## Tools

Le projet montre l'approche `@Tool` avec `DocumentKnowledgeTools`.

Tools exposes:
- recherche de typologies candidates a partir d'un extrait OCR
- statistiques factices par fonds / typologie

Quand utiliser des tools:
- pour interroger une source de verite metier
- pour recuperer des chiffres ou des correspondances sans les recopier dans le prompt
- pour limiter les hallucinations sur des donnees operationnelles

## RAG

Le RAG du projet repose sur:
- un `SimpleVectorStore`
- les embeddings du provider actif
- des documents de connaissance generes a partir du referentiel factice
- `QuestionAnswerAdvisor`

Ce RAG sert a injecter de la connaissance documentaire sans surcharger chaque prompt.

## Etat d'avancement (Mars 2026)

### Ce qui est en place
- Embedding local en Java via Spring AI Transformers ONNX (pas d'appel OpenAI pour la vectorisation)
- Persist de vecteurs dans PostgreSQL/pgvector via `embedding-service`
- Isolation de table vectorielle (`vector_store_embedding`) pour eviter les conflits de dimension avec d'anciens essais
- Pipeline hybride operationnel: OCR/BFF -> top-k semantique local (embedding-service) -> arbitrage final OpenAI
- Swagger active sur `embedding-service`

## Observabilite du flux facture (logs)

Pour un upload via `POST /api/ai/route-document/pdf?strategy=hybrid`, vous verrez maintenant:

- Cote BFF (`bff-service`):
	- Log OCR fallback sur 2 pages max:
		- `[BFF][OCR] page=1 ... text=...`
		- `[BFF][OCR] page=2 ... text=...`
	- Log d'appel vers `embedding-service`:
		- `[BFF][EMBEDDING_ROUTE] calling embedding-service /embed/search ...`
	- Log des candidats retournes (top-k):
		- `[BFF][EMBEDDING_ROUTE] rank=1 ... cosineDistance=...`
		- `[BFF][EMBEDDING_ROUTE] rank=2 ...`
		- `[BFF][EMBEDDING_ROUTE] rank=3 ...`
	- Log du prompt complet d'arbitrage OpenAI:
		- `[BFF][OPENAI_ARBITRATION][SYSTEM] ...`
		- `[BFF][OPENAI_ARBITRATION][CONTEXT_TOP3] ...`
		- `[BFF][OPENAI_ARBITRATION][USER] ...`

- Cote Embedding Service (`embedding-service`):
	- Log du vecteur ONNX genere depuis le texte d'entree:
		- `[EMBED][ONNX_VECTOR] dimensions=... values=[...]`
	- Log des distances cosinus calculees en base PGVector (top-k):
		- `[EMBED][PG_COSINE] rank=1 ... cosineDistance=...`
		- `[EMBED][PG_COSINE] rank=2 ...`
		- `[EMBED][PG_COSINE] rank=3 ...`
	- Log des resultats retournes par l'API `/embed/search`:
		- `[EMBED][TOPK] rank=1 ... cosineDistance=...`

### Points de vigilance connus
- Le runtime ONNX/DJL reste base sur des libs natives (stabilite meilleure qu'un binding maison, mais pas 100% "pur Java bytecode")
- Verifier l'alignement `EMBED_DIMENSIONS` <-> dimension reelle du modele ONNX utilise

## Prochaines etapes (apres la pause)

1. Passer en full local inference (sans OpenAI) pour l'arbitrage de classification:
	charger un modele ONNX de classification/instruction dans un service dedie Java et l'integrer dans le flux `hybrid`.
2. Comparer qualite/latence entre:
	arbitrage OpenAI actuel vs arbitrage local ONNX (top-1/top-3 sur un jeu de documents reel).
3. Industrialiser le mode entreprise offline:
	dependances natives prepackees, cache DJL prechauffe, et validation sans acces internet en runtime.
4. Ajouter des tests d'evaluation metier:
	precision par fonds/typologie, matrice de confusion, et seuils d'acceptation pour passer en pilote.

## MCP

Le MCP est pertinent quand tu veux brancher l'agent sur des capacites externes standardisees: outils distants, referentiels, connecteurs maison, recherche, workflows.

Pour l'integrer dans Spring AI, la trajectoire type est:
1. ajouter un starter MCP client ou serveur
2. exposer tes capacites documentaires comme serveur MCP, ou consommer un serveur MCP existant
3. transformer ces capacites en outils pour le `ChatClient`

Dans ce repo, on reste volontairement focalise sur les tools locaux et le RAG pour garder la demo lisible en `1.0.0`. C'est la bonne premiere marche avant d'ajouter un serveur MCP.

## Budget de tokens et performance

### Ce qu'il ne faut pas faire par defaut
Envoyer l'OCR complet d'un compromis de vente de 20 a 30 pages a chaque appel OpenAI.

### Strategie plus realiste
1. Extraire des pages candidates: premiere page, pages de signatures, pages avec parties, montants, references dossier, clauses structurantes.
2. Faire une qualification rapide avec un prompt court ou un petit modele.
3. Interroger le referentiel via tools ou RAG pour confirmer fonds et typologie.
4. N'envoyer le document complet que pour les cas litigieux ou pour une analyse juridique plus profonde.

### Heuristique simple pour la GED
- classification / routage: quelques pages ou extraits cibles suffisent souvent
- extraction de champs: pages ciblees > document complet
- resume ou audit juridique: document complet ou segmentation par sections

### A retenir
Les tokens coutent surtout quand tu dupliques du contexte metier et quand tu envoies des OCR longs et bruites. Les advisors, les tools et le RAG servent justement a eviter ca.
