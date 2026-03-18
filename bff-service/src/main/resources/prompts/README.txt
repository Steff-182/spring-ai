Prompts centralises pour les demonstrations Spring AI.

Fichiers principaux:
- question-answer-system.st : assistant conversationnel avec memoire + tools
- document-routing-system.st : system prompt commun aux scenarios prompt-only, memory, tools et RAG
- document-routing-user.st : prompt utilisateur pour la classification / routage de documents

Objectif:
- garder les prompts hors du code Java
- faciliter les ajustements de formulation et de budget de tokens
- montrer l'effet des advisors, des tools et du RAG sans dupliquer la logique applicative
Ce dossier pourra contenir vos prompts versionnes plus tard:
- summarize.st
- sentiment.st
- document-classification.st
